package com.edgedetection.core.camera;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.Executor;

/**
 * Implementation of CameraSource using Android CameraX (Internal Camera).
 */
public class InternalCameraSource implements CameraSource {
    private static final String TAG = "InternalCameraSource";

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final PreviewView previewView;
    private final Executor executor;
    
    private ProcessCameraProvider cameraProvider;
    private CameraSourceListener listener;
    private boolean isRunning = false;

    public InternalCameraSource(Context context, LifecycleOwner lifecycleOwner, PreviewView previewView, Executor executor) {
        this.context = context;
        this.lifecycleOwner = lifecycleOwner;
        this.previewView = previewView;
        this.executor = executor;
    }

    @Override
    public void start(CameraSourceListener listener) {
        this.listener = listener;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
                isRunning = true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to get camera provider", e);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null || listener == null) return;

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();

        imageAnalysis.setAnalyzer(executor, image -> {
            if (listener != null) {
                listener.onFrame(image);
            } else {
                image.close();
            }
        });

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
            );
            Log.i(TAG, "Internal camera bound successfully");
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
        }
    }

    @Override
    public void stop() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        isRunning = false;
        listener = null;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }
}
