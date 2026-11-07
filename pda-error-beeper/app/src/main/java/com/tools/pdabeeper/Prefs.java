package com.tools.pdabeeper;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Cấu hình + nhật ký đơn giản, lưu trong SharedPreferences. */
final class Prefs {
    static final String DEFAULT_PACKAGE = "com.heineken.cmobilepda";
    static final String DEFAULT_KEYWORDS = "khong ton tai\nnot exist\nda duoc ghi nhan\ntong sl pallet da quet phai bang";
    /** Link Web App đặt sẵn để máy mới không phải gõ lại. Khóa/TOKEN KHÔNG đặt sẵn, nhập tay trên từng máy. */
    static final String DEFAULT_LOG_URL = "https://script.google.com/macros/s/AKfycby2TwuADMNJsSpn2i24Qd68GFUdZjqC7G5a5Wh__qST_1qW8UJ4VqbXl1aScccc1Hwtzg/exec";
    static final String DEFAULT_GR_URL  = "https://script.google.com/macros/s/AKfycby6aXZjQrJglYTeQwQN2wZVkgkFSE9_6QnXrE6uEjIQYjVl1kA5ke5t0fcrfyqhQFGkqA/exec";
    private static final int MAX_LOG_CHARS = 8000;

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    String targetPackage()  { return sp.getString("pkg", DEFAULT_PACKAGE).trim(); }
    String keywordsRaw()    { return sp.getString("kw", DEFAULT_KEYWORDS); }
    int beepSeconds()       { return sp.getInt("secs", 3); }
    boolean debugLog()      { return sp.getBoolean("debug", false); }
    /** true = chỉ bắt chữ trong popup/hộp thoại (mặc định); false = quét toàn màn hình. */
    boolean popupOnly()     { return sp.getBoolean("popupOnly", true); }
    boolean scanLogEnabled(){ return sp.getBoolean("scanLog", true); }
    /** ID ô nhập mã quét (vd. com.heineken.cmobilepda:id/edtPallet). Trống = mọi ô nhập. */
    String scanFieldId()    { return sp.getString("scanField", "").trim(); }

    void save(String pkg, String kw, int secs, boolean debug,
              boolean popupOnly, boolean scanLog, String scanField) {
        sp.edit().putString("pkg", pkg.trim()).putString("kw", kw)
                .putInt("secs", Math.max(1, Math.min(secs, 30))).putBoolean("debug", debug)
                .putBoolean("popupOnly", popupOnly).putBoolean("scanLog", scanLog)
                .putString("scanField", scanField.trim()).apply();
    }

    /** Danh sách từ khóa đã chuẩn hóa (bỏ dấu, chữ thường). */
    List<String> keywords() {
        List<String> out = new ArrayList<>();
        for (String line : keywordsRaw().split("\n")) {
            String k = normalize(line);
            if (!k.isEmpty()) out.add(k);
        }
        return out;
    }

    /** Trả về từ khóa khớp đầu tiên, hoặc null. */
    String findKeyword(String raw) {
        String norm = normalize(raw);
        if (norm.isEmpty()) return null;
        for (String k : keywords()) if (norm.contains(k)) return k;
        return null;
    }

    /** "Pallet KHÔNG tồn  tại!" -> "pallet khong ton tai!" */
    static String normalize(CharSequence s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toString(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D');
        return n.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    // --- Kiểm tra chéo GR ---
    boolean crossCheck()   { return sp.getBoolean("cross", true); }
    /** Mẫu mã hàng (regex), mặc định 8 số bắt đầu bằng 1, vd 10136696. */
    String skuPattern()    { return sp.getString("skuPat", "1\\d{7}"); }
    void saveCross(boolean on, String pat) {
        sp.edit().putBoolean("cross", on).putString("skuPat", pat.trim().isEmpty() ? "1\\d{7}" : pat.trim()).apply();
    }

    // --- PIN quản trị (Admin) ---
    boolean hasPin() { return sp.contains("pinHash"); }

    void setPin(String pin) {
        byte[] b = new byte[8];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder salt = new StringBuilder();
        for (byte x : b) salt.append(String.format(Locale.ROOT, "%02x", x));
        sp.edit().putString("pinSalt", salt.toString()).putString("pinHash", hashPin(salt.toString(), pin)).apply();
    }

    boolean checkPin(String pin) {
        String h = sp.getString("pinHash", null);
        return h != null && h.equals(hashPin(sp.getString("pinSalt", ""), pin));
    }

    private static String hashPin(String salt, String pin) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((salt + ":" + pin).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format(Locale.ROOT, "%02x", x));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    // --- Google Sheet ---
    String sheetUrl()   { String v = sp.getString("sheetUrl", "").trim(); return v.isEmpty() ? DEFAULT_LOG_URL : v; }
    String sheetToken() { return sp.getString("sheetToken", "").trim(); }
    String grUrl()      { String v = sp.getString("grUrl", "").trim(); return v.isEmpty() ? DEFAULT_GR_URL : v; }
    String grKey()      { return sp.getString("grKey", "").trim(); }
    void saveGr(String url, String key) {
        sp.edit().putString("grUrl", url.trim()).putString("grKey", key.trim()).apply();
    }
    String deviceName() { return sp.getString("device", android.os.Build.MODEL).trim(); }
    void saveSheet(String url, String token, String device) {
        sp.edit().putString("sheetUrl", url.trim()).putString("sheetToken", token.trim())
                .putString("device", device.trim()).apply();
    }

    // Uri file CSV của từng ngày (MediaStore)
    String csvUri(String day)            { return sp.getString("csv_" + day, null); }
    void setCsvUri(String day, String u) { sp.edit().putString("csv_" + day, u).apply(); }

    synchronized void log(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        String all = ts + "  " + msg + "\n" + sp.getString("log", "");
        if (all.length() > MAX_LOG_CHARS) all = all.substring(0, MAX_LOG_CHARS);
        sp.edit().putString("log", all).apply();
    }

    String readLog()  { return sp.getString("log", ""); }
    void clearLog()   { sp.edit().remove("log").apply(); }
}
