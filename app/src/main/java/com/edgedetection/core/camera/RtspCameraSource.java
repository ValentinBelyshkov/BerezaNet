package com.edgedetection.core.camera;

import android.graphics.Bitmap;
import android.view.PixelCopy;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.View;

import androidx.annotation.RequiresApi;

import com.alexvas.rtsp.widget.RtspSurfaceView;
import com.alexvas.rtsp.widget.RtspStatusListener;

import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * RTSP camera source using alexeyvasilyev/rtsp-client-android v5.x.
 *
 * RtspSurfaceView decodes H264/H265 via MediaCodec internally.
 * PixelCopy (API 26+) extracts frames as Bitmaps every ~33 ms.
 *
 * Flow:
 *   RtspSurfaceView → PixelCopy → Bitmap → bitmapToMat() → onFrameMat() → glView
 *
 * URL is set via rtspView.init() before start().
 * RtspSurfaceView must be VISIBLE and have a non-zero size for PixelCopy to work.
 * It is placed behind glView in the layout — invisible to the user.
 */
public class RtspCameraSource implements CameraSource {

    private static final String TAG = "RtspCameraSource";

    // ── Status ─────────────────────────────────────────────────────────────
    public enum Status { CONNECTING, ONLINE, RECONNECTING }

    public interface StatusListener {
        void onStatusChanged(Status status, int attempt);
    }

    // ── Fields ──────────────────────────────────────────────────────────────
    private final RtspSurfaceView rtspView;
    private final String          rtspUrl;
    private final String          host;
    private final Handler         mainHandler;

    private volatile boolean isRunning  = false;
    private volatile boolean firstFrame = false;

    private HandlerThread workerThread;
    private Handler       workerHandler;
    private Bitmap        copyBitmap;

    private CameraSourceListener listener;
    private StatusListener        statusListener;

    // ── Constructor ─────────────────────────────────────────────────────────
    public RtspCameraSource(RtspSurfaceView rtspView, String rtspUrl) {
        this.rtspView    = rtspView;
        this.rtspUrl     = rtspUrl;
        this.host        = Uri.parse(rtspUrl).getHost();
        this.mainHandler = new Handler(rtspView.getContext().getMainLooper());
    }

    public void setStatusListener(StatusListener l) { this.statusListener = l; }

    // ── CameraSource ────────────────────────────────────────────────────────
    @Override
    public void start(CameraSourceListener listener) {
        if (isRunning) return;
        this.listener = listener;
        isRunning     = true;
        firstFrame    = false;
        notifyStatus(Status.CONNECTING);

        workerThread = new HandlerThread("RtspWorker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        workerHandler.post(this::syncCameraClock);

        mainHandler.post(() -> {
            rtspView.setVisibility(View.VISIBLE);

            // v5.x: URL is set via init(), then start(video, audio) is called
            rtspView.init(Uri.parse(rtspUrl), null, null, null, 1000);

            rtspView.setStatusListener(new RtspStatusListener() {
                @Override public void onRtspStatusConnecting()  {
                    Log.d(TAG, "Connecting...");
                }
                @Override public void onRtspStatusConnected()   {
                    Log.i(TAG, "Connected");
                    notifyStatus(Status.ONLINE);
                }
                @Override public void onRtspStatusDisconnecting() {}
                @Override public void onRtspStatusDisconnected() {
                    Log.w(TAG, "Disconnected");
                    if (isRunning) scheduleRestart();
                }
                @Override public void onRtspStatusFailedUnauthorized() {
                    Log.e(TAG, "Unauthorized");
                }
                @Override public void onRtspStatusFailed(String message) {
                    Log.e(TAG, "Failed: " + message);
                    if (isRunning) scheduleRestart();
                }
                @Override public void onRtspFirstFrameRendered() {
                    Log.i(TAG, "First frame — starting capture loop");
                    firstFrame = true;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        mainHandler.post(captureTask);
                    } else {
                        Log.w(TAG, "PixelCopy requires API 26+");
                    }
                }
            });

            rtspView.start(true, false,false); // requestVideo=true, requestAudio=false
        });
    }

    @Override
    public void stop() {
        if (!isRunning) return;
        isRunning  = false;
        firstFrame = false;
        listener   = null;

        mainHandler.removeCallbacks(captureTask);
        mainHandler.post(() -> {
            rtspView.stop();
            rtspView.setVisibility(View.GONE);
        });

        if (workerHandler != null) workerHandler.removeCallbacksAndMessages(null);
        if (workerThread  != null) workerThread.quitSafely();
        workerThread  = null;
        workerHandler = null;
        copyBitmap    = null;
    }

    @Override
    public boolean isRunning() { return isRunning; }

    // ── Reconnect ────────────────────────────────────────────────────────────
    private void scheduleRestart() {
        notifyStatus(Status.RECONNECTING);
        mainHandler.postDelayed(() -> {
            if (isRunning) {
                rtspView.stop();
                rtspView.start(true, false,false);
            }
        }, 3000);
    }

    // ── PixelCopy capture loop ───────────────────────────────────────────────
    // Runs on the main thread. One frame in-flight at a time: the next capture
    // is scheduled from the PixelCopy callback (on workerHandler).

    private final Runnable captureTask = new Runnable() {
        @RequiresApi(Build.VERSION_CODES.O)
        @Override public void run() {
            if (!isRunning || !firstFrame) return;

            int w = rtspView.getWidth();
            int h = rtspView.getHeight();
            if (w <= 0 || h <= 0) {
                mainHandler.postDelayed(this, 33);
                return;
            }

            if (copyBitmap == null
                    || copyBitmap.getWidth()  != w
                    || copyBitmap.getHeight() != h) {
                copyBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }

            final Bitmap  bm   = copyBitmap;
            final long    tsNs = System.nanoTime();
            final Handler wh   = workerHandler;
            if (wh == null) return;

            PixelCopy.request(rtspView, bm, result -> {
                if (result == PixelCopy.SUCCESS && isRunning) {
                    Mat mat = new Mat();
                    Utils.bitmapToMat(bm, mat);
                    if (!mat.empty()) {
                        final CameraSourceListener l = listener;
                        if (l != null) l.onFrameMat(mat, tsNs);
                    }
                    mat.release();
                }
                if (isRunning) mainHandler.postDelayed(captureTask, 33);
            }, wh);
        }
    };

    // ── Helpers ──────────────────────────────────────────────────────────────
    private void syncCameraClock() {
        if (host == null || host.isEmpty()) return;
        try {
            double ts  = System.currentTimeMillis() / 1000.0;
            URL    url = new URL("http://" + host + "/api/v1/misc/time");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(("{\"value\":\"" + (long) ts + "\"}").getBytes());
            }
            Log.i(TAG, "Clock sync → " + (long) ts + " HTTP " + conn.getResponseCode());
            conn.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "Clock sync skipped: " + e.getMessage());
        }
    }

    private void notifyStatus(Status s) {
        final StatusListener sl = statusListener;
        if (sl != null) mainHandler.post(() -> sl.onStatusChanged(s, 0));
    }
}
