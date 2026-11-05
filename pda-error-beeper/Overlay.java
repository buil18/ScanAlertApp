package com.tools.pdabeeper;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/** Dải thông báo nằm đè lên đầu màn hình Heineken PDA (cửa sổ Trợ năng, không cần quyền vẽ đè). Chạm để ẩn. */
final class Overlay {
    enum Level { OK, WARN, ERROR }

    private final AccessibilityService svc;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView view;
    private final Runnable hide = this::hide;

    Overlay(AccessibilityService s) { svc = s; }

    void show(Level level, String text) {
        h.removeCallbacks(hide);
        WindowManager wm = (WindowManager) svc.getSystemService(AccessibilityService.WINDOW_SERVICE);
        if (view == null) {
            view = new TextView(svc);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            view.setTextColor(0xFFFFFFFF);
            int pad = (int) (12 * svc.getResources().getDisplayMetrics().density);
            view.setPadding(pad, pad, pad, pad);
            view.setOnClickListener(v -> hide());
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            try { wm.addView(view, lp); } catch (Exception e) { view = null; return; }
        }
        view.setBackgroundColor(level == Level.ERROR ? 0xF0C62828 : level == Level.WARN ? 0xF0E65100 : 0xF02E7D32);
        view.setText(text + (level == Level.ERROR ? "\n(chạm để ẩn)" : ""));
        if (level == Level.OK) h.postDelayed(hide, 2500);
        else if (level == Level.WARN) h.postDelayed(hide, 6000);
        // ERROR: giữ tới khi chạm hoặc khi pallet sai được xóa
    }

    void hide() {
        h.removeCallbacks(hide);
        if (view == null) return;
        try { ((WindowManager) svc.getSystemService(AccessibilityService.WINDOW_SERVICE)).removeView(view); }
        catch (Exception ignored) { }
        view = null;
    }
}
