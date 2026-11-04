package com.tools.pdabeeper;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ghi mỗi lần quét thành 1 dòng CSV, mỗi ngày 1 file:
 *   Download/PdaBeeper/scan_2026-11-04.csv  (mở được bằng Excel, có BOM UTF-8 cho tiếng Việt)
 */
final class ScanLog {
    static final String HEADER = "Ngay,Gio,KetQua,MaQuet,O_Nhap,ThongBao\r\n";
    static final String FOLDER = "PdaBeeper";

    private final Context ctx;
    private final Prefs prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    ScanLog(Context c, Prefs p) { ctx = c.getApplicationContext(); prefs = p; }

    static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()); }
    static String fileName(String day) { return "scan_" + day + ".csv"; }

    void write(long timeMs, String result, String code, String field, String message) {
        Date d = new Date(timeMs);
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(d);
        String row = day + "," + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(d) + ","
                + csv(result) + "," + csv(code) + "," + csv(field) + "," + csv(message) + "\r\n";
        SheetUploader.get(ctx).enqueue(day, new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(d),
                result, code, field, message);
        io.execute(() -> {
            try { append(day, row); }
            catch (Exception e) { prefs.log("Lỗi ghi file CSV: " + e); }
        });
    }

    private void append(String day, String row) throws Exception {
        byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT < 29) {                       // máy cũ: thư mục riêng của app
            File dir = new File(ctx.getExternalFilesDir(null), FOLDER);
            dir.mkdirs();
            File f = new File(dir, fileName(day));
            boolean isNew = !f.exists();
            try (OutputStream os = new FileOutputStream(f, true)) {
                if (isNew) os.write(header());
                os.write(bytes);
            }
            return;
        }
        ContentResolver cr = ctx.getContentResolver();
        String saved = prefs.csvUri(day);
        if (saved != null) {
            try (OutputStream os = cr.openOutputStream(Uri.parse(saved), "wa")) {
                if (os != null) { os.write(bytes); return; }
            } catch (Exception ignored) { /* file bị xóa -> tạo lại */ }
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(day));
        v.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IllegalStateException("Không tạo được file");
        try (OutputStream os = cr.openOutputStream(uri, "wa")) {
            os.write(header());
            os.write(bytes);
        }
        prefs.setCsvUri(day, uri.toString());
    }

    private static byte[] header() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] h = HEADER.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bom.length + h.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(h, 0, out, bom.length, h.length);
        return out;
    }

    private static String csv(String s) {
        if (s == null) s = "";
        s = s.replace("\r", " ").replace("\n", " ").trim();
        // chặn Excel hiểu nhầm thành công thức
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
