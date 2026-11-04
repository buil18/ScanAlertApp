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
    static final String DEFAULT_KEYWORDS = "khong ton tai\nnot exist\nnot found";
    private static final int MAX_LOG_CHARS = 6000;

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    String targetPackage() { return sp.getString("pkg", DEFAULT_PACKAGE).trim(); }
    String keywordsRaw()   { return sp.getString("kw", DEFAULT_KEYWORDS); }
    int beepSeconds()      { return sp.getInt("secs", 3); }
    boolean debugLog()     { return sp.getBoolean("debug", false); }

    void save(String pkg, String kw, int secs, boolean debug) {
        sp.edit().putString("pkg", pkg.trim()).putString("kw", kw)
                .putInt("secs", Math.max(1, Math.min(secs, 30))).putBoolean("debug", debug).apply();
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

    /** "Pallet KHÔNG tồn  tại!" -> "pallet khong ton tai!" */
    static String normalize(CharSequence s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toString(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D');
        return n.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    synchronized void log(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        String all = ts + "  " + msg + "\n" + sp.getString("log", "");
        if (all.length() > MAX_LOG_CHARS) all = all.substring(0, MAX_LOG_CHARS);
        sp.edit().putString("log", all).apply();
    }

    String readLog()  { return sp.getString("log", ""); }
    void clearLog()   { sp.edit().remove("log").apply(); }
}
