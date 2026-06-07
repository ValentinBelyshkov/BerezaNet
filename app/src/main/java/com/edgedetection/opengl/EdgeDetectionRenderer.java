package com.edgedetection.opengl;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class EdgeDetectionRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "EdgeDetectionRenderer";

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
                    "attribute vec2 aTexCoord;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "  gl_Position = aPosition;\n" +
                    "  vTexCoord = aTexCoord;\n" +
                    "}";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform sampler2D uTexture;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                    "}";

    private static final float[] VERTICES = {
            -1.0f, -1.0f, 0.0f,  0.0f, 1.0f,
            1.0f, -1.0f, 0.0f,  1.0f, 1.0f,
            -1.0f,  1.0f, 0.0f,  0.0f, 0.0f,
            1.0f,  1.0f, 0.0f,  1.0f, 0.0f
    };

    private int mProgram;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mTextureHandle;
    private int mTextureId;

    private FloatBuffer mVertexBuffer;
    private ByteBuffer mPixelBuffer;
    private int mFrameWidth;
    private int mFrameHeight;
    private boolean mNewFrame;
    private final Object mLock = new Object();

    private boolean mInitialized = false;

    public EdgeDetectionRenderer() {
        ByteBuffer bb = ByteBuffer.allocateDirect(VERTICES.length * 4);
        bb.order(ByteOrder.nativeOrder());
        mVertexBuffer = bb.asFloatBuffer();
        mVertexBuffer.put(VERTICES);
        mVertexBuffer.position(0);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        initGL();
        mInitialized = true;
        Log.i(TAG, "Renderer initialized");
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        if (mProgram == 0) return;

        synchronized (mLock) {
            if (mNewFrame && mPixelBuffer != null) {
                mPixelBuffer.position(0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId);
                GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                        mFrameWidth, mFrameHeight, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mPixelBuffer
                );
                mNewFrame = false;
            }
        }

        GLES20.glUseProgram(mProgram);

        mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, 5 * 4, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(mPositionHandle);

        mVertexBuffer.position(3);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 5 * 4, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId);
        GLES20.glUniform1i(mTextureHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }

    private void initGL() {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vs);
        GLES20.glAttachShader(mProgram, fs);
        GLES20.glLinkProgram(mProgram);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Shader link failed: " + GLES20.glGetProgramInfoLog(mProgram));
            mProgram = 0;
            return;
        }

        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mTextureHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mTextureId = textures[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    public void updateTexture(byte[] imageData, int width, int height) {
        if (imageData == null || width <= 0 || height <= 0) return;
        int expected = width * height * 4;
        if (imageData.length != expected) return;

        synchronized (mLock) {
            if (mPixelBuffer == null || mPixelBuffer.capacity() != expected) {
                mPixelBuffer = ByteBuffer.allocateDirect(expected);
                mPixelBuffer.order(ByteOrder.nativeOrder());
            }
            mPixelBuffer.clear();
            mPixelBuffer.put(imageData);
            mPixelBuffer.flip();
            mFrameWidth = width;
            mFrameHeight = height;
            mNewFrame = true;
        }
    }

    public void cleanup() {
        if (mTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{mTextureId}, 0);
            mTextureId = 0;
        }
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        mInitialized = false;
    }

    private int loadShader(int type, String code) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile error: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}