package com.tools.pdabeeper;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Phát bíp to trên luồng ALARM (không bị ảnh hưởng bởi âm lượng media/chuông), tự trả lại âm lượng cũ. */
final class Alarm {
    private static final long BEEP_ON_MS = 350, BEEP_OFF_MS = 150;

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private ToneGenerator tone;
    private int savedVolume = -1;
    private long endAt;
    private boolean playing;

    Alarm(Context c) { ctx = c.getApplicationContext(); }

    boolean isPlaying() { return playing; }

    void start(int seconds) {
        endAt = System.currentTimeMillis() + seconds * 1000L;
        if (playing) return;           // đang kêu thì chỉ gia hạn
        playing = true;

        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        } catch (Exception ignored) { savedVolume = -1; }

        try { tone = new ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME); }
        catch (RuntimeException e) { tone = null; }

        Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            long[] pattern = {0, 400, 150, 400, 150, 400};
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        }
        h.post(loop);
    }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!playing) return;
            if (System.currentTimeMillis() >= endAt) { stop(); return; }
            if (tone != null) tone.startTone(ToneGenerator.TONE_CDMA_HIGH_L, (int) BEEP_ON_MS);
            h.postDelayed(this, BEEP_ON_MS + BEEP_OFF_MS);
        }
    };

    void stop() {
        if (!playing) return;
        playing = false;
        h.removeCallbacks(loop);
        if (tone != null) { tone.stopTone(); tone.release(); tone = null; }
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
