package com.tools.pdabeeper;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Phát bíp to trên luồng ALARM (không bị ảnh hưởng bởi âm lượng media/chuông), tự trả lại âm lượng cũ.
 * Ba kiểu cảnh báo:
 *   SAI      = ĐỎ: còi 2 tông + rung dài (sai mã hàng / thừa số lượng)
 *   LOI      = ĐỎ: bíp ngắt quãng + rung (pallet không tồn tại, lỗi từ PDA)
 *   CANH_BAO = CAM: 2 tiếng bíp ngắn + rung nhẹ (không kiểm tra được, cần xem lại)
 */
final class Alarm {
    /** Thứ tự mức độ: kiểu thấp hơn không được ghi đè kiểu đang kêu cao hơn. */
    enum Kind { CANH_BAO, LOI, SAI }

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private ToneGenerator tone;
    private int savedVolume = -1;
    private long endAt;
    private boolean playing;
    private Kind kind = Kind.LOI;

    Alarm(Context c) { ctx = c.getApplicationContext(); }

    boolean isPlaying() { return playing; }
    Kind kind() { return kind; }

    void start(int seconds) { start(seconds, Kind.LOI); }

    void start(int seconds, Kind k) {
        long dur = k == Kind.CANH_BAO ? 650 : seconds * 1000L;
        long newEnd = System.currentTimeMillis() + dur;
        if (playing) {
            if (k.ordinal() < kind.ordinal()) return;            // đang kêu mức cao hơn thì giữ nguyên
            boolean upgraded = k.ordinal() > kind.ordinal();
            kind = k;
            endAt = Math.max(endAt, newEnd);
            if (upgraded) vibrate(k);
            return;
        }
        kind = k;
        endAt = newEnd;
        playing = true;

        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        } catch (Exception ignored) { savedVolume = -1; }

        try { tone = new ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME); }
        catch (RuntimeException e) { tone = null; }

        vibrate(k);
        h.post(loop);
    }

    private void vibrate(Kind k) {
        Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        long[] pattern = k == Kind.SAI ? new long[]{0, 1200, 200, 1200}
                : k == Kind.LOI ? new long[]{0, 400, 150, 400, 150, 400}
                : new long[]{0, 150, 100, 150};
        v.vibrate(VibrationEffect.createWaveform(pattern, -1));
    }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!playing) return;
            if (System.currentTimeMillis() >= endAt) { stop(); return; }
            boolean amber = kind == Kind.CANH_BAO;
            int on = amber ? 200 : 350, off = amber ? 120 : 150;
            int type = kind == Kind.SAI ? ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK
                    : amber ? ToneGenerator.TONE_PROP_BEEP
                    : ToneGenerator.TONE_CDMA_HIGH_L;
            if (tone != null) tone.startTone(type, on);
            h.postDelayed(this, on + off);
        }
    };

    void stop() {
        if (!playing) return;
        playing = false;
        h.removeCallbacks(loop);
        if (tone != null) { tone.stopTone(); tone.release(); tone = null; }
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.cancel();
        } catch (Exception ignored) { }
        if (savedVolume >= 0) {
            final int vol = savedVolume;
            // trả âm lượng sau khi tiếng bíp cuối kết thúc
            h.postDelayed(() -> {
                try {
                    ((AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE))
                            .setStreamVolume(AudioManager.STREAM_ALARM, vol, 0);
                } catch (Exception ignored) { }
            }, 400);
            savedVolume = -1;
        }
    }
}
