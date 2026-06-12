package com.edgedetection.ui.battle.components;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

public class BattleSoundManager {
    private static final String TAG = "BattleSoundManager";

    private static final int SAMPLE_RATE = 44100;

    // Proximity thresholds
    private static final float DIST_FAR  = 1000f; // м — тихо/редко
    private static final float DIST_NEAR =   30f; // м — часто/высоко

    // Beep tone range (Hz)
    private static final float FREQ_FAR  =  400f;
    private static final float FREQ_NEAR = 1600f;

    // Beep interval range (ms)
    private static final long INTERVAL_FAR  = 2000L;
    private static final long INTERVAL_NEAR =   80L;

    // Beep duration (ms) — stays short
    private static final int BEEP_DURATION_MS = 60;

    // Hit sound params
    private static final float HIT_FREQ_START = 2000f;
    private static final float HIT_FREQ_END   =  300f;
    private static final int   HIT_DURATION_MS = 250;

    private final HandlerThread thread;
    private final Handler handler;

    private volatile boolean enabled = false;
    private volatile float currentDistance = -1f; // -1 = нет дрона
    private volatile boolean dronePresent = false;

    private final Runnable beepRunnable = new Runnable() {
        @Override
        public void run() {
            if (!enabled || !dronePresent || currentDistance < 0) return;
            float freq = distToFreq(currentDistance);
            long interval = distToInterval(currentDistance);
            playTone(freq, BEEP_DURATION_MS, 0.7f);
            handler.postDelayed(this, interval);
        }
    };

    public BattleSoundManager() {
        thread = new HandlerThread("BattleSoundThread");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public void setEnabled(boolean enabled) {
        boolean wasEnabled = this.enabled;
        this.enabled = enabled;
        if (!enabled) {
            handler.removeCallbacks(beepRunnable);
        } else if (!wasEnabled && dronePresent) {
            scheduleBeep();
        }
    }

    public void updateDistance(float distanceMeters) {
        boolean wasPresent = dronePresent;
        dronePresent = distanceMeters > 0;
        currentDistance = dronePresent ? distanceMeters : -1f;
        if (enabled && dronePresent && !wasPresent) {
            scheduleBeep();
        } else if (!dronePresent) {
            handler.removeCallbacks(beepRunnable);
        }
    }

    public void onDroneLost() {
        dronePresent = false;
        currentDistance = -1f;
        handler.removeCallbacks(beepRunnable);
    }

    public void onHit() {
        if (!enabled) return;
        handler.removeCallbacks(beepRunnable);
        handler.post(() -> playHitSound());
        handler.postDelayed(() -> {
            if (dronePresent) scheduleBeep();
        }, HIT_DURATION_MS + 100L);
    }

    public void release() {
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    private void scheduleBeep() {
        handler.removeCallbacks(beepRunnable);
        handler.post(beepRunnable);
    }

    private float distToFreq(float dist) {
        float t = 1f - Math.min(1f, Math.max(0f, (dist - DIST_NEAR) / (DIST_FAR - DIST_NEAR)));
        return FREQ_FAR + t * (FREQ_NEAR - FREQ_FAR);
    }

    private long distToInterval(float dist) {
        float t = 1f - Math.min(1f, Math.max(0f, (dist - DIST_NEAR) / (DIST_FAR - DIST_NEAR)));
        return (long) (INTERVAL_FAR - t * (INTERVAL_FAR - INTERVAL_NEAR));
    }

    private void playTone(float freqHz, int durationMs, float amplitude) {
        int numSamples = (int) (SAMPLE_RATE * durationMs / 1000.0);
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(numSamples * 2, minBuf);

        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();

            short[] samples = generateTone(freqHz, numSamples, amplitude, true);
            track.write(samples, 0, samples.length);
            track.play();

            long sleepMs = durationMs + 5L;
            try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) {}
        } catch (Exception e) {
            Log.w(TAG, "playTone error: " + e.getMessage());
        } finally {
            if (track != null) {
                try { track.stop(); track.release(); } catch (Exception ignored) {}
            }
        }
    }

    private void playHitSound() {
        int durationMs = HIT_DURATION_MS;
        int numSamples = (int) (SAMPLE_RATE * durationMs / 1000.0);
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(numSamples * 2, minBuf);

        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();

            short[] samples = generateSweep(HIT_FREQ_START, HIT_FREQ_END, numSamples, 0.9f);
            track.write(samples, 0, samples.length);
            track.play();

            try { Thread.sleep(durationMs + 10L); } catch (InterruptedException ignored) {}
        } catch (Exception e) {
            Log.w(TAG, "playHitSound error: " + e.getMessage());
        } finally {
            if (track != null) {
                try { track.stop(); track.release(); } catch (Exception ignored) {}
            }
        }
    }

    private short[] generateTone(float freqHz, int numSamples, float amplitude, boolean fade) {
        short[] samples = new short[numSamples];
        double twoPiF = 2.0 * Math.PI * freqHz / SAMPLE_RATE;
        int fadeLen = fade ? Math.min(numSamples / 4, 800) : 0;
        for (int i = 0; i < numSamples; i++) {
            double env = amplitude;
            if (fade) {
                if (i < fadeLen) env *= (double) i / fadeLen;
                else if (i > numSamples - fadeLen) env *= (double) (numSamples - i) / fadeLen;
            }
            samples[i] = (short) (env * Short.MAX_VALUE * Math.sin(twoPiF * i));
        }
        return samples;
    }

    private short[] generateSweep(float freqStart, float freqEnd, int numSamples, float amplitude) {
        short[] samples = new short[numSamples];
        double phase = 0;
        int fadeLen = Math.min(numSamples / 5, 400);
        for (int i = 0; i < numSamples; i++) {
            float t = (float) i / numSamples;
            float freq = freqStart + t * (freqEnd - freqStart);
            double env = amplitude;
            if (i < fadeLen) env *= (double) i / fadeLen;
            else if (i > numSamples - fadeLen) env *= (double) (numSamples - i) / fadeLen;
            phase += 2.0 * Math.PI * freq / SAMPLE_RATE;
            samples[i] = (short) (env * Short.MAX_VALUE * Math.sin(phase));
        }
        return samples;
    }
}
