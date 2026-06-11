package com.edgedetection.ui.battle.components;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.opencv.core.Mat;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class BattleFrameLogger {
    private static final String TAG = "BattleFrameLogger";
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;

    private final Context appContext;
    private final BattleSensorProvider sensorProvider;

    private ExecutorService executor;
    private File sessionDir;
    private File framesDir;
    private File imuDir;
    private BufferedWriter frameWriter;
    private BufferedWriter imuWriter;
    private BufferedWriter stateWriter;
    private BufferedWriter metadataWriter;
    private long startNs;
    private int frameIndex;
    private volatile boolean recording;

    public BattleFrameLogger(Context context, BattleSensorProvider sensorProvider) {
        this.appContext = context.getApplicationContext();
        this.sensorProvider = sensorProvider;
    }

    public synchronized boolean start() {
        if (recording) {
            return true;
        }

        try {
            File externalRoot = appContext.getExternalFilesDir(null);
            File root = new File(externalRoot != null ? externalRoot : appContext.getFilesDir(), "bereza");
            if (!root.exists() && !root.mkdirs()) {
                Log.e(TAG, "Failed to create bereza root");
                return false;
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
            sessionDir = new File(root, timestamp);
            if (!sessionDir.mkdirs()) {
                Log.e(TAG, "Failed to create session dir: " + sessionDir.getAbsolutePath());
                return false;
            }

            framesDir = new File(sessionDir, "frames");
            imuDir = new File(sessionDir, "imu");
            if (!framesDir.mkdirs() || !imuDir.mkdirs()) {
                Log.e(TAG, "Failed to create frame or imu subdirs");
                return false;
            }

frameWriter = new BufferedWriter(new FileWriter(new File(sessionDir, "frames.jsonl")));
             imuWriter = new BufferedWriter(new FileWriter(new File(imuDir, "imu.jsonl")));
            stateWriter = new BufferedWriter(new FileWriter(new File(sessionDir, "state.jsonl")));
            metadataWriter = new BufferedWriter(new FileWriter(new File(sessionDir, "manifest.json")));

            startNs = System.nanoTime();
            frameIndex = 0;
            sensorProvider.clearImuSamples();
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "BattleFrameLogger");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
            recording = true;

            JSONObject manifest = new JSONObject();
            manifest.put("sessionDir", sessionDir.getAbsolutePath());
            manifest.put("startedAtUnixMs", System.currentTimeMillis());
            manifest.put("startedAtMonotonicNs", startNs);
            manifest.put("framesDir", "frames");
            manifest.put("imuDir", "imu");
            manifest.put("timestampBasis", "IMU sensor timestamps and frame timestamps are monotonic nanoseconds; wallClockTimestampNs is System.nanoTime()");
            metadataWriter.write(manifest.toString(2));
            metadataWriter.newLine();
            metadataWriter.flush();

            Log.i(TAG, "Frame logging started: " + sessionDir.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start frame logging", e);
            closeQuietly();
            return false;
        }
    }

    public synchronized void stop() {
        if (!recording) {
            return;
        }
        recording = false;

        ExecutorService service = executor;
        if (service != null) {
            service.shutdown();
            try {
                if (!service.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    service.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                service.shutdownNow();
            }
            executor = null;
        }

        closeQuietly();
        if (sessionDir != null) {
            Log.i(TAG, "Frame logging stopped: " + sessionDir.getAbsolutePath());
        }
    }

    public boolean isRecording() {
        return recording;
    }

    public String getSessionPath() {
        return sessionDir != null ? sessionDir.getAbsolutePath() : "";
    }

    public void recordFrame(Mat frame, BattleFrameProcessor.FrameMetadata metadata, List<BattleSensorProvider.ImuSample> imuSamples) {
        ExecutorService service;
        synchronized (this) {
            if (!recording || executor == null) {
                frame.release();
                return;
            }
            service = executor;
        }

        service.execute(() -> {
            Mat frameCopy = frame;
            int index = -1;
            try {
                List<BattleSensorProvider.ImuSample> samples;
                synchronized (BattleFrameLogger.this) {
                    if (!recording) {
                        return;
                    }
                    index = frameIndex++;
                    samples = imuSamples;
                }

                String frameFileName = String.format(Locale.US, "frame_%08d.png", index);
                File frameFile = new File(framesDir, frameFileName);
                writePng(frameCopy, frameFile);

                JSONObject frameObject = new JSONObject();
                frameObject.put("frameIndex", index);
                frameObject.put("frameFile", "frames/" + frameFileName);
                frameObject.put("width", metadata.width);
                frameObject.put("height", metadata.height);
                frameObject.put("frameTimestampNs", metadata.frameTimestampNs);
                frameObject.put("wallClockTimestampNs", metadata.wallClockTimestampNs);
                frameObject.put("elapsedMs", elapsedMs(metadata.wallClockTimestampNs));
                frameObject.put("frameToWallClockOffsetNs", metadata.wallClockTimestampNs - metadata.frameTimestampNs);
                frameObject.put("gyroX", metadata.gyroX);
                frameObject.put("gyroY", metadata.gyroY);
                frameObject.put("gyroZ", metadata.gyroZ);
                frameObject.put("gyroTimestampNs", metadata.gyroTimestampNs);
                frameObject.put("pitchDeg", metadata.pitchDeg);
                frameObject.put("yawDeg", metadata.yawDeg);
                frameObject.put("rollDeg", metadata.rollDeg);
                frameObject.put("targetDetected", metadata.targetDetected);
                frameObject.put("targetTracking", metadata.targetTracking);
                frameObject.put("targetBboxX", metadata.targetBboxX);
                frameObject.put("targetBboxY", metadata.targetBboxY);
                frameObject.put("targetBboxW", metadata.targetBboxW);
                frameObject.put("targetBboxH", metadata.targetBboxH);
                frameObject.put("targetConfidence", metadata.targetConfidence);
                frameObject.put("imuSampleCount", samples.size());

                JSONArray imuArray = new JSONArray();
                for (BattleSensorProvider.ImuSample sample : samples) {
                    imuArray.put(sampleToJson(index, sample, metadata.frameTimestampNs));
                }
                frameObject.put("imuSamples", imuArray);

                synchronized (BattleFrameLogger.this) {
                    if (frameWriter != null) {
                        frameWriter.write(frameObject.toString());
                        frameWriter.newLine();
                        frameWriter.flush();
                    }
                    writeImuSamplesLocked(index, samples, metadata.frameTimestampNs);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to record frame " + index, e);
            } finally {
                frameCopy.release();
            }
        });
    }

    public void recordState(JSONObject state) {
        ExecutorService service;
        synchronized (this) {
            if (!recording || executor == null || state == null) {
                return;
            }
            service = executor;
        }

        service.execute(() -> {
            try {
                synchronized (BattleFrameLogger.this) {
                    if (!recording || stateWriter == null) {
                        return;
                    }
                    state.put("elapsedMs", elapsedMs(System.nanoTime()));
                    state.put("wallClockTimestampNs", System.nanoTime());
                    stateWriter.write(state.toString());
                    stateWriter.newLine();
                    stateWriter.flush();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to record state", e);
            }
        });
    }

    private JSONObject sampleToJson(int frameIndex, BattleSensorProvider.ImuSample sample, long frameTimestampNs) throws Exception {
        JSONObject object = new JSONObject();
        object.put("frameIndex", frameIndex);
        object.put("sensorType", sample.sensorType);
        object.put("sensorName", sensorName(sample.sensorType));
        object.put("timestampNs", sample.timestampNs);
        object.put("wallClockTimestampNs", sample.wallClockTimestampNs);
        object.put("elapsedMs", elapsedMs(sample.wallClockTimestampNs));
        object.put("deltaToFrameNs", sample.timestampNs - frameTimestampNs);

        JSONArray values = new JSONArray();
        for (float value : sample.values) {
            values.put(value);
        }
        object.put("values", values);
        return object;
    }

    private void writeImuSamplesLocked(int frameIndex, List<BattleSensorProvider.ImuSample> samples, long frameTimestampNs) throws IOException {
        for (BattleSensorProvider.ImuSample sample : samples) {
            try {
                imuWriter.write(sampleToJson(frameIndex, sample, frameTimestampNs).toString());
            } catch (Exception e) {
                throw new IOException(e);
            }
            imuWriter.newLine();
        }
        imuWriter.flush();
    }

    private double elapsedMs(long timestampNs) {
        return (timestampNs - startNs) / 1_000_000.0;
    }

    private void writePng(Mat frame, File file) throws IOException {
        int width = frame.width();
        int height = frame.height();
        byte[] pixels = new byte[width * height * 4];
        frame.get(0, 0, pixels);

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
        } finally {
            bitmap.recycle();
        }
    }

    private String sensorName(int sensorType) {
        switch (sensorType) {
            case Sensor.TYPE_GYROSCOPE:
                return "gyroscope";
            case Sensor.TYPE_ACCELEROMETER:
                return "accelerometer";
            case Sensor.TYPE_ROTATION_VECTOR:
                return "rotation_vector";
            default:
                return "sensor_" + sensorType;
        }
    }

    private void closeQuietly() {
        closeWriter(frameWriter);
        closeWriter(imuWriter);
        closeWriter(stateWriter);
        closeWriter(metadataWriter);
        frameWriter = null;
        imuWriter = null;
        stateWriter = null;
        metadataWriter = null;
    }

    private void closeWriter(BufferedWriter writer) {
        if (writer == null) return;
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            Log.w(TAG, "Failed to close writer", e);
        }
    }
}
