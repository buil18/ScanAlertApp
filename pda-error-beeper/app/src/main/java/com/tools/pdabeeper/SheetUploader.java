package com.tools.pdabeeper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gửi log quét lên Google Sheet (qua Google Apps Script Web App).
 * Dòng chưa gửi được (mất Wi-Fi...) được lưu vào hàng đợi trên máy và tự gửi lại sau.
 */
final class SheetUploader {
    private static final int BATCH = 50;
    private static final long RETRY_SEC = 30;
    private static SheetUploader instance;

    private final Prefs prefs;
    private final File queue;
    private final ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();
    private boolean retryScheduled;
    private volatile String lastStatus = "Chưa gửi lần nào";

    static synchronized SheetUploader get(Context c) {
        if (instance == null) instance = new SheetUploader(c.getApplicationContext());
        return instance;
    }

    private SheetUploader(Context c) {
        prefs = new Prefs(c);
        queue = new File(c.getFilesDir(), "sheet_queue.jsonl");
    }

    boolean configured() { return !prefs.sheetUrl().isEmpty() && !prefs.sheetToken().isEmpty(); }
    String status() { return lastStatus + " – đang chờ gửi: " + pendingCount() + " dòng"; }

    /** Thêm 1 dòng vào hàng đợi và gửi ngay. */
    void enqueue(String day, String time, String result, String code, String field, String message, String[] det) {
        if (!configured()) return;
        JSONArray row = new JSONArray();
        row.put(day + " " + time).put(prefs.deviceName()).put(result).put(code).put(field).put(message);
        for (String x : det) row.put(x);
        ex.execute(() -> { appendLine(row.toString()); flush(); });
    }

    void flushAsync() { ex.execute(this::flush); }

    // -------- chạy trên luồng nền --------

    private void flush() {
        if (!configured()) return;
        while (true) {
            List<String> lines = readLines();
            if (lines.isEmpty()) return;
            List<String> batch = lines.subList(0, Math.min(BATCH, lines.size()));
            try {
                post(batch);
                writeLines(new ArrayList<>(lines.subList(batch.size(), lines.size())));
                lastStatus = "Gửi OK lúc " + new java.text.SimpleDateFormat("HH:mm:ss",
                        java.util.Locale.ROOT).format(new java.util.Date());
            } catch (Exception e) {
                lastStatus = "Lỗi gửi: " + e.getMessage();
                scheduleRetry();
                return;
            }
        }
    }

    private void scheduleRetry() {
        if (retryScheduled) return;
        retryScheduled = true;
        ex.schedule(() -> { retryScheduled = false; flush(); }, RETRY_SEC, TimeUnit.SECONDS);
    }

    private void post(List<String> lines) throws Exception {
        JSONArray rows = new JSONArray();
        for (String l : lines) rows.put(new JSONArray(l));
        JSONObject body = new JSONObject().put("token", prefs.sheetToken()).put("rows", rows);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = (HttpURLConnection) new URL(prefs.sheetUrl()).openConnection();
        c.setInstanceFollowRedirects(false);   // Apps Script trả 302 sau khi đã chạy xong doPost
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
        int code = c.getResponseCode();
        String location = c.getHeaderField("Location");
        String resp = "";
        try (InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream()) {
            resp = readAll(is);
        } finally { c.disconnect(); }

        if (code == 302 || code == 303) {
            // Apps Script: 302 = đã nhận. Kiểm tra token bằng cách đọc trang đích.
            if (location != null) {
                HttpURLConnection g = (HttpURLConnection) new URL(location).openConnection();
                g.setConnectTimeout(15000); g.setReadTimeout(20000);
                try (InputStream is = g.getInputStream()) {
                    resp = readAll(is);
                } finally { g.disconnect(); }
            } else resp = "ok";
        } else if (code != 200) {
            throw new Exception("HTTP " + code);
        }
        if (!resp.trim().startsWith("ok")) {
            throw new Exception(resp.trim().isEmpty() ? "không có phản hồi" : shorten(resp.trim()));
        }
    }

    /** Đọc trọn phản hồi (tối đa 4 KB) – tránh đọc thiếu khiến tưởng lỗi rồi gửi trùng. */
    private static String readAll(InputStream is) throws java.io.IOException {
        if (is == null) return "";
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) > 0 && bo.size() < 4096) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    // -------- hàng đợi trên file --------

    int pendingCount() { return readLines().size(); }

    private synchronized List<String> readLines() {
        List<String> out = new ArrayList<>();
        if (!queue.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(queue))) {
            String l;
            while ((l = r.readLine()) != null) if (!l.trim().isEmpty()) out.add(l);
        } catch (Exception ignored) { }
        return out;
    }

    private synchronized void appendLine(String line) {
        try (FileOutputStream os = new FileOutputStream(queue, true)) {
            os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { prefs.log("Lỗi lưu hàng đợi: " + e); }
    }

    private synchronized void writeLines(List<String> lines) {
        try (FileOutputStream os = new FileOutputStream(queue, false)) {
            for (String l : lines) os.write((l + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { prefs.log("Lỗi lưu hàng đợi: " + e); }
    }

    private static String shorten(String s) { return s.length() > 120 ? s.substring(0, 120) + "…" : s; }
}
