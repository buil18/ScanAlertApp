package com.tools.pdabeeper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int MAX_TRIES = 5;
    private static final long LOCK_MS = 60_000;

    private Prefs prefs;
    private Alarm testAlarm;
    private EditText pkg, kw, secs, skuPattern, sheetUrl, sheetToken, deviceName, grUrl, grKey;
    private CheckBox debug, scanLog, crossCheck;
    private TextView status, log, csvInfo, sheetStatus, tvCross;
    private View adminPanel;
    private Button btnAdmin;
    private int failCount;
    private long lockedUntil;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        testAlarm = new Alarm(this);

        status = findViewById(R.id.status);
        tvCross = findViewById(R.id.tvCross);
        log = findViewById(R.id.log);
        csvInfo = findViewById(R.id.csvInfo);
        sheetStatus = findViewById(R.id.sheetStatus);
        adminPanel = findViewById(R.id.adminPanel);
        btnAdmin = findViewById(R.id.btnAdmin);

        pkg = findViewById(R.id.targetPackage);
        kw = findViewById(R.id.keywords);
        secs = findViewById(R.id.beepSeconds);
        debug = findViewById(R.id.debugLog);
        scanLog = findViewById(R.id.scanLog);
        crossCheck = findViewById(R.id.crossCheck);
        skuPattern = findViewById(R.id.skuPattern);
        sheetUrl = findViewById(R.id.sheetUrl);
        sheetToken = findViewById(R.id.sheetToken);
        grUrl = findViewById(R.id.grUrl);
        grKey = findViewById(R.id.grKey);
        deviceName = findViewById(R.id.deviceName);

        ((TextView) findViewById(R.id.tvAbout)).setText(
                "ScanAlert – Cảnh báo quét pallet\n"
                + "Ứng dụng nội bộ hỗ trợ vận hành kho: cảnh báo ngay khi quét pallet lỗi, sai mã hàng "
                + "hoặc vượt số lượng so với đơn (GR), đồng thời ghi nhật ký quét để theo dõi trên Google Sheet.\n\n"
                + "Phát triển bởi Tiến Lợi – Supervisor, Kho Hóc Môn.\n"
                + "Phiên bản " + BuildConfigVersion.NAME);

        loadFields();

        // ---- Việc ai cũng làm được ----
        findViewById(R.id.btnAccessibility).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btnTestRed).setOnClickListener(v -> testAlarm.start(2, Alarm.Kind.SAI));
        findViewById(R.id.btnTestLoi).setOnClickListener(v -> testAlarm.start(2, Alarm.Kind.LOI));
        findViewById(R.id.btnTestAmber).setOnClickListener(v -> testAlarm.start(1, Alarm.Kind.CANH_BAO));
        findViewById(R.id.btnShareCsv).setOnClickListener(v -> shareTodayCsv());
        Button refresh = findViewById(R.id.btnRefreshLog);
        refresh.setOnClickListener(v -> {
            log.setText(prefs.readLog());
            sheetStatus.setText(SheetUploader.get(this).status());
        });
        refresh.setOnLongClickListener(v -> {
            if (adminPanel.getVisibility() != View.VISIBLE) {
                Toast.makeText(this, "Chỉ Admin được xóa nhật ký", Toast.LENGTH_SHORT).show();
                return true;
            }
            prefs.clearLog(); log.setText(""); return true;
        });

        // ---- Khu vực Admin (khóa PIN) ----
        btnAdmin.setOnClickListener(v -> { if (adminPanel.getVisibility() == View.VISIBLE) lock(); else openAdmin(); });
        findViewById(R.id.btnLock).setOnClickListener(v -> lock());
        findViewById(R.id.btnChangePin).setOnClickListener(v -> changePin());
        findViewById(R.id.btnAppInfo).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        findViewById(R.id.btnSheetTest).setOnClickListener(v -> sheetTest());
    }

    private void loadFields() {
        crossCheck.setChecked(prefs.crossCheck());
        skuPattern.setText(prefs.skuPattern());
        sheetUrl.setText(prefs.sheetUrl());
        sheetToken.setText(prefs.sheetToken());
        grUrl.setText(prefs.grUrl());
        grKey.setText(prefs.grKey());
        deviceName.setText(prefs.deviceName());
        pkg.setText(prefs.targetPackage());
        kw.setText(prefs.keywordsRaw());
        secs.setText(String.valueOf(prefs.beepSeconds()));
        debug.setChecked(prefs.debugLog());
        scanLog.setChecked(prefs.scanLogEnabled());
    }

    // ===================== Admin / PIN =====================

    private void promptPin(String title, String msg, java.util.function.Consumer<String> ok) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        et.setGravity(Gravity.CENTER);
        et.setTextSize(24);
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setView(et)
                .setPositiveButton("OK", (d, w) -> ok.accept(et.getText().toString().trim()))
                .setNegativeButton("Hủy", null).show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void openAdmin() {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) {
            toast("Nhập sai nhiều lần. Thử lại sau " + ((lockedUntil - now) / 1000 + 1) + " giây");
            return;
        }
        if (!prefs.hasPin()) {
            promptPin("Tạo mã PIN Admin",
                    "Nhập mã PIN gồm 4–8 chữ số. Chỉ người có mã này mới sửa được cấu hình.", p1 -> {
                        if (!p1.matches("\\d{4,8}")) { toast("PIN phải gồm 4–8 chữ số"); return; }
                        promptPin("Nhập lại mã PIN", "Nhập lại để xác nhận.", p2 -> {
                            if (!p1.equals(p2)) { toast("Hai lần nhập không khớp"); return; }
                            prefs.setPin(p1);
                            toast("Đã tạo mã PIN Admin");
                            unlock();
                        });
                    });
            return;
        }
        promptPin("Đăng nhập Admin", "Nhập mã PIN quản trị.", p -> {
            if (prefs.checkPin(p)) { failCount = 0; unlock(); return; }
            if (++failCount >= MAX_TRIES) {
                failCount = 0;
                lockedUntil = System.currentTimeMillis() + LOCK_MS;
                toast("Sai " + MAX_TRIES + " lần. Khóa 60 giây");
            } else toast("Sai mã PIN");
        });
    }

    private void changePin() {
        promptPin("Đổi mã PIN", "Nhập mã PIN hiện tại.", old -> {
            if (!prefs.checkPin(old)) { toast("Sai mã PIN hiện tại"); return; }
            promptPin("Mã PIN mới", "Gồm 4–8 chữ số.", n1 -> {
                if (!n1.matches("\\d{4,8}")) { toast("PIN phải gồm 4–8 chữ số"); return; }
                promptPin("Nhập lại mã PIN mới", "Nhập lại để xác nhận.", n2 -> {
                    if (!n1.equals(n2)) { toast("Hai lần nhập không khớp"); return; }
                    prefs.setPin(n1);
                    toast("Đã đổi mã PIN");
                });
            });
        });
    }

    private void unlock() {
        adminPanel.setVisibility(View.VISIBLE);
        btnAdmin.setText("🔓 Đóng cài đặt nâng cao");
    }

    private void lock() {
        adminPanel.setVisibility(View.GONE);
        btnAdmin.setText("🔒 Cài đặt nâng cao (Admin)");
    }

    // ===================== Lưu / gửi thử =====================

    private void save() {
        int s;
        try { s = Integer.parseInt(secs.getText().toString().trim()); } catch (NumberFormatException e) { s = 3; }
        String pat = skuPattern.getText().toString().trim();
        try { java.util.regex.Pattern.compile(pat.isEmpty() ? "1\\d{7}" : pat); }
        catch (Exception ex) { toast("Mẫu mã hàng sai cú pháp"); return; }
        prefs.save(pkg.getText().toString(), kw.getText().toString(), s, debug.isChecked(),
                false, scanLog.isChecked(), "");
        prefs.saveSheet(sheetUrl.getText().toString(), sheetToken.getText().toString(),
                deviceName.getText().toString());
        prefs.saveGr(grUrl.getText().toString(), grKey.getText().toString());
        prefs.saveCross(crossCheck.isChecked(), pat);
        updateStatus();
        Toast.makeText(this, "Đã lưu", Toast.LENGTH_SHORT).show();
    }

    private void sheetTest() {
        prefs.saveSheet(sheetUrl.getText().toString(), sheetToken.getText().toString(),
                deviceName.getText().toString());
        SheetUploader up = SheetUploader.get(this);
        if (!up.configured()) { toast("Chưa nhập link Web App"); return; }
        java.util.Date d = new java.util.Date();
        up.enqueue(ScanLog.today(), new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(d),
                "TEST", "TEST-123", "", "Dòng thử từ ScanAlert", new String[]{"", "", "", "", "", ""});
        sheetStatus.setText("Đang gửi…");
        sheetStatus.postDelayed(() -> sheetStatus.setText(up.status()), 6000);
    }

    private void shareTodayCsv() {
        String u = prefs.csvUri(ScanLog.today());
        if (u == null) {
            toast("Hôm nay chưa có lần quét nào được ghi");
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(u))
                .putExtra(Intent.EXTRA_SUBJECT, ScanLog.fileName(ScanLog.today()))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Gửi file log quét"));
    }

    private void updateStatus() {
        boolean on = ErrorWatcherService.running;
        status.setText(on ? "✅ Đang hoạt động – sẵn sàng cảnh báo"
                          : "❌ Chưa bật – bấm nút bên dưới rồi bật \"ScanAlert\"");
        status.setTextColor(on ? 0xFF2E7D32 : 0xFFC62828);
        boolean cross = prefs.crossCheck();
        tvCross.setText((cross ? "✅ Đối soát GR: BẬT" : "⚪ Đối soát GR: TẮT")
                + (prefs.sheetUrl().isEmpty() ? "   ·   ⚪ Chưa nối Google Sheet" : "   ·   ✅ Đã nối Google Sheet"));
    }

    @Override protected void onResume() {
        super.onResume();
        updateStatus();
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
        lock();                      // rời màn hình là khóa lại khu vực Admin
    }
}
