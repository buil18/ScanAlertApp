package com.tools.pdabeeper;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private Prefs prefs;
    private Alarm testAlarm;
    private EditText pkg, kw, secs, skuPattern, sheetUrl, sheetToken, deviceName;
    private CheckBox debug, scanLog, crossCheck;
    private TextView status, log, csvInfo, sheetStatus;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        testAlarm = new Alarm(this);

        status = findViewById(R.id.status);
        log = findViewById(R.id.log);
        csvInfo = findViewById(R.id.csvInfo);
        pkg = findViewById(R.id.targetPackage);
        kw = findViewById(R.id.keywords);
        secs = findViewById(R.id.beepSeconds);
        debug = findViewById(R.id.debugLog);
        scanLog = findViewById(R.id.scanLog);

        crossCheck = findViewById(R.id.crossCheck);
        skuPattern = findViewById(R.id.skuPattern);
        crossCheck.setChecked(prefs.crossCheck());
        skuPattern.setText(prefs.skuPattern());
        sheetUrl = findViewById(R.id.sheetUrl);
        sheetToken = findViewById(R.id.sheetToken);
        deviceName = findViewById(R.id.deviceName);
        sheetStatus = findViewById(R.id.sheetStatus);
        sheetUrl.setText(prefs.sheetUrl());
        sheetToken.setText(prefs.sheetToken());
        deviceName.setText(prefs.deviceName());

        pkg.setText(prefs.targetPackage());
        kw.setText(prefs.keywordsRaw());
        secs.setText(String.valueOf(prefs.beepSeconds()));
        debug.setChecked(prefs.debugLog());
        scanLog.setChecked(prefs.scanLogEnabled());

        ((Button) findViewById(R.id.btnAccessibility)).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        ((Button) findViewById(R.id.btnAppInfo)).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));
        ((Button) findViewById(R.id.btnSave)).setOnClickListener(v -> {
            int s;
            try { s = Integer.parseInt(secs.getText().toString().trim()); } catch (NumberFormatException e) { s = 3; }
            prefs.save(pkg.getText().toString(), kw.getText().toString(), s, debug.isChecked(),
                    false, scanLog.isChecked(), "");
            prefs.saveSheet(sheetUrl.getText().toString(), sheetToken.getText().toString(),
                    deviceName.getText().toString());
            String pat = skuPattern.getText().toString().trim();
            try { java.util.regex.Pattern.compile(pat.isEmpty() ? "1\\d{7}" : pat); }
            catch (Exception ex) { Toast.makeText(this, "Mẫu mã hàng sai cú pháp", Toast.LENGTH_LONG).show(); return; }
            prefs.saveCross(crossCheck.isChecked(), pat);
            Toast.makeText(this, "Đã lưu", Toast.LENGTH_SHORT).show();
        });
        ((Button) findViewById(R.id.btnSheetTest)).setOnClickListener(v -> {
            prefs.saveSheet(sheetUrl.getText().toString(), sheetToken.getText().toString(),
                    deviceName.getText().toString());
            SheetUploader up = SheetUploader.get(this);
            if (!up.configured()) { Toast.makeText(this, "Chưa nhập link Web App", Toast.LENGTH_LONG).show(); return; }
            java.util.Date d = new java.util.Date();
            up.enqueue(ScanLog.today(), new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(d),
                    "TEST", "TEST-123", "", "Dòng thử từ PDA Error Beeper", new String[]{"", "", "", "", "", ""});
            sheetStatus.setText("Đang gửi…");
            sheetStatus.postDelayed(() -> sheetStatus.setText(up.status()), 6000);
        });
        ((Button) findViewById(R.id.btnTest)).setOnClickListener(v -> testAlarm.start(2));
        ((Button) findViewById(R.id.btnShareCsv)).setOnClickListener(v -> shareTodayCsv());
        Button refresh = findViewById(R.id.btnRefreshLog);
        refresh.setOnClickListener(v -> {
            log.setText(prefs.readLog());
            sheetStatus.setText(SheetUploader.get(this).status());
        });
        refresh.setOnLongClickListener(v -> { prefs.clearLog(); log.setText(""); return true; });
    }

    private void shareTodayCsv() {
        String u = prefs.csvUri(ScanLog.today());
        if (u == null) {
            Toast.makeText(this, "Hôm nay chưa có lần quét nào được ghi", Toast.LENGTH_LONG).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(u))
                .putExtra(Intent.EXTRA_SUBJECT, ScanLog.fileName(ScanLog.today()))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Gửi file log quét"));
    }

    @Override protected void onResume() {
        super.onResume();
        boolean on = ErrorWatcherService.running;
        status.setText(on ? "✅ Dịch vụ cảnh báo ĐANG CHẠY"
                          : "❌ Dịch vụ CHƯA bật – bấm nút 1 và bật \"PDA Error Beeper\"");
        status.setTextColor(on ? 0xFF1B7F2A : 0xFFC62828);
        csvInfo.setText("File log: Bộ nhớ trong › Download › " + ScanLog.FOLDER + " › "
                + ScanLog.fileName(ScanLog.today()) + " (mỗi ngày 1 file, mở bằng Excel)");
        log.setText(prefs.readLog());
        sheetStatus.setText(SheetUploader.get(this).status());
        sheetStatus.removeCallbacks(refreshStatus);
        sheetStatus.postDelayed(refreshStatus, 3000);
    }

    private final Runnable refreshStatus = new Runnable() {
        @Override public void run() {
            sheetStatus.setText(SheetUploader.get(MainActivity.this).status());
            sheetStatus.postDelayed(this, 3000);
        }
    };

    @Override protected void onPause() {
        super.onPause();
        testAlarm.stop();
        sheetStatus.removeCallbacks(refreshStatus);
    }
}
