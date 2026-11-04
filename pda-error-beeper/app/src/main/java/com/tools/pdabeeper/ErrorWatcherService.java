package com.tools.pdabeeper;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Dịch vụ Trợ năng theo dõi ứng dụng Heineken PDA.
 *  - Lỗi: popup/hộp thoại (hoặc Toast) có chữ chứa từ khóa -> bíp + rung.
 *    Chỉ đọc chữ ĐANG HIỂN THỊ; mặc định chỉ đọc trong popup, không đọc màn hình nền
 *    (tránh bíp nhầm do nhãn ẩn / dòng trạng thái cũ còn chữ lỗi).
 *  - Log quét: mỗi mã được nhập/quét vào ô nhập -> 1 dòng CSV, KetQua = OK hoặc LOI
 *    (LOI nếu trong RESULT_WINDOW_MS sau khi quét có popup lỗi).
 */
public class ErrorWatcherService extends AccessibilityService {
    private static final long SCAN_DEBOUNCE_MS = 250;
    private static final long INPUT_QUIET_MS = 500;     // ngừng gõ 0,5s = kết thúc 1 lần quét
    private static final long RESULT_WINDOW_MS = 3000;  // chờ tối đa 3s xem có lỗi không
    private static final int MAX_NODES = 500;
    private static final String SYSTEM_UI = "com.android.systemui";

    static volatile boolean running;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private Alarm alarm;
    private ScanLog scanLog;
    private boolean errorVisible;
    private String lastDebug = "";

    // --- theo dõi lần quét ---
    private String pendingCode, pendingField;     // đang nhập, chưa chốt
    private Scan openScan;                        // đã chốt, đang chờ kết quả
    private static final class Scan { long t; String code, field; }

    private final Runnable scan = this::scanWindows;
    private final Runnable finalizeInput = this::finalizeInput;
    private final Runnable closeScanOk = () -> closeScan("OK", "");

    @Override protected void onServiceConnected() {
        running = true;
        prefs = new Prefs(this);
        alarm = new Alarm(this);
        scanLog = new ScanLog(this, prefs);
        SheetUploader.get(this).flushAsync();             // gửi nốt các dòng còn tồn
        prefs.log("Dịch vụ đã BẬT, theo dõi: " + prefs.targetPackage()
                + (prefs.popupOnly() ? " (chỉ popup)" : " (toàn màn hình)"));
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (prefs == null || e == null) return;
        String target = prefs.targetPackage();
        String pkg = e.getPackageName() == null ? "" : e.getPackageName().toString();
        int type = e.getEventType();

        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {      // Toast
            if (!(e.getParcelableData() instanceof Notification)
                    && (pkg.equals(target) || pkg.equals(SYSTEM_UI))) {
                String text = joinText(e.getText());
                debug("TOAST[" + pkg + "] " + text);
                String kw = prefs.findKeyword(text);
                if (kw != null) fire(kw, "Toast: " + text);
            }
            return;
        }

        if (!pkg.equals(target) && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            onInputChanged(e);
            return;
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            debug("CỬA SỔ class=" + e.getClassName() + " text=" + joinText(e.getText()));
        }
        h.removeCallbacks(scan);
        h.postDelayed(scan, SCAN_DEBOUNCE_MS);
    }

    // ===================== PHÁT HIỆN LỖI =====================

    private void scanWindows() {
        String target = prefs.targetPackage();
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        List<Rect> bounds = new ArrayList<>();

        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null && target.contentEquals(r.getPackageName())) {
                    Rect b = new Rect();
                    w.getBoundsInScreen(b);
                    roots.add(r);
                    bounds.add(b);
                }
            }
        }
        if (roots.isEmpty()) { updateError(false, null, ""); return; }

        // Cửa sổ lớn nhất = màn hình chính của app; các cửa sổ còn lại = popup/hộp thoại
        int main = 0;
        for (int i = 1; i < bounds.size(); i++) if (area(bounds.get(i)) > area(bounds.get(main))) main = i;

        StringBuilder popup = new StringBuilder(), screen = new StringBuilder();
        for (int i = 0; i < roots.size(); i++) {
            collect(roots.get(i), i == main ? screen : popup, new int[]{0});
        }
        if (popup.length() > 0) debug("POPUP " + popup);
        else debug("MÀN HÌNH " + screen);

        String checkText = prefs.popupOnly() ? popup.toString() : popup + " " + screen;
        String kw = prefs.findKeyword(checkText);
        updateError(kw != null, kw, popup.length() > 0 ? popup.toString() : screen.toString());
    }

    private void updateError(boolean nowError, String kw, String text) {
        if (nowError && !errorVisible) fire(kw, text);
        else if (!nowError && errorVisible && alarm.isPlaying()) alarm.stop();   // đã bấm OK -> im
        errorVisible = nowError;
    }

    /** Chỉ lấy chữ của các view đang hiển thị thật trên màn hình. */
    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int[] count) {
        if (n == null || count[0]++ > MAX_NODES || !n.isVisibleToUser()) return;
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) sb.append(t).append(" | ");
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0) sb.append(d).append(" | ");
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), sb, count);
    }

    private void fire(String keyword, String text) {
        prefs.log("BÍP (từ khóa \"" + keyword + "\") ← " + shorten(text));
        alarm.start(prefs.beepSeconds());
        if (pendingCode != null) finalizeInput();          // lỗi hiện trước khi kịp chốt mã
        if (openScan != null) closeScan("LOI", text);
        else if (prefs.scanLogEnabled()) scanLog.write(System.currentTimeMillis(), "LOI", "", "", text);
    }

    // ===================== GHI LOG QUÉT =====================

    private void onInputChanged(AccessibilityEvent e) {
        if (e.isPassword()) return;
        String field = "";
        AccessibilityNodeInfo src = e.getSource();
        if (src != null && src.getViewIdResourceName() != null) field = src.getViewIdResourceName();
        String text = joinText(e.getText());
        debug("NHẬP ô=" + field + " giá trị=\"" + text + "\"");

        String filter = prefs.scanFieldId();
        if (!filter.isEmpty() && !field.endsWith(filter)) return;

        h.removeCallbacks(finalizeInput);
        if (!text.isEmpty()) {
            pendingCode = text;
            pendingField = field;
            h.postDelayed(finalizeInput, INPUT_QUIET_MS);
        } else if (pendingCode != null) {
            finalizeInput();                               // app xóa ô sau khi nhận mã -> chốt luôn
        }
    }

    private void finalizeInput() {
        h.removeCallbacks(finalizeInput);
        if (pendingCode == null) return;
        if (openScan != null) closeScan("OK", "");        // mã trước không có lỗi
        Scan s = new Scan();
        s.t = System.currentTimeMillis();
        s.code = pendingCode;
        s.field = pendingField;
        openScan = s;
        pendingCode = pendingField = null;
        h.postDelayed(closeScanOk, RESULT_WINDOW_MS);
    }

    private void closeScan(String result, String message) {
        h.removeCallbacks(closeScanOk);
        Scan s = openScan;
        openScan = null;
        if (s == null) return;
        prefs.log("QUÉT " + result + ": " + s.code);
        if (prefs.scanLogEnabled()) scanLog.write(s.t, result, s.code, shortId(s.field), shorten(message));
    }

    // ===================== tiện ích =====================

    private void debug(String snapshot) {
        if (!prefs.debugLog() || snapshot.equals(lastDebug)) return;
        lastDebug = snapshot;
        prefs.log("[dò] " + shorten(snapshot));
    }

    private static long area(Rect r) { return (long) r.width() * r.height(); }

    private static String shortId(String id) {
        int i = id == null ? -1 : id.indexOf(":id/");
        return i >= 0 ? id.substring(i + 4) : (id == null ? "" : id);
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
        if (openScan != null) closeScan("OK", "");
        if (alarm != null) alarm.stop();
        if (prefs != null) prefs.log("Dịch vụ đã TẮT");
        super.onDestroy();
    }
}
