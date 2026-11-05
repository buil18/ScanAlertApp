package com.tools.pdabeeper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kiểm tra chéo PDA ↔ GR theo TỪNG MÃ HÀNG rồi cộng tổng.
 *  - Dữ liệu GR của SO tải từ cổng Apps Script (op=pda), lưu lại trên máy để dùng khi mất mạng.
 *  - Tally: pallet đã quét -> mã hàng (lưu trên máy theo SO).
 * Nguyên tắc: thiếu dữ liệu thì báo VÀNG, không báo xanh, không đoán.
 */
final class OrderCheck {
    static final long REFRESH_MS = 5 * 60 * 1000;

    /** Dòng hàng của đơn theo GR (đã gộp theo mã hàng). */
    static final class Line { String sku, name; double qty; int perPallet; int maxPallets; }

    static final class Expect {
        String so, fetchedAt; long fetchedMs;
        Map<String, Line> lines = new LinkedHashMap<>();
        boolean allHaveMaster() { for (Line l : lines.values()) if (l.maxPallets < 0) return false; return true; }
        int totalPallets() { int t = 0; for (Line l : lines.values()) t += Math.max(l.maxPallets, 0); return t; }
    }

    enum Kind { NO_DATA, UNKNOWN_SKU, WRONG_SKU, OVER, OK }

    static final class Verdict {
        Kind kind; String sku, msg; int scanned, max;
    }

    interface Listener { void onExpectLoaded(String so, Expect e, String error); }

    private final SharedPreferences sp;
    private final Prefs prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Expect> cache = new LinkedHashMap<>();
    private final Map<String, Boolean> loading = new LinkedHashMap<>();

    OrderCheck(Context c, Prefs p) {
        sp = c.getApplicationContext().getSharedPreferences("check", Context.MODE_PRIVATE);
        prefs = p;
    }

    // ================= dữ liệu GR =================

    Expect expect(String so) {
        Expect e = cache.get(so);
        if (e == null) {
            String raw = sp.getString("exp_" + so, null);
            if (raw != null) try { e = parse(so, new JSONObject(raw)); cache.put(so, e); } catch (Exception ignored) { }
        }
        return e;
    }

    /** Tải (hoặc tải lại sau REFRESH_MS) dữ liệu GR của SO; kết quả trả về luồng chính. */
    void ensure(String so, boolean force, Listener l) {
        if (so == null || so.isEmpty() || prefs.sheetUrl().isEmpty()) return;
        Expect cur = expect(so);
        if (!force && cur != null && System.currentTimeMillis() - cur.fetchedMs < REFRESH_MS) return;
        if (Boolean.TRUE.equals(loading.get(so))) return;
        loading.put(so, true);
        io.execute(() -> {
            Expect got = null; String err = null;
            try {
                JSONObject j = fetch(so);
                if (j.optBoolean("ok")) {
                    got = parse(so, j);
                    sp.edit().putString("exp_" + so, j.toString()).apply();
                } else err = j.optString("reason", "không rõ lý do");
            } catch (Exception ex) { err = "Lỗi mạng: " + ex.getMessage(); }
            final Expect fg = got; final String fe = err;
            main.post(() -> {
                loading.remove(so);
                if (fg != null) cache.put(so, fg);
                l.onExpectLoaded(so, fg != null ? fg : expect(so), fe);
            });
        });
    }

    private JSONObject fetch(String so) throws Exception {
        String url = prefs.sheetUrl() + (prefs.sheetUrl().contains("?") ? "&" : "?")
                + "op=pda&so=" + URLEncoder.encode(so, "UTF-8")
                + "&token=" + URLEncoder.encode(prefs.sheetToken(), "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        try (InputStream is = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            String body = bo.toString("UTF-8").trim();
            if (!body.startsWith("{")) throw new Exception("phản hồi không phải JSON: "
                    + (body.length() > 60 ? body.substring(0, 60) : body));
            return new JSONObject(body);
        } finally { c.disconnect(); }
    }

    private static Expect parse(String so, JSONObject j) throws Exception {
        Expect e = new Expect();
        e.so = so;
        e.fetchedAt = j.optString("updated", "");
        e.fetchedMs = j.optLong("_fetchedMs", System.currentTimeMillis());
        if (!j.has("_fetchedMs")) j.put("_fetchedMs", e.fetchedMs);
        JSONArray arr = j.getJSONArray("lines");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Line l = new Line();
            l.sku = o.getString("sku").trim();
            l.name = o.optString("name", "");
            l.qty = o.optDouble("qty", 0);
            l.perPallet = o.optInt("perPallet", 0);
            l.maxPallets = o.optInt("maxPallets", -1);     // -1 = chưa có quy cách
            e.lines.put(l.sku, l);
        }
        return e;
    }

    // ================= tally pallet đã quét =================

    private JSONObject tally(String so) {
        try { return new JSONObject(sp.getString("tally_" + so, "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private void saveTally(String so, JSONObject t) {
        SharedPreferences.Editor ed = sp.edit().putString("tally_" + so, t.toString());
        // giữ tối đa ~30 SO gần nhất
        Map<String, ?> all = sp.getAll();
        int n = 0; for (String k : all.keySet()) if (k.startsWith("tally_")) n++;
        if (n > 30) for (String k : all.keySet()) if (k.startsWith("tally_") && !k.equals("tally_" + so)) { ed.remove(k); break; }
        ed.apply();
    }

    boolean known(String so, String pallet) { return tally(so).has(pallet); }

    int scannedOf(String so, String sku) {
        JSONObject t = tally(so); int c = 0;
        for (Iterator<String> it = t.keys(); it.hasNext(); ) if (sku.equals(t.optString(it.next()))) c++;
        return c;
    }

    int tallySize(String so) { return tally(so).length(); }

    void remove(String so, String pallet) {
        JSONObject t = tally(so);
        t.remove(pallet);
        saveTally(so, t);
    }

    /** Ghi nhận 1 pallet vừa quét và trả kết quả đối chiếu. sku = null nếu không đọc được. */
    Verdict onScan(String so, String pallet, String sku) {
        Verdict v = new Verdict();
        v.sku = sku;
        JSONObject t = tally(so);
        try { t.put(pallet, sku == null ? "" : sku); } catch (Exception ignored) { }
        saveTally(so, t);

        Expect e = expect(so);
        if (e == null) { v.kind = Kind.NO_DATA; v.msg = "Chưa có dữ liệu GR cho SO " + so + " – không kiểm tra được"; return v; }
        if (sku == null) { v.kind = Kind.UNKNOWN_SKU; v.msg = "Không đọc được mã hàng của pallet " + pallet; return v; }
        Line l = e.lines.get(sku);
        if (l == null) {
            v.kind = Kind.WRONG_SKU;
            v.msg = "SAI HÀNG: mã " + sku + " KHÔNG có trong đơn " + so;
            return v;
        }
        v.scanned = scannedOf(so, sku);
        v.max = l.maxPallets;
        if (l.maxPallets < 0) {
            v.kind = Kind.UNKNOWN_SKU;
            v.msg = sku + " có trong đơn nhưng chưa có quy cách pallet (Master) – đã quét " + v.scanned;
        } else if (v.scanned > l.maxPallets) {
            v.kind = Kind.OVER;
            v.msg = "THỪA PALLET: " + sku + " " + shortName(l.name) + " đã quét " + v.scanned + " / GR " + l.maxPallets;
        } else {
            v.kind = Kind.OK;
            v.msg = "✓ " + sku + " " + shortName(l.name) + "  " + v.scanned + "/" + l.maxPallets;
        }
        return v;
    }

    /** Tổng kết từng mã hàng: trả null nếu khớp hết, ngược lại mô tả chênh lệch. */
    String summary(String so) {
        Expect e = expect(so);
        if (e == null) return "Chưa có dữ liệu GR";
        StringBuilder sb = new StringBuilder();
        for (Line l : e.lines.values()) {
            int s = scannedOf(so, l.sku);
            if (l.maxPallets >= 0 && s != l.maxPallets)
                sb.append(l.sku).append(" ").append(s).append("/").append(l.maxPallets).append("; ");
        }
        JSONObject t = tally(so);
        for (Iterator<String> it = t.keys(); it.hasNext(); ) {
            String sku = t.optString(it.next());
            if (sku.isEmpty()) { sb.append("có pallet chưa rõ mã hàng; "); break; }
        }
        return sb.length() == 0 ? null : sb.toString().trim();
    }

    private static String shortName(String n) { return n == null ? "" : (n.length() > 28 ? n.substring(0, 28) + "…" : n); }
}
