package com.tools.pdabeeper;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dịch vụ Trợ năng theo dõi ứng dụng Heineken PDA (màn hình quét là Dialog toàn màn hình).
 *
 * LỖI: một dòng chữ đang hiển thị chứa từ khóa (vd. "Pallet 1893... không tồn tại")
 *      xuất hiện MỚI -> bíp + rung + ghi LOI (mã pallet lấy từ chính dòng lỗi).
 * OK : số "Tổng SL Pallet đã quét" TĂNG -> ghi OK (mã pallet = số 18 chữ số mới xuất hiện).
 * XOA: số "đã quét" GIẢM -> ghi XOA.
 * Kèm Sales order + Số xe đang hiển thị trên màn hình.
 */
public class ErrorWatcherService extends AccessibilityService {
    private static final long SCAN_DEBOUNCE_MS = 250;
    private static final long ERROR_GONE_MS = 1500;     // dòng lỗi biến mất >1,5s mới tính là lỗi mới
    private static final int MAX_NODES = 600;
    private static final String SYSTEM_UI = "com.android.systemui";

    private static final Pattern P_COUNT = Pattern.compile("da quet\\s*:?\\s*(\\d+)");
    private static final Pattern P_SO = Pattern.compile("(?:sales order|st number|so number)\\s*:?\\s*(\\d+)");
    private static final Pattern P_XE = Pattern.compile("so xe\\s*:?\\s*((?=[a-z0-9.\\-]*\\d)[a-z0-9][a-z0-9.\\-]{3,})");
    private static final Pattern P_ID = Pattern.compile("(?<!\\d)\\d{12,20}(?!\\d)");
    private static final Pattern P_TOTAL = Pattern.compile("tong sl pallet\\s*:\\s*(\\d+)");
    private static final Pattern P_QTY = Pattern.compile("/\\s*(\\d+(?:[.,]\\d+)?)\\s*(\\p{L}+)");
    private static final Pattern P_LOC = Pattern.compile("v\\S{0,3}\\s+tr\\S{0,3}\\s*:\\s*\\|?\\s*([^|]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_PROD = Pattern.compile("s\\S{0,3}n\\s+ph\\S{0,3}m\\s*:\\s*\\|?\\s*(\\d{6,10})\\s*-\\s*([^|]+)", Pattern.CASE_INSENSITIVE);

    static volatile boolean running;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private Alarm alarm;
    private ScanLog scanLog;
    private String lastDebug = "";

    // trạng thái màn hình lần quét trước
    private String lastErrorLine;          // dòng lỗi đã báo gần nhất
    private long errorLastSeen;
    private int lastCount = -1;
    private String lastSo = "";
    private Set<String> lastIds = new LinkedHashSet<>();
    private String curSo = "", curXe = "", curAll = "";
    private int curTotal = -1;                       // "Tổng SL Pallet" trên PDA
    private List<String> curTexts = new ArrayList<>();
    private OrderCheck check;
    private Overlay overlay;
    private String errorPallet;                      // pallet đang bị báo đỏ (xóa thì ẩn cảnh báo)
    private final java.util.Set<String> doneSummary = new java.util.HashSet<>();
    private final java.util.Set<String> noDataWarned = new java.util.HashSet<>();   // SO đã bíp cảnh báo "chưa có dữ liệu GR"
    private final java.util.Map<String, PalletInfo> palletCache = new java.util.LinkedHashMap<>();

    /** Thông tin 1 pallet đọc từ màn hình PDA. */
    static final class PalletInfo {
        String pid, sku, name = "", loc = "", unit = "";
        double qty = -1;     // -1 = không đọc được
    }

    private final Runnable scan = this::scanWindows;

    @Override protected void onServiceConnected() {
        running = true;
        prefs = new Prefs(this);
        alarm = new Alarm(this);
        scanLog = new ScanLog(this, prefs);
        check = new OrderCheck(this, prefs);
        overlay = new Overlay(this);
        overlay.setOnTap(() -> alarm.stop());            // chạm vào dải cảnh báo thì tắt còi
        SheetUploader.get(this).flushAsync();             // gửi nốt các dòng còn tồn
        prefs.log("Dịch vụ đã BẬT (v" + BuildConfigVersion.NAME + "), theo dõi: " + prefs.targetPackage());
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (prefs == null || e == null) return;
        String target = prefs.targetPackage();
        String pkg = e.getPackageName() == null ? "" : e.getPackageName().toString();

        if (e.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {   // Toast
            if (!(e.getParcelableData() instanceof Notification)
                    && (pkg.equals(target) || pkg.equals(SYSTEM_UI))) {
                String text = joinText(e.getText());
                debug("TOAST[" + pkg + "] " + text);
                if (prefs.findKeyword(text) != null) { curAll = text; reportError(text, "Toast"); }
            }
            return;
        }
        if (!pkg.equals(target) && e.getEventType() != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        h.removeCallbacks(scan);
        h.postDelayed(scan, SCAN_DEBOUNCE_MS);
    }

    // ===================== ĐỌC MÀN HÌNH =====================

    private void scanWindows() {
        String target = prefs.targetPackage();
        List<String> texts = new ArrayList<>();
        boolean onScreen = false;

        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null && target.contentEquals(r.getPackageName())) {
                    onScreen = true;
                    collect(r, texts, new int[]{0});
                }
            }
        }
        if (!onScreen) { onErrorLine(null); return; }

        String all = String.join(" | ", texts);
        curAll = all;
        String norm = Prefs.normalize(all.replace(" | ", " "));   // ô chữ ngăn bởi " | " làm hỏng nhãn "đã quét: N"
        debug("MÀN HÌNH " + all);

        // --- ngữ cảnh: Sales order, số xe ---
        Matcher m = P_SO.matcher(norm);
        String so = m.find() ? m.group(1) : "";
        m = P_XE.matcher(norm);
        String xe = m.find() ? m.group(1).toUpperCase(Locale.ROOT) : "";
        curSo = so; curXe = xe;
        curTexts = texts;
        m = P_TOTAL.matcher(norm);
        curTotal = m.find() ? Integer.parseInt(m.group(1)) : -1;

        // --- dòng lỗi ---
        String errorLine = null;
        for (String t : texts) if (prefs.findKeyword(t) != null) { errorLine = t; break; }

        // --- bộ đếm "đã quét" + các mã pallet đang hiện ---
        m = P_COUNT.matcher(norm);
        int count = m.find() ? Integer.parseInt(m.group(1)) : -1;
        Set<String> ids = new LinkedHashSet<>();
        Matcher mi = P_ID.matcher(all);
        while (mi.find()) if (!mi.group().equals(so)) ids.add(mi.group());
        if (palletCache.size() > 300) palletCache.clear();
        for (String pid : ids) {                                  // đọc mã hàng + số lượng của từng pallet đang hiện
            PalletInfo pi = parseInfo(pid, check.expect(so));
            if (pi != null) palletCache.put(pid, pi);
        }

        boolean countUp = false;
        if (count >= 0) {
            boolean sameOrder = so.equals(lastSo);
            if (lastCount >= 0 && sameOrder && count != lastCount) {
                Set<String> added = diff(ids, lastIds), removed = diff(lastIds, ids);
                if (errorLine != null) added.removeAll(extractIds(errorLine));
                if (prefs.crossCheck()) added.removeIf(pid -> check.known(so, pid));   // pallet cuộn vào màn hình
                if (count > lastCount) {
                    countUp = true;
                    if (added.isEmpty()) record("OK", "", "Đã quét " + count);
                    for (String pid : added) onPalletScanned(so, pid, count);
                } else {
                    for (String pid : removed) { onPalletRemoved(so, pid); record("XOA", pid, "Đã quét " + count); }
                    if (removed.isEmpty()) record("XOA", "", "Đã quét " + count);
                }
                checkCompletion(so, count);
            }
            if (lastCount < 0 || !sameOrder) {
                debug("BẮT ĐẦU theo dõi SO=" + so + " xe=" + xe + " đã quét=" + count + " tổng=" + curTotal);
                startOrder(so, ids);
            }
            lastCount = count;
            lastSo = so;
            lastIds = ids;
        }

        // quét đúng (bộ đếm tăng) thì không coi dòng lỗi cũ còn sót là lỗi mới
        if (countUp && errorLine != null) { lastErrorLine = errorLine; errorLastSeen = now(); return; }
        onErrorLine(errorLine);
    }

    private void onErrorLine(String errorLine) {
        long t = now();
        if (errorLine == null) {
            if (lastErrorLine != null && t - errorLastSeen > ERROR_GONE_MS) lastErrorLine = null;
            // chỉ tắt còi của "lỗi dòng chữ PDA" khi dòng lỗi đã biến mất; còi SAI HÀNG/THỪA do kiểm tra chéo tự quản lý
            if (alarm.isPlaying() && alarm.kind() == Alarm.Kind.LOI && lastErrorLine == null) alarm.stop();
            return;
        }
        boolean isNew = !errorLine.equals(lastErrorLine);
        lastErrorLine = errorLine;
        errorLastSeen = t;
        if (isNew) reportError(errorLine, "Màn hình");
    }

    private void reportError(String text, String source) {
        String kw = prefs.findKeyword(text);
        prefs.log("BÍP (" + source + ", từ khóa \"" + kw + "\") ← " + shorten(text));
        alarm.start(prefs.beepSeconds(), Alarm.Kind.LOI);
        overlay.show(Overlay.Level.ERROR, "⛔ LỖI TỪ PDA\n" + shorten(text) + "\n→ Xóa pallet này trên PDA", 8000);
        record("LOI", String.join(" ", extractIds(text)), text);
    }

    private void record(String result, String code, String message) {
        prefs.log("QUÉT " + result + ": " + (code.isEmpty() ? "(không rõ mã)" : code));
        if (!prefs.scanLogEnabled()) return;
        String ctx = (curSo.isEmpty() ? "" : "SO " + curSo) + (curXe.isEmpty() ? "" : " / Xe " + curXe);
        scanLog.write(now(), result, code, ctx.trim(), shorten(message), details(code));
    }

    /** [PalletID, SoLuong, DonVi, ViTri, MaSP, TenSP] của pallet đầu tiên trong code (lấy từ bộ nhớ đệm màn hình). */
    private String[] details(String code) {
        String pid = "";
        Matcher mi = P_ID.matcher(code == null ? "" : code);
        if (mi.find()) pid = mi.group();
        PalletInfo pi = pid.isEmpty() ? null : palletCache.get(pid);
        if (pi == null) return new String[]{pid, "", "", "", "", ""};
        return new String[]{pid, pi.qty < 0 ? "" : OrderCheck.fmt(pi.qty), pi.unit, pi.loc,
                pi.sku == null ? "" : pi.sku, pi.name};
    }

    /** Chỉ lấy chữ của các view đang hiển thị thật; mỗi view 1 phần tử. */
    private void collect(AccessibilityNodeInfo n, List<String> out, int[] count) {
        if (n == null || count[0]++ > MAX_NODES || !n.isVisibleToUser()) return;
        CharSequence t = n.getText();
        if (t != null && t.toString().trim().length() > 0) out.add(t.toString().trim());
        else {
            CharSequence d = n.getContentDescription();
            if (d != null && d.toString().trim().length() > 0) out.add(d.toString().trim());
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, count);
    }

    // ===================== KIỂM TRA CHÉO GR =====================

    private void startOrder(String so, Set<String> visibleIds) {
        if (!prefs.crossCheck() || so.isEmpty()) return;
        // pallet đã quét trước khi app theo dõi: ghi nhận vào tally (không báo động)
        for (String pid : visibleIds) {
            if (!check.known(so, pid)) {
                PalletInfo pi = palletCache.get(pid);
                check.onScan(so, pid, pi == null ? null : pi.sku, pi == null ? -1 : pi.qty);
            }
        }
        check.ensure(so, false, this::onExpectLoaded);
    }

    private void onExpectLoaded(String so, OrderCheck.Expect e, String error) {
        if (e == null) {
            prefs.log("GR " + so + ": " + error);
            if (so.equals(curSo)) overlay.show(Overlay.Level.WARN, "⚠ Chưa có dữ liệu GR cho SO " + so
                    + "\n" + error + "\nKHÔNG kiểm tra chéo được");
            return;
        }
        double loose = 0; for (OrderCheck.Line l : e.lines.values()) loose += l.qty - l.fullQty;
        prefs.log("GR " + so + ": " + e.lines.size() + " mã hàng, tổng " + OrderCheck.fmt(e.totalQty())
                + (loose > 1e-9 ? " (lẻ không quét " + OrderCheck.fmt(loose) + ")" : "")
                + (error != null ? " (dùng bản lưu trên máy – " + error + ")" : ""));
    }

    private void onPalletScanned(String so, String pid, int count) {
        if (!prefs.crossCheck()) { record("OK", pid, "Đã quét " + count); return; }
        check.ensure(so, false, this::onExpectLoaded);           // làm mới định kỳ
        PalletInfo pi = palletCache.get(pid);
        String sku = pi == null ? null : pi.sku;
        OrderCheck.Verdict v = check.onScan(so, pid, sku, pi == null ? -1 : pi.qty);
        switch (v.kind) {
            case OK:
                overlay.show(Overlay.Level.OK, v.msg);
                record("OK", pid, (sku == null ? "" : sku + " ") + OrderCheck.fmt(v.scanned) + "/" + OrderCheck.fmt(v.max));
                break;
            case WRONG_SKU:
            case OVER:
                errorPallet = pid;
                alarm.start(Math.max(prefs.beepSeconds(), 3), Alarm.Kind.SAI);
                overlay.show(Overlay.Level.ERROR, "⛔ " + (v.kind == OrderCheck.Kind.WRONG_SKU ? "SAI MÃ HÀNG" : "THỪA SỐ LƯỢNG")
                        + "\n" + v.msg + "\nPallet " + pid + "\n→ Xóa pallet này trên PDA");
                prefs.log("BÍP SAI ← " + v.msg + " | pallet " + pid);
                record(v.kind == OrderCheck.Kind.WRONG_SKU ? "SAI_HANG" : "THUA_SL", pid, v.msg);
                break;
            default:   // NO_DATA, UNKNOWN_SKU, NO_QTY  -> CAM: 2 tiếng bíp ngắn + rung nhẹ
                overlay.show(Overlay.Level.WARN, "⚠ KHÔNG KIỂM TRA ĐƯỢC\n" + v.msg);
                // thiếu dữ liệu GR của cả đơn thì chỉ bíp 1 lần cho mỗi đơn, tránh kêu liên tục mỗi pallet
                if (v.kind != OrderCheck.Kind.NO_DATA || noDataWarned.add(so)) alarm.start(1, Alarm.Kind.CANH_BAO);
                record("OK_CHUA_DOI_CHIEU", pid, v.msg);
        }
    }

    private void onPalletRemoved(String so, String pid) {
        if (!prefs.crossCheck()) return;
        check.remove(so, pid);
        if (pid.equals(errorPallet)) {
            errorPallet = null;
            alarm.stop();
            overlay.show(Overlay.Level.OK, "Đã xóa pallet sai " + pid);
        }
    }

    private void checkCompletion(String so, int count) {
        if (!prefs.crossCheck() || curTotal <= 0 || count < curTotal || !doneSummary.add(so)) return;
        String diff = check.summary(so);
        if (diff == null) {
            overlay.show(Overlay.Level.OK, "✅ ĐỦ " + count + "/" + curTotal + " pallet – KHỚP GR từng mã hàng");
            record("TONG_KET", "", "Khớp GR: " + count + "/" + curTotal);
        } else {
            overlay.show(Overlay.Level.WARN, "⚠ Đủ " + count + " pallet nhưng LỆCH GR: " + diff);
            record("TONG_KET_LECH", "", diff);
        }
    }

    /**
     * Đọc thông tin 1 pallet: lấy đoạn chữ NGAY SAU mã pallet cho tới mã pallet kế tiếp
     * (mỗi pallet trên PDA gồm: mã pallet "/160 Tray", vị trí, "Sản phẩm: <mã hàng> - <tên>", lot...).
     */
    private PalletInfo parseInfo(String pid, OrderCheck.Expect e) {
        String all = curAll;
        int at = all.indexOf(pid);
        if (at < 0) return null;
        int from = at + pid.length(), to = all.length();
        Matcher nx = P_ID.matcher(all);
        if (nx.find(from)) to = nx.start();
        String seg = all.substring(from, to);
        PalletInfo p = new PalletInfo();
        p.pid = pid;
        Matcher m = P_QTY.matcher(seg);
        if (m.find() && m.start() <= 8) {                         // "/160 Tray" nằm ngay sau mã pallet
            try { p.qty = Double.parseDouble(m.group(1).replace(',', '.')); } catch (Exception ignored) { }
            p.unit = m.group(2);
        }
        m = P_LOC.matcher(seg);
        if (m.find()) p.loc = m.group(1).trim();
        m = P_PROD.matcher(seg);
        if (m.find()) { p.sku = m.group(1); p.name = m.group(2).trim(); }
        if (p.sku == null) {                                      // không có dòng "Sản phẩm: ..." -> dò theo mẫu mã hàng
            Pattern skuP;
            try { skuP = Pattern.compile("(?<!\\d)(" + prefs.skuPattern() + ")(?!\\d)"); }
            catch (Exception ex) { skuP = Pattern.compile("(?<!\\d)(1\\d{7})(?!\\d)"); }
            String first = null;
            m = skuP.matcher(seg);
            while (m.find()) {
                String c = m.group(1);
                if (e != null && e.lines.containsKey(c)) { first = c; break; }
                if (first == null) first = c;
            }
            p.sku = first;
        }
        return p;
    }

    // ===================== tiện ích =====================

    private static Set<String> extractIds(String s) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = P_ID.matcher(s == null ? "" : s);
        while (m.find()) out.add(m.group());
        return out;
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }

    private static long now() { return System.currentTimeMillis(); }

    private void debug(String snapshot) {
        if (!prefs.debugLog() || snapshot.equals(lastDebug)) return;
        lastDebug = snapshot;
        prefs.log("[dò] " + shorten(snapshot));
    }

    private static String joinText(List<CharSequence> parts) {
        StringBuilder sb = new StringBuilder();
        if (parts != null) for (CharSequence p : parts) if (p != null) sb.append(p).append(' ');
        return sb.toString().trim();
    }

    private static String shorten(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    @Override public void onInterrupt() { if (alarm != null) alarm.stop(); }

    @Override public void onDestroy() {
        running = false;
        if (alarm != null) alarm.stop();
        if (prefs != null) prefs.log("Dịch vụ đã TẮT");
        super.onDestroy();
    }
}
