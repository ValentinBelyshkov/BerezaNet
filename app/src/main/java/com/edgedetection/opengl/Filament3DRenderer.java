package com.edgedetection.opengl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceView;

import com.google.android.filament.Camera;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.IndirectLight;
import com.google.android.filament.LightManager;
import com.google.android.filament.Renderer;
import com.google.android.filament.Scene;
import com.google.android.filament.Skybox;
import com.google.android.filament.SwapChain;
import com.google.android.filament.Texture;
import com.google.android.filament.TransformManager;
import com.google.android.filament.View;
import com.google.android.filament.Viewport;
import com.google.android.filament.gltfio.AssetLoader;
import com.google.android.filament.gltfio.FilamentAsset;
import com.google.android.filament.gltfio.Gltfio;
import com.google.android.filament.gltfio.ResourceLoader;
import com.google.android.filament.gltfio.UbershaderProvider;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class Filament3DRenderer {
    private static final String TAG = "Filament3D";

    private final Engine mEngine;
    private final Renderer mRenderer;
    private final Scene mScene;
    private final View mView;
    private final Camera mCamera;
    private final AssetLoader mAssetLoader;
    private final ResourceLoader mResourceLoader;
    private final Context mContext;

    private FilamentAsset mSkyboxAsset;
    private boolean mSkyboxLoaded = false;
    private final Object mSwapChainLock = new Object();
    private SwapChain mSwapChain;
    private volatile boolean mLifecycleResumed = false;
    private volatile boolean mDestroyed = false;
    private volatile boolean mEngineDestroyed = false;
    private float mFovDegrees = 45f;
    private float mAspectRatio = 1.0f;
    private static final float SKYBOX_SCALE = 100.0f;
    private Choreographer mChoreographer;

    private final Choreographer.FrameCallback mFrameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mLifecycleResumed || mDestroyed || mEngineDestroyed) return;

            boolean hasSwapChain;
            synchronized (mSwapChainLock) {
                hasSwapChain = (mSwapChain != null);
            }

            if (hasSwapChain) {
                synchronized (mSwapChainLock) {
                    if (mDestroyed || mEngineDestroyed || mSwapChain == null) return;

                    boolean frameBegun = false;
                    try {
                        frameBegun = mRenderer.beginFrame(mSwapChain, frameTimeNanos);
                        if (frameBegun) {
                            mRenderer.render(mView);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Render frame error", e);
                    } finally {
                        if (frameBegun) {
                            try {
                                mRenderer.endFrame();
                            } catch (Exception e) {
                                Log.e(TAG, "endFrame error", e);
                            }
                        }
                    }
                }
            }

            if (mLifecycleResumed && mChoreographer != null && !mDestroyed) {
                mChoreographer.postFrameCallback(this);
            }
        }
    };

    private FilamentAsset mAsset;
    private boolean mModelLoaded = false;
    private Skybox mSkybox;
    private Texture mSkyboxTexture;

    private float[] mModelCenter = {0f, 0f, 0f};
    private float mModelRadius = 1.0f;
    private boolean mModelVisible = true;

    public Filament3DRenderer(Context context, SurfaceView surfaceView) {
        this(context, surfaceView, true);
    }

    public Filament3DRenderer(Context context, SurfaceView surfaceView, boolean transparent) {
        mContext = context;

        Gltfio.init();
        mEngine = Engine.create();
        mRenderer = mEngine.createRenderer();
        mScene = mEngine.createScene();
        mView = mEngine.createView();
        mCamera = mEngine.createCamera(mEngine.getEntityManager().create());

        mView.setCamera(mCamera);
        mView.setScene(mScene);

        if (transparent) {
            mView.setBlendMode(View.BlendMode.TRANSLUCENT);
            mScene.setSkybox(null);
            if (surfaceView != null) {
                surfaceView.setZOrderMediaOverlay(true);
                surfaceView.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            }
        } else {
            mView.setBlendMode(View.BlendMode.OPAQUE);
            setSkyboxColor(0.05f, 0.1f, 0.2f, 1.0f);
        }
        mView.setPostProcessingEnabled(true);

        UbershaderProvider materialProvider = new UbershaderProvider(mEngine);
        mAssetLoader = new AssetLoader(mEngine, materialProvider, EntityManager.get());
        mResourceLoader = new ResourceLoader(mEngine);

        mCamera.setExposure(16.0f, 1.0f / 125.0f, 100.0f);
        mCamera.setProjection(mFovDegrees, 1.0, 0.1, 1000.0, Camera.Fov.VERTICAL);

        addDefaultLighting();

        if (surfaceView != null) {
            surfaceView.getHolder().addCallback(new SurfaceHolderCallback());
        }

        Log.i(TAG, "Filament ready (transparent=" + transparent + ")");
    }

    private void addDefaultLighting() {
        int light = EntityManager.get().create();
        new LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 1.0f, 1.0f)
                .intensity(100000.0f)
                .direction(0.0f, -1.0f, -1.0f)
                .castShadows(false)
                .build(mEngine, light);
        mScene.addEntity(light);
    }

    private class SurfaceHolderCallback implements android.view.SurfaceHolder.Callback {
        @Override
        public void surfaceCreated(android.view.SurfaceHolder holder) {
            Log.i(TAG, "surfaceCreated");
            if (mDestroyed || mEngineDestroyed) return;
            Surface surface = holder.getSurface();
            if (surface != null && surface.isValid()) {
                synchronized (mSwapChainLock) {
                    if (!mDestroyed && !mEngineDestroyed && mSwapChain == null) {
                        try {
                            mSwapChain = mEngine.createSwapChain(surface);
                            Log.i(TAG, "SwapChain created");
                        } catch (Exception e) {
                            Log.e(TAG, "createSwapChain failed", e);
                        }
                    }
                }
                if (mLifecycleResumed && !mDestroyed) {
                    startChoreographer();
                }
            }
        }

        @Override
        public void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height) {
            Log.i(TAG, "surfaceChanged " + width + "x" + height);
            if (mDestroyed || mEngineDestroyed) return;
            mView.setViewport(new Viewport(0, 0, width, height));
            synchronized (mSwapChainLock) {
                if (!mDestroyed && !mEngineDestroyed && mSwapChain == null) {
                    Surface surface = holder.getSurface();
                    if (surface != null && surface.isValid()) {
                        try {
                            mSwapChain = mEngine.createSwapChain(surface);
                            Log.i(TAG, "SwapChain created in surfaceChanged");
                        } catch (Exception e) {
                            Log.e(TAG, "createSwapChain failed", e);
                        }
                    }
                }
            }
            if (mLifecycleResumed && !mDestroyed) {
                startChoreographer();
            }
        }

        @Override
        public void surfaceDestroyed(android.view.SurfaceHolder holder) {
            Log.i(TAG, "surfaceDestroyed");
            onPause();
            synchronized (mSwapChainLock) {
                if (mDestroyed || mEngineDestroyed || mSwapChain == null) return;
                try {
                    mEngine.destroySwapChain(mSwapChain);
                } catch (Exception e) {
                    Log.e(TAG, "destroySwapChain error", e);
                }
                mSwapChain = null;
            }
        }
    }

    public void loadModel(String assetPath) {
        if (mDestroyed || mEngineDestroyed) return;
        mModelLoaded = false;
        InputStream stream = null;
        try {
            stream = mContext.getAssets().open(assetPath);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            byte[] bytes = baos.toByteArray();

            ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
            mAsset = mAssetLoader.createAsset(byteBuffer);

            if (mAsset == null) {
                Log.e(TAG, "Failed to load: " + assetPath);
                return;
            }

            mResourceLoader.loadResources(mAsset);

            // ✅ ДИАГНОСТИКА: Проверяем структуру модели
            int root = mAsset.getRoot();
            int[] allEntities = mAsset.getEntities();
            int[] renderables = mAsset.getRenderableEntities();

            Log.w(TAG, "=== MODEL STRUCTURE ===");
            Log.w(TAG, "Root entity: " + root);
            Log.w(TAG, "Total entities: " + (allEntities != null ? allEntities.length : 0));
            Log.w(TAG, "Renderable entities: " + (renderables != null ? renderables.length : 0));

            if (renderables != null) {
                for (int i = 0; i < renderables.length; i++) {
                    Log.w(TAG, "Renderable[" + i + "]: " + renderables[i]);
                }
            }

            // ✅ ИСПРАВЛЕНО: Добавляем все entities (как было), но логируем
            if (allEntities != null) {
                for (int entity : allEntities) {
                    mScene.addEntity(entity);
                }
                Log.i(TAG, "Added " + allEntities.length + " entities to scene");
            }

            mModelCenter = mAsset.getBoundingBox().getCenter();
            float[] halfExtent = mAsset.getBoundingBox().getHalfExtent();
            float maxExtent = Math.max(halfExtent[0], Math.max(halfExtent[1], halfExtent[2]));
            mModelRadius = maxExtent;
            float distance = maxExtent * 3.0f;

            mCamera.lookAt(
                    mModelCenter[0], mModelCenter[1] + distance, mModelCenter[2] + distance,
                    mModelCenter[0], mModelCenter[1], mModelCenter[2],
                    0, 1, 0
            );

            mModelLoaded = true;
            mModelVisible = true;
            Log.i(TAG, "Model loaded: " + assetPath);

        } catch (Exception e) {
            Log.e(TAG, "Load error", e);
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (Exception ignored) {}
            }
        }
    }

    public boolean isModelLoaded() {
        return mModelLoaded;
    }

    public void setModelVisible(boolean visible) {
        if (mAsset == null || mDestroyed || mEngineDestroyed) return;
        if (mModelVisible == visible) return;

        int[] entities = mAsset.getEntities();
        if (entities != null) {
            for (int entity : entities) {
                if (visible) {
                    mScene.addEntity(entity);
                } else {
                    mScene.removeEntity(entity);
                }
            }
        }
        mModelVisible = visible;
    }

    public Camera getCamera() {
        return mCamera;
    }

    public float[] getModelCenter() {
        return mModelCenter;
    }

    public float getModelRadius() {
        return mModelRadius;
    }

    public void setSkyboxColor(float r, float g, float b, float a) {
        if (mDestroyed || mEngineDestroyed) return;

        if (mSkybox != null) {
            mScene.setSkybox(null);
            mEngine.destroySkybox(mSkybox);
            mSkybox = null;
        }

        mSkybox = new Skybox.Builder()
                .color(r, g, b, a)
                .build(mEngine);
        mScene.setSkybox(mSkybox);
    }

    public void setSkyboxFromDrawable(int drawableResId) {
        if (mDestroyed || mEngineDestroyed) return;

        try {
            Bitmap bitmap = BitmapFactory.decodeResource(mContext.getResources(), drawableResId);
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode drawable");
                return;
            }
            bitmap.recycle();

            Log.w(TAG, "============================================================");
            Log.w(TAG, "HDRI from drawable: equirectangular JPG -> NOT SUPPORTED");
            Log.w(TAG, "Filament Skybox requires pre-converted KTX cubemap.");
            Log.w(TAG, "CONVERT: cmgen --deploy=./out --format=ktx --size=256 hdri.jpg");
            Log.w(TAG, "PLACE:   app/src/main/assets/skybox/output_hdri.ktx");
            Log.w(TAG, "LOAD:    loadSkyboxKtx(\"skybox/output_hdri.ktx\")");
            Log.w(TAG, "============================================================");

            setSkyboxColor(0.55f, 0.20f, 0.15f, 1.0f);

        } catch (Exception e) {
            Log.e(TAG, "Error in setSkyboxFromDrawable", e);
        }
    }

    public void updateCameraSpringArm(float yaw, float pitch) {
        if (mDestroyed || mEngineDestroyed) return;

        float distance = mModelRadius * 3.0f;
        float clampedPitch = Math.max(-1.2f, Math.min(1.2f, pitch));

        float cosP = (float) Math.cos(clampedPitch);
        float sinP = (float) Math.sin(clampedPitch);
        float cosY = (float) Math.cos(yaw);
        float sinY = (float) Math.sin(yaw);

        float camX = distance * cosP * sinY;
        float camY = 0.5f + distance * sinP;
        float camZ = -distance * cosP * cosY;

        mCamera.lookAt(
                camX, camY, camZ,
                mModelCenter[0], mModelCenter[1] + 0.5f, mModelCenter[2],
                0, 1, 0
        );
    }

    private void startChoreographer() {
        if (mChoreographer == null) {
            mChoreographer = Choreographer.getInstance();
        }
        mChoreographer.removeFrameCallback(mFrameCallback);
        mChoreographer.postFrameCallback(mFrameCallback);
    }

    private void stopChoreographer() {
        if (mChoreographer != null) {
            mChoreographer.removeFrameCallback(mFrameCallback);
        }
    }

    public void onResume() {
        if (mDestroyed || mEngineDestroyed) return;
        if (!mLifecycleResumed) {
            mLifecycleResumed = true;
            startChoreographer();
        }
    }

    public void onPause() {
        if (mLifecycleResumed) {
            mLifecycleResumed = false;
            stopChoreographer();
        }
    }

    public void loadSkyboxModel(String assetPath) {
        if (mDestroyed || mEngineDestroyed) return;

        InputStream stream = null;
        try {
            stream = mContext.getAssets().open(assetPath);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            byte[] bytes = baos.toByteArray();

            ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
            mSkyboxAsset = mAssetLoader.createAsset(byteBuffer);

            if (mSkyboxAsset == null) {
                Log.e(TAG, "Failed to load skybox: " + assetPath);
                return;
            }

            mResourceLoader.loadResources(mSkyboxAsset);

            // ✅ ИСПРАВЛЕНО: Добавляем ТОЛЬКО root entity
            int root = mSkyboxAsset.getRoot();
            if (root != 0) {
                mScene.addEntity(root);
                Log.i(TAG, "Skybox root entity added to scene: " + root);
            } else {
                int[] entities = mSkyboxAsset.getEntities();
                if (entities != null && entities.length > 0) {
                    mScene.addEntity(entities[0]);
                    Log.w(TAG, "Skybox root is 0, using first entity: " + entities[0]);
                }
            }

            mSkyboxLoaded = true;
            Log.i(TAG, "Skybox loaded: " + assetPath);

        } catch (Exception e) {
            Log.e(TAG, "Skybox load error", e);
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void scaleEntities(int[] entities, float scale) {
        TransformManager tm = mEngine.getTransformManager();
        float[] matrix = new float[]{
                scale, 0, 0, 0,
                0, scale, 0, 0,
                0, 0, scale, 0,
                0, 0, 0, 1
        };
        for (int entity : entities) {
            int inst = tm.getInstance(entity);
            if (inst == 0) {
                inst = tm.create(entity);
            }
            tm.setTransform(inst, matrix);
        }
    }

    public void setupEnvironmentLighting() {
        if (mDestroyed || mEngineDestroyed) return;

        float[] sh = new float[27];
        sh[0]  = 0.282095f; sh[1]  = 0.282095f; sh[2]  = 0.282095f;

        IndirectLight ibl = new IndirectLight.Builder()
                .irradiance(3, sh)
                .intensity(40000.0f)
                .build(mEngine);

        mScene.setIndirectLight(ibl);

        int light2 = EntityManager.get().create();
        new LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 0.95f, 0.9f)
                .intensity(200000.0f)
                .direction(0.5f, -0.3f, 0.8f)
                .castShadows(false)
                .build(mEngine, light2);
        mScene.addEntity(light2);

        mCamera.setExposure(16.0f, 1.0f / 60.0f, 100.0f);

        Log.i(TAG, "Environment lighting setup complete");
    }

    public boolean isSkyboxLoaded() {
        return mSkyboxLoaded;
    }

    public void calibrateSkybox(float northAzimuth) {
        if (mDestroyed || mEngineDestroyed || mSkyboxAsset == null) return;

        float angle = -northAzimuth;
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        float S = SKYBOX_SCALE;

        float[] matrix = new float[16];
        matrix[0]  = S * c;   matrix[4]  = 0;       matrix[8]  = S * s;   matrix[12] = 0;
        matrix[1]  = 0;       matrix[5]  = S;       matrix[9]  = 0;       matrix[13] = 0;
        matrix[2]  = -S * s;  matrix[6]  = 0;       matrix[10] = S * c;   matrix[14] = 0;
        matrix[3]  = 0;       matrix[7]  = 0;       matrix[11] = 0;       matrix[15] = 1;

        TransformManager tm = mEngine.getTransformManager();

        // ✅ ИСПРАВЛЕНО: Применяем трансформацию ТОЛЬКО к root
        int root = mSkyboxAsset.getRoot();
        if (root != 0) {
            int inst = tm.getInstance(root);
            if (inst == 0) {
                inst = tm.create(root);
            }
            tm.setTransform(inst, matrix);
        } else {
            int[] entities = mSkyboxAsset.getEntities();
            if (entities != null && entities.length > 0) {
                int inst = tm.getInstance(entities[0]);
                if (inst == 0) {
                    inst = tm.create(entities[0]);
                }
                tm.setTransform(inst, matrix);
            }
        }
        Log.i(TAG, "Skybox calibrated: scale=" + S + ", northOffset=" + Math.toDegrees(northAzimuth) + "°");
    }

    public void attachSurface(android.view.Surface surface, int width, int height) {
        synchronized (mSwapChainLock) {
            if (mDestroyed || mEngineDestroyed || mSwapChain != null) return;
            try {
                mSwapChain = mEngine.createSwapChain(surface);
                mView.setViewport(new Viewport(0, 0, width, height));
                mAspectRatio = (float) width / Math.max(1, height);
                mCamera.setProjection(mFovDegrees, mAspectRatio, 0.1, 1000.0, Camera.Fov.VERTICAL);
            } catch (Exception e) { Log.e(TAG, "attachSurface failed", e); }
        }
        if (mLifecycleResumed) startChoreographer();
    }

    public void detachSurface() {
        onPause();
        synchronized (mSwapChainLock) {
            if (mSwapChain != null) {
                try { mEngine.destroySwapChain(mSwapChain); } catch (Exception e) {}
                mSwapChain = null;
            }
        }
    }

    public void setDronePosition(float x, float y, float z, float heading) {
        if (mDestroyed || mEngineDestroyed || mAsset == null) return;
        TransformManager tm = mEngine.getTransformManager();

        int root = mAsset.getRoot();
        if (root != 0) {
            int inst = tm.getInstance(root);
            if (inst == 0) inst = tm.create(root);
            float c = (float) Math.cos(heading), s = (float) Math.sin(heading);
            float[] m = new float[16];
            m[0] = c;   m[4] = 0; m[8]  = s;  m[12] = x;
            m[1] = 0;   m[5] = 1; m[9]  = 0;  m[13] = y;
            m[2] = -s;  m[6] = 0; m[10] = c;  m[14] = z;
            m[3] = 0;   m[7] = 0; m[11] = 0;  m[15] = 1;
            tm.setTransform(inst, m);
            Log.d(TAG, "Drone moved to root: " + x + ", " + y + ", " + z);
        } else {
            int[] entities = mAsset.getEntities();
            if (entities != null) {
                for (int entity : entities) {
                    int inst = tm.getInstance(entity);
                    if (inst == 0) inst = tm.create(entity);
                    float c = (float) Math.cos(heading), s = (float) Math.sin(heading);
                    float[] m = new float[16];
                    m[0] = c;   m[4] = 0; m[8]  = s;  m[12] = x;
                    m[1] = 0;   m[5] = 1; m[9]  = 0;  m[13] = y;
                    m[2] = -s;  m[6] = 0; m[10] = c;  m[14] = z;
                    m[3] = 0;   m[7] = 0; m[11] = 0;  m[15] = 1;
                    tm.setTransform(inst, m);
                }
                Log.d(TAG, "Drone moved (fallback) to: " + x + ", " + y + ", " + z);
            }
        }
    }

    public float[] getDroneModelMatrix() {
        if (mAsset == null) return new float[16];
        TransformManager tm = mEngine.getTransformManager();
        int root = mAsset.getRoot();
        float[] matrix = new float[16];
        if (root != 0) {
            int inst = tm.getInstance(root);
            if (inst != 0) {
                tm.getTransform(inst, matrix);
            }
        }
        return matrix;
    }

    public void updateCameraAR(float eyeHeight,
                               float forwardX, float forwardY, float forwardZ,
                               float upX, float upY, float upZ) {
        if (mDestroyed || mEngineDestroyed) return;
        float ex = 0f, ey = eyeHeight, ez = 0f;
        mCamera.lookAt(
                ex, ey, ez,
                ex + forwardX, ey + forwardY, ez + forwardZ,
                upX, upY, upZ
        );
    }

    private double mFarPlane = 1000.0;

    public void setFarPlane(double far) {
        mFarPlane = far;
        if (mDestroyed || mEngineDestroyed) return;
        mCamera.setProjection(mFovDegrees, mAspectRatio, 0.1, far, Camera.Fov.VERTICAL);
    }

    public double getFarPlane() {
        return mFarPlane;
    }

    public float getFovDegrees() { return mFovDegrees; }
    public float getAspectRatio() { return mAspectRatio; }
    public Viewport getViewport() { return mView.getViewport(); }

    public void destroy() {
        if (mDestroyed) return;
        mDestroyed = true;
        onPause();

        synchronized (mSwapChainLock) {
            if (mSkybox != null) {
                try {
                    mScene.setSkybox(null);
                    mEngine.destroySkybox(mSkybox);
                } catch (Exception e) {
                    Log.e(TAG, "destroy skybox error", e);
                }
                mSkybox = null;
            }
            if (mSkyboxTexture != null) {
                try {
                    mEngine.destroyTexture(mSkyboxTexture);
                } catch (Exception e) {
                    Log.e(TAG, "destroy skybox texture error", e);
                }
                mSkyboxTexture = null;
            }

            if (mAsset != null) {
                try {
                    int[] entities = mAsset.getEntities();
                    if (entities != null) {
                        for (int entity : entities) {
                            mScene.removeEntity(entity);
                        }
                    }
                    mAssetLoader.destroyAsset(mAsset);
                } catch (Exception e) {
                    Log.e(TAG, "destroy asset error", e);
                }
                mAsset = null;
            }
            if (mSwapChain != null && !mEngineDestroyed) {
                try {
                    mEngine.destroySwapChain(mSwapChain);
                } catch (Exception e) {
                    Log.e(TAG, "destroy swapchain error", e);
                }
                mSwapChain = null;
            }
            if (!mEngineDestroyed) {
                try {
                    mEngine.destroy();
                } catch (Exception e) {
                    Log.e(TAG, "destroy engine error", e);
                }
                mEngineDestroyed = true;
            }
            if (mSkyboxAsset != null) {
                try {
                    // ✅ ИСПРАВЛЕНО: Удаляем только root
                    int root = mSkyboxAsset.getRoot();
                    if (root != 0) {
                        mScene.removeEntity(root);
                    }
                    mAssetLoader.destroyAsset(mSkyboxAsset);
                } catch (Exception e) {
                    Log.e(TAG, "destroy skybox asset error", e);
                }
                mSkyboxAsset = null;
            }
        }
    }
}