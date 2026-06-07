package com.edgedetection.opengl;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.util.Log;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

public class EdgeDetectionGLView extends GLSurfaceView {
    private static final String TAG = "EdgeDetectionGLView";
    private final EdgeDetectionRenderer mRenderer;

    public EdgeDetectionGLView(Context context) {
        this(context, null);
    }

    public EdgeDetectionGLView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        mRenderer = new EdgeDetectionRenderer();  // <-- без параметра!
        setRenderer(mRenderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    public void updateFrame(Mat mat) {
        if (mat == null || mat.empty()) return;

        byte[] rgbaBytes = matToRgbaBytes(mat);
        if (rgbaBytes != null) {
            // Было: updateCannyTexture → Стало: updateTexture
            mRenderer.updateTexture(rgbaBytes, mat.cols(), mat.rows());
            requestRender();
        }
    }

    private byte[] matToRgbaBytes(Mat mat) {
        try {
            Mat rgba = new Mat();
            int ch = mat.channels();

            if (ch == 1) {
                Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_GRAY2RGBA);
            } else if (ch == 3) {
                Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA);
            } else if (ch == 4) {
                rgba = mat.clone();
            } else {
                return null;
            }

            int w = rgba.cols();
            int h = rgba.rows();
            byte[] data = new byte[w * h * 4];
            rgba.get(0, 0, data);

            if (ch != 4) rgba.release();
            return data;
        } catch (Exception e) {
            Log.e(TAG, "Conversion error", e);
            return null;
        }
    }

    public void cleanup() {
        onPause();
        mRenderer.cleanup();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        onResume(); // GLSurfaceView восстанавливает EGLContext
    }

    @Override
    protected void onDetachedFromWindow() {
        onPause(); // GLSurfaceView сохраняет EGLContext
        super.onDetachedFromWindow();
    }
}