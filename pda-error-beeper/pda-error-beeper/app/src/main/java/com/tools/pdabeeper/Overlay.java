package com.tools.pdabeeper;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Dải thông báo nằm đè lên đầu màn hình Heineken PDA (cửa sổ Trợ năng, không cần quyền vẽ đè).
 * Dòng đầu chữ to đậm, các dòng sau chữ thường. Chạm vào dải để ẩn (và tắt còi nếu đang kêu).
 */
final class Overlay {
    enum Level { OK, WARN, ERROR }

    private static final int RED = 0xF2C62828, AMBER = 0xF2E65100, GREEN = 0xF22E7D32;

    private final AccessibilityService svc;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView view;
    private Level current = Level.OK;
    private Runnable onTap;
    private final Runnable hide = this::hide;

    Overlay(AccessibilityService s) { svc = s; }

    /** Việc cần làm khi người dùng chạm vào dải ĐỎ/CAM (ví dụ tắt còi). */
    void setOnTap(Runnable r) { onTap = r; }

    void show(Level level, String text) { show(level, text, -1); }

    /** autoHideMs: >0 tự ẩn sau chừng đó mili giây; <=0 dùng mặc định (OK 2,5s; CAM 8s; ĐỎ giữ tới khi chạm). */
    void show(Level level, String text, long autoHideMs) {
        h.removeCallbacks(hide);
        current = level;
        WindowManager wm = (WindowManager) svc.getSystemService(AccessibilityService.WINDOW_SERVICE);
        if (view == null) {
            view = new TextView(svc);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            view.setTextColor(0xFFFFFFFF);
            int pad = (int) (16 * svc.getResources().getDisplayMetrics().density);
            view.setPadding(pad, pad, pad, pad);
            view.setOnClickListener(v -> {
                if (current != Level.OK && onTap != null) onTap.run();
                hide();
            });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            try { wm.addView(view, lp); } catch (Exception e) { view = null; return; }
        }
        view.setBackgroundColor(level == Level.ERROR ? RED : level == Level.WARN ? AMBER : GREEN);

        String full = text + (level == Level.ERROR ? "\n(chạm để tắt còi và ẩn)" : "");
        SpannableString sp = new SpannableString(full);
        int nl = full.indexOf('\n');
        int end = nl < 0 ? full.length() : nl;
        sp.setSpan(new RelativeSizeSpan(1.35f), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(sp);

        long ms = autoHideMs > 0 ? autoHideMs : level == Level.OK ? 2500 : level == Level.WARN ? 8000 : 0;
        if (ms > 0) h.postDelayed(hide, ms);
        // ĐỎ mặc định: giữ tới khi chạm hoặc khi pallet sai được xóa
    }

    void hide() {
        h.removeCallbacks(hide);
        if (view == null) return;
        try { ((WindowManager) svc.getSystemService(AccessibilityService.WINDOW_SERVICE)).removeView(view); }
        catch (Exception ignored) { }
        view = null;
    }
}
