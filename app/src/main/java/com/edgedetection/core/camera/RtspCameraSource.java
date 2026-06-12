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

    private final Context context;
    private final TextureView textureView;
    private final String rtspUrl;

    private ExoPlayer player;
    private CameraSourceListener listener;
    private volatile boolean isRunning = false;

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

    public RtspCameraSource(Context context, TextureView textureView, String rtspUrl) {
        this.context = context;
        this.textureView = textureView;
        this.rtspUrl = rtspUrl;
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void start(CameraSourceListener listener) {
        this.listener = listener;
        isRunning = true;

        frameThread = new HandlerThread("RtspFrameCapture");
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());

        new Handler(context.getMainLooper()).post(() -> {
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
                            Log.i(TAG, "RTSP stream ready, starting frame capture");
                            frameHandler.post(frameCaptureRunnable);
                        } else if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                            Log.w(TAG, "RTSP player state: " + state);
                        }
                    }

                    @Override
                    public void onPlayerError(androidx.media3.common.PlaybackException error) {
                        Log.e(TAG, "RTSP player error: " + error.getMessage());
                    }
                });

                Log.i(TAG, "RTSP stream connecting to: " + rtspUrl);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start RTSP player: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public void stop() {
        isRunning = false;
        listener = null;

        if (frameHandler != null) {
            frameHandler.removeCallbacks(frameCaptureRunnable);
        }
        if (frameThread != null) {
            frameThread.quitSafely();
            frameThread = null;
        }

        new Handler(context.getMainLooper()).post(() -> {
            if (player != null) {
                player.stop();
                player.release();
                player = null;
            }
        });
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
