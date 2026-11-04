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
    private EditText pkg, kw, secs;
    private CheckBox debug;
    private TextView status, log;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        testAlarm = new Alarm(this);

        status = findViewById(R.id.status);
        log = findViewById(R.id.log);
        pkg = findViewById(R.id.targetPackage);
        kw = findViewById(R.id.keywords);
        secs = findViewById(R.id.beepSeconds);
        debug = findViewById(R.id.debugLog);

        pkg.setText(prefs.targetPackage());
        kw.setText(prefs.keywordsRaw());
        secs.setText(String.valueOf(prefs.beepSeconds()));
        debug.setChecked(prefs.debugLog());

        ((Button) findViewById(R.id.btnAccessibility)).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        ((Button) findViewById(R.id.btnAppInfo)).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));
        ((Button) findViewById(R.id.btnSave)).setOnClickListener(v -> {
            int s;
            try { s = Integer.parseInt(secs.getText().toString().trim()); } catch (NumberFormatException e) { s = 3; }
            prefs.save(pkg.getText().toString(), kw.getText().toString(), s, debug.isChecked());
            Toast.makeText(this, "Đã lưu", Toast.LENGTH_SHORT).show();
        });
        ((Button) findViewById(R.id.btnTest)).setOnClickListener(v -> testAlarm.start(2));
        Button refresh = findViewById(R.id.btnRefreshLog);
        refresh.setOnClickListener(v -> log.setText(prefs.readLog()));
        refresh.setOnLongClickListener(v -> { prefs.clearLog(); log.setText(""); return true; });
    }

    @Override protected void onResume() {
        super.onResume();
        boolean on = ErrorWatcherService.running;
        status.setText(on ? "✅ Dịch vụ cảnh báo ĐANG CHẠY"
                          : "❌ Dịch vụ CHƯA bật – bấm nút 1 và bật \"PDA Error Beeper\"");
        status.setTextColor(on ? 0xFF1B7F2A : 0xFFC62828);
        log.setText(prefs.readLog());
    }

    @Override protected void onPause() { super.onPause(); testAlarm.stop(); }
}
