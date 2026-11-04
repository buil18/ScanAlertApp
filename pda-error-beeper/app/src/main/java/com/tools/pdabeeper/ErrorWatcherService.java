package com.tools.pdabeeper;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Dịch vụ Trợ năng: theo dõi cửa sổ của ứng dụng Heineken PDA.
 * Khi xuất hiện chữ chứa từ khóa lỗi (hộp thoại hoặc Toast) -> phát bíp.
 * Chỉ báo 1 lần cho mỗi lần hộp thoại hiện ra; hộp thoại đóng thì dừng bíp.
 */
public class ErrorWatcherService extends AccessibilityService {
    private static final long SCAN_DEBOUNCE_MS = 250;
    private static final int MAX_NODES = 400;
    private static final String SYSTEM_UI = "com.android.systemui";

    static volatile boolean running;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private Alarm alarm;
    private boolean errorVisible;
    private String lastDebugSnapshot = "";

    private final Runnable scan = this::scanWindows;

    @Override protected void onServiceConnected() {
        running = true;
        prefs = new Prefs(this);
        alarm = new Alarm(this);
        prefs.log("Dịch vụ đã BẬT, theo dõi: " + prefs.targetPackage());
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (prefs == null || e == null) return;
        String target = prefs.targetPackage();
        CharSequence pkgCs = e.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();

        // 1) Toast: sự kiện NOTIFICATION_STATE_CHANGED không kèm Notification
        if (e.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (!(e.getParcelableData() instanceof Notification)
                    && (pkg.equals(target) || pkg.equals(SYSTEM_UI))) {
                String text = joinText(e.getText());
                debug("TOAST[" + pkg + "] " + text);
                if (matches(text)) fire("Toast: " + text);
            }
            return;
        }

        // 2) Hộp thoại / màn hình: gom quét lại để tránh xử lý quá nhiều sự kiện
        if (pkg.equals(target) || e.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            h.removeCallbacks(scan);
            h.postDelayed(scan, SCAN_DEBOUNCE_MS);
        }
    }

    private void scanWindows() {
        String target = prefs.targetPackage();
        StringBuilder sb = new StringBuilder();
        boolean targetOnScreen = false;

        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null && !windows.isEmpty()) {
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                if (root.getPackageName() != null && target.contentEquals(root.getPackageName())) {
                    targetOnScreen = true;
                    collect(root, sb, new int[]{0});
                }
            }
        } else {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null
                    && target.contentEquals(root.getPackageName())) {
                targetOnScreen = true;
                collect(root, sb, new int[]{0});
            }
        }

        String text = sb.toString();
        if (targetOnScreen) debug("SCREEN " + text);

        boolean nowError = targetOnScreen && matches(text);
        if (nowError && !errorVisible) {
            fire("Hộp thoại: " + text);
        } else if (!nowError && errorVisible && alarm.isPlaying()) {
            alarm.stop();                       // người dùng đã bấm OK -> im luôn
        }
        errorVisible = nowError;
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int[] count) {
        if (n == null || count[0]++ > MAX_NODES) return;
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) sb.append(t).append(" | ");
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0) sb.append(d).append(" | ");
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), sb, count);
    }

    private boolean matches(String raw) {
        String norm = Prefs.normalize(raw);
        if (norm.isEmpty()) return false;
        for (String k : prefs.keywords()) if (norm.contains(k)) return true;
        return false;
    }

    private void fire(String reason) {
        prefs.log("BÍP ← " + shorten(reason));
        alarm.start(prefs.beepSeconds());
    }

    private void debug(String snapshot) {
        if (!prefs.debugLog() || snapshot.equals(lastDebugSnapshot)) return;
        lastDebugSnapshot = snapshot;
        prefs.log("[dò] " + shorten(snapshot));
    }

    private static String joinText(List<CharSequence> parts) {
        StringBuilder sb = new StringBuilder();
        if (parts != null) for (CharSequence p : parts) sb.append(p).append(' ');
        return sb.toString().trim();
    }

    private static String shorten(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    @Override public void onInterrupt() { if (alarm != null) alarm.stop(); }

    @Override public void onDestroy() {
        running = false;
        if (alarm != null) alarm.stop();
        if (prefs != null) prefs.log("Dịch vụ đã TẮT");
        super.onDestroy();
    }
}
