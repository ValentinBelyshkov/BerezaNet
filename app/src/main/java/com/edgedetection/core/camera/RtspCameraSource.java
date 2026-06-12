package com.edgedetection.core.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.TextureView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

public class RtspCameraSource implements CameraSource {
    private static final String TAG = "RtspCameraSource";

    private static final long FRAME_INTERVAL_MS = 33;
    private static final long RECONNECT_DELAY_BASE_MS = 2_000;
    private static final long RECONNECT_DELAY_MAX_MS = 30_000;
    private static final long WATCHDOG_TIMEOUT_MS = 5_000;

    public enum Status {
        CONNECTING,
        ONLINE,
        RECONNECTING
    }

    public interface StatusListener {
        void onStatusChanged(Status status, int attempt);
    }

    private final Context context;
    private final TextureView textureView;
    private final String rtspUrl;
    private final Handler mainHandler;

    private ExoPlayer player;
    private CameraSourceListener listener;
    private StatusListener statusListener;
    private volatile boolean isRunning = false;
    private volatile boolean isConnected = false;

    private int reconnectAttempt = 0;
    private volatile long lastFrameTimeMs = 0;

    private HandlerThread frameThread;
    private Handler frameHandler;

    private final Runnable frameCaptureRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning || listener == null) return;
            try {
                Bitmap bitmap = textureView.getBitmap();
                if (bitmap != null && !bitmap.isRecycled()) {
                    Mat rgba = bitmapToMat(bitmap);
                    bitmap.recycle();
                    if (rgba != null) {
                        lastFrameTimeMs = System.currentTimeMillis();
                        listener.onFrameMat(rgba, System.nanoTime());
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Frame capture error: " + e.getMessage());
            }
            if (isRunning) {
                frameHandler.postDelayed(this, FRAME_INTERVAL_MS);
            }
        }
    };

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            if (isConnected && lastFrameTimeMs > 0) {
                long sinceLastFrame = System.currentTimeMillis() - lastFrameTimeMs;
                if (sinceLastFrame > WATCHDOG_TIMEOUT_MS) {
                    Log.w(TAG, "Watchdog: no frames for " + sinceLastFrame + "ms — reconnecting");
                    scheduleReconnect();
                    return;
                }
            }
            frameHandler.postDelayed(this, WATCHDOG_TIMEOUT_MS);
        }
    };

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            Log.i(TAG, "Reconnecting (attempt " + (reconnectAttempt + 1) + ")...");
            mainHandler.post(() -> {
                releasePlayer();
                if (isRunning) {
                    createPlayer();
                }
            });
        }
    };

    public RtspCameraSource(Context context, TextureView textureView, String rtspUrl) {
        this.context = context;
        this.textureView = textureView;
        this.rtspUrl = rtspUrl;
        this.mainHandler = new Handler(context.getMainLooper());
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    private void notifyStatus(Status status) {
        if (statusListener != null) {
            mainHandler.post(() -> statusListener.onStatusChanged(status, reconnectAttempt));
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void start(CameraSourceListener listener) {
        this.listener = listener;
        isRunning = true;
        reconnectAttempt = 0;
        lastFrameTimeMs = 0;

        frameThread = new HandlerThread("RtspFrameCapture");
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());

        notifyStatus(Status.CONNECTING);
        mainHandler.post(this::createPlayer);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void createPlayer() {
        if (!isRunning) return;
        try {
            player = new ExoPlayer.Builder(context).build();
            player.setVideoTextureView(textureView);

            RtspMediaSource mediaSource = new RtspMediaSource.Factory()
                    .createMediaSource(MediaItem.fromUri(rtspUrl));

            player.setMediaSource(mediaSource);
            player.prepare();
            player.setPlayWhenReady(true);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        Log.i(TAG, "RTSP stream ready");
                        isConnected = true;
                        reconnectAttempt = 0;
                        lastFrameTimeMs = System.currentTimeMillis();
                        notifyStatus(Status.ONLINE);
                        frameHandler.removeCallbacks(frameCaptureRunnable);
                        frameHandler.post(frameCaptureRunnable);
                        frameHandler.removeCallbacks(watchdogRunnable);
                        frameHandler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS);
                    } else if (state == Player.STATE_ENDED) {
                        Log.w(TAG, "RTSP stream ended — reconnecting");
                        isConnected = false;
                        scheduleReconnect();
                    } else if (state == Player.STATE_IDLE) {
                        if (isConnected) {
                            Log.w(TAG, "RTSP player went idle — reconnecting");
                            isConnected = false;
                            scheduleReconnect();
                        }
                    }
                }

                @Override
                public void onPlayerError(androidx.media3.common.PlaybackException error) {
                    Log.e(TAG, "RTSP error: " + error.getMessage());
                    isConnected = false;
                    scheduleReconnect();
                }
            });

            Log.i(TAG, "RTSP connecting to: " + rtspUrl);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create player: " + e.getMessage(), e);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!isRunning) return;
        frameHandler.removeCallbacks(frameCaptureRunnable);
        frameHandler.removeCallbacks(watchdogRunnable);
        frameHandler.removeCallbacks(reconnectRunnable);

        long delay = Math.min(RECONNECT_DELAY_BASE_MS * (1L << reconnectAttempt), RECONNECT_DELAY_MAX_MS);
        reconnectAttempt++;
        Log.i(TAG, "Scheduling reconnect in " + delay + "ms (attempt " + reconnectAttempt + ")");
        notifyStatus(Status.RECONNECTING);
        frameHandler.postDelayed(reconnectRunnable, delay);
    }

    private void releasePlayer() {
        if (player != null) {
            player.removeListener(player.getApplicationLooper() != null ? new Player.Listener() {} : new Player.Listener() {});
            player.stop();
            player.release();
            player = null;
        }
    }

    @Override
    public void stop() {
        isRunning = false;
        isConnected = false;
        listener = null;

        if (frameHandler != null) {
            frameHandler.removeCallbacks(frameCaptureRunnable);
            frameHandler.removeCallbacks(watchdogRunnable);
            frameHandler.removeCallbacks(reconnectRunnable);
        }
        if (frameThread != null) {
            frameThread.quitSafely();
            frameThread = null;
        }

        mainHandler.post(this::releasePlayer);
        Log.i(TAG, "RTSP camera source stopped");
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    private Mat bitmapToMat(Bitmap bitmap) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            Mat mat = new Mat(height, width, CvType.CV_8UC4);

            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

            byte[] bytes = new byte[width * height * 4];
            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                bytes[i * 4]     = (byte) ((pixel >> 16) & 0xFF);
                bytes[i * 4 + 1] = (byte) ((pixel >> 8) & 0xFF);
                bytes[i * 4 + 2] = (byte) (pixel & 0xFF);
                bytes[i * 4 + 3] = (byte) ((pixel >> 24) & 0xFF);
            }
            mat.put(0, 0, bytes);
            return mat;
        } catch (Exception e) {
            Log.e(TAG, "bitmapToMat error: " + e.getMessage());
            return null;
        }
    }
}
