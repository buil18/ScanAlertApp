package com.tools.pdabeeper;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Màn hình Giả lập: chạy các kịch bản mẫu qua đúng bộ xử lý của ScanAlert và hiện Đạt / Không đạt. */
public class SimActivity extends Activity {
    private LinearLayout list;
    private TextView summary, hint;
    private Button run;
    private CheckBox sound;
    private Simulator sim;
    private boolean running;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Giả lập kiểm thử");
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        sv.addView(root);

        TextView t = new TextView(this);
        t.setText("Giả lập kiểm thử (v" + BuildConfigVersion.NAME + ")");
        t.setTextSize(20); t.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(t);

        hint = new TextView(this);
        hint.setTextSize(13);
        hint.setText("App tự \"bày\" màn hình PDA giả với đơn 9900xxxx (dữ liệu giả, không gọi mạng, không ghi Google Sheet) "
                + "rồi kiểm tra ScanAlert phản ứng đúng không. Cần BẬT dịch vụ Trợ năng của ScanAlert.");
        hint.setPadding(0, dp(6), 0, dp(10));
        root.addView(hint);

        sound = new CheckBox(this);
        sound.setText("Phát âm thanh thật khi bíp");
        root.addView(sound);

        run = new Button(this);
        run.setText("▶ Chạy tất cả kịch bản");
        run.setAllCaps(false);
        run.setOnClickListener(v -> start());
        root.addView(run);

        summary = new TextView(this);
        summary.setTextSize(17); summary.setTypeface(Typeface.DEFAULT_BOLD);
        summary.setPadding(0, dp(12), 0, dp(8));
        root.addView(summary);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        for (Simulator.Scenario s : Simulator.scenarios()) addRow(s, null, null, false);
        setContentView(sv);
    }

    private void addRow(Simulator.Scenario s, String why, List<String> ev, boolean done) {
        TextView tv = new TextView(this);
        tv.setTextSize(14);
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        String status = !done ? "⏳ chưa chạy" : why == null ? "✅ ĐẠT" : "❌ KHÔNG ĐẠT – " + why;
        String txt = s.name + "\nMong đợi: " + s.expect + "\n" + status;
        if (done && why != null && ev != null) txt += "\nSự kiện: " + ev;
        tv.setText(txt);
        tv.setBackgroundColor(!done ? 0xFFEEEEEE : why == null ? 0xFFDFF3E0 : 0xFFFBE0E0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        tv.setLayoutParams(lp);
        list.addView(tv);
    }

    private void start() {
        if (running) return;
        ErrorWatcherService svc = ErrorWatcherService.instance;
        if (svc == null || !svc.simReady()) {
            summary.setText("⚠ Dịch vụ Trợ năng chưa bật.\nVào Cài đặt → Trợ năng → ScanAlert → Bật, rồi quay lại.");
            return;
        }
        String warn = "";
        if (!svc.simCrossCheck()) warn += "⚠ Đang TẮT \"đối chiếu GR\" trong cài đặt – nhiều kịch bản sẽ không đạt.\n";
        if (!svc.simKeywordOk()) warn += "⚠ Từ khóa lỗi chưa có \"khong ton tai\" – kịch bản 4 sẽ không đạt.\n";
        list.removeAllViews();
        summary.setText(warn + "Đang chạy…");
        running = true; run.setEnabled(false);
        sim = new Simulator();
        final String warnF = warn;
        sim.run(svc, sound.isChecked(), new Simulator.Listener() {
            @Override public void onResult(int index, Simulator.Scenario s, String why, List<String> ev) {
                addRow(s, why, ev, true);
            }
            @Override public void onAll(int pass, int total) {
                running = false; run.setEnabled(true);
                String res = "KẾT QUẢ: " + pass + "/" + total + " đạt" + (pass == total ? " ✅" : " ❌");
                summary.setText(warnF + res);
                Prefs p = new Prefs(SimActivity.this);
                p.log("[GIẢ LẬP] v" + BuildConfigVersion.NAME + " – " + res);
            }
        });
    }

    @Override protected void onDestroy() {
        if (sim != null) sim.cancel();
        super.onDestroy();
    }
}
