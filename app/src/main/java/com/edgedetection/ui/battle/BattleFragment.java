package com.edgedetection.ui.battle;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.edgedetection.R;
import com.edgedetection.EdgeDetector;
import com.edgedetection.domain.ballistics.Bullet;
import com.edgedetection.domain.geo.GeoUtils;
import com.edgedetection.domain.mission.DronePosition;
import com.edgedetection.domain.mission.Mission;
import com.edgedetection.opengl.EdgeDetectionGLView;
import com.edgedetection.opengl.Filament3DRenderer;
import com.edgedetection.ui.shared.MissionViewModel;
import com.edgedetection.ui.shared.MissionIntent;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.filament.Viewport;
import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BattleFragment extends Fragment implements SensorEventListener {
    private static final String TAG = "BattleFragment";
    private static final int CAMERA_PERMISSION_REQUEST = 1;
    private static final int AR_PERMISSION_REQUEST = 3;

    static {
        try {
            System.loadLibrary("opencv_java4");
            Log.i(TAG, "OpenCV loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "OpenCV failed: " + e.getMessage());
        }
    }

    // --- OpenCV / CameraX ---
    private BattleViewModel viewModel;
    private EdgeDetectionGLView glView;
    private PreviewView previewView;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private long lastFrameTime = 0;

    // --- AR overlay ---
    private SurfaceView arSurface;
    private Filament3DRenderer arRenderer;
    private ImageView offscreenIndicator;
    private TextView gpsWarning;

    // --- Bullet system ---
    private BulletTrajectoryView bulletOverlay;
    private ImageButton fireButton;
    private final List<Bullet> bullets = new ArrayList<>();
    private long lastFrameNanos = 0;
    private float camForwardX, camForwardY, camForwardZ;
    private float camUpX, camUpY, camUpZ;

    // Location
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private volatile double userLat, userLon, userAlt;
    private volatile boolean hasUserLocation = false;
    private double lastGoodLat, lastGoodLon, lastGoodAlt;

    // Sensors
    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;
    private final float[] rotationMatrix = new float[16];

    // Drone state
    private volatile double droneLat, droneLon, droneAlt;
    private volatile float droneHeading = 0f;
    private volatile boolean simulationActive = false;
    private volatile boolean simulationPaused = false;
    private volatile boolean hasDronePosition = false;
    private volatile int currentDroneIndex = -1;
    private float lastDroneX, lastDroneY, lastDroneZ;
    private boolean hasRelativePosition = false;
    private double missionOriginLat, missionOriginLon, missionOriginAlt;
    private boolean hasMissionOrigin = false;

    private static final float EYE_HEIGHT = 1.6f;
    private static final float TAN_HALF_FOV_Y = (float) Math.tan(Math.toRadians(22.5));

    // --- Choreographer: один кадр = один updateScene ---
    private Choreographer choreographer;
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (isDetached() || !isAdded()) return;
            float dt = (lastFrameNanos == 0) ? 0.016f : (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
            lastFrameNanos = frameTimeNanos;
            updateScene(dt);
        }
    };
    private boolean frameScheduled = false;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(this).get(BattleViewModel.class);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext());
        sensorManager = (SensorManager) requireContext().getSystemService(android.content.Context.SENSOR_SERVICE);
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        choreographer = Choreographer.getInstance();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_battle, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        previewView = view.findViewById(R.id.preview_view);
        previewView.setVisibility(View.INVISIBLE);
        glView = view.findViewById(R.id.camera_view);

        arSurface = view.findViewById(R.id.ar_overlay);
        offscreenIndicator = view.findViewById(R.id.offscreen_indicator);
        gpsWarning = view.findViewById(R.id.gps_warning);

        // Bullet overlay & fire button
        bulletOverlay = view.findViewById(R.id.bullet_overlay);
        fireButton = view.findViewById(R.id.fire_button);
        fireButton.setOnClickListener(v -> fireBullet());

        arRenderer = new Filament3DRenderer(requireContext(), arSurface, true);
        arRenderer.setFarPlane(2000.0);
        arRenderer.loadModel("models/drone.glb");
        if (!arRenderer.isModelLoaded()) {
            Toast.makeText(requireContext(), "drone.glb failed", Toast.LENGTH_LONG).show();
        } else {
            arRenderer.setModelVisible(false);
        }
        arRenderer.setupEnvironmentLighting();

        Log.d(TAG, "glView is " + (glView != null ? "found" : "NULL"));

        checkPermissions();

        // === Подписка на симуляцию ===
        MissionViewModel missionVm = new ViewModelProvider(requireActivity()).get(MissionViewModel.class);

        missionVm.getMissionState().observe(getViewLifecycleOwner(), mission -> {
            if (mission == null) return;
            if (mission.originLatitude != null && mission.originLongitude != null) {
                double alt = mission.originAltitudeAmsl != null ? mission.originAltitudeAmsl : 0.0;
                setMissionOrigin(mission.originLatitude, mission.originLongitude, alt);
            }
            onSimulationPaused(mission.simState == Mission.SimulationState.PAUSED);
            this.simulationActive = (mission.simState != Mission.SimulationState.IDLE);
            if (!this.simulationActive) {
                this.hasRelativePosition = false;
            }
        });

        missionVm.getDronePosition().observe(getViewLifecycleOwner(), pos -> {
            if (pos != null && pos.active) {
                this.currentDroneIndex = pos.index;
                updateDronePosition(pos.lat, pos.lon, pos.alt, pos.heading);
            }
        });

        if (!EdgeDetector.isLibraryLoaded()) {
            Toast.makeText(requireContext(), "Native library failed!", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Native library NOT loaded");
        } else {
            Log.i(TAG, "Native library IS loaded");
        }
    }

    // ===================== Permissions =====================

    private void checkPermissions() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), AR_PERMISSION_REQUEST);
        } else {
            startCamera();
            startLocationUpdates();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == AR_PERMISSION_REQUEST) {
            boolean cam = true, loc = true;
            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.CAMERA) && grantResults[i] != PackageManager.PERMISSION_GRANTED)
                    cam = false;
                if (permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION) && grantResults[i] != PackageManager.PERMISSION_GRANTED)
                    loc = false;
            }
            if (cam) startCamera();
            if (loc) startLocationUpdates();
        } else if (requestCode == CAMERA_PERMISSION_REQUEST && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(requireContext(), "Camera permission required!", Toast.LENGTH_LONG).show();
        }
    }

    // ===================== Location =====================

    private void startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        LocationRequest request = LocationRequest.create();
        request.setPriority(com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY);
        request.setInterval(1000);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc == null) return;
                if (!hasUserLocation) {
                    hasUserLocation = true;
                    if (gpsWarning != null) gpsWarning.setVisibility(View.GONE);
                }
                userLat = loc.getLatitude();
                userLon = loc.getLongitude();
                userAlt = loc.getAltitude();
                lastGoodLat = userLat;
                lastGoodLon = userLon;
                lastGoodAlt = userAlt;
                Log.d(TAG, "User GPS: " + userLat + ", " + userLon + ", " + userAlt);
            }
        };

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
    }

    // ===================== CameraX (OpenCV) =====================

    private void startCamera() {
        cameraExecutor = Executors.newSingleThreadExecutor();

        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(requireContext());
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception e) {
                Log.e(TAG, "Camera provider failed", e);
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, this::processFrame);

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(
                    getViewLifecycleOwner(),
                    cameraSelector,
                    preview,
                    imageAnalysis
            );
            Log.i(TAG, "Camera bound successfully");
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
        }
    }

    private void processFrame(ImageProxy image) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();

            byte[] bytes = new byte[image.getPlanes()[0].getBuffer().remaining()];
            image.getPlanes()[0].getBuffer().get(bytes);

            Mat rgba = new Mat(height, width, CvType.CV_8UC4);
            rgba.put(0, 0, bytes);

            long currentTime = System.currentTimeMillis();
            if (lastFrameTime != 0) {
                double fps = 1000.0 / (currentTime - lastFrameTime);
                viewModel.setFps(fps);
            }
            lastFrameTime = currentTime;

            Mat edges = viewModel.getEdges();
            if (edges == null || edges.width() != width || edges.height() != height) {
                viewModel.initMats(width, height);
                edges = viewModel.getEdges();
            }

            if (EdgeDetector.isLibraryLoaded() && edges != null) {
                EdgeDetector.detectEdges(
                        rgba.getNativeObjAddr(),
                        edges.getNativeObjAddr(),
                        50, 150, 5
                );
                if (glView != null) glView.updateFrame(edges);
            } else {
                if (glView != null) glView.updateFrame(rgba);
            }

            rgba.release();

        } catch (Exception e) {
            Log.e(TAG, "processFrame error: " + e.getMessage(), e);
        } finally {
            image.close();
        }
    }

    // ===================== Public API for simulation =====================

    public void updateDronePosition(double lat, double lon, double alt, float headingDegrees) {
        this.droneLat = lat;
        this.droneLon = lon;
        this.droneAlt = alt;
        this.droneHeading = headingDegrees;
        this.simulationActive = true;
        this.hasDronePosition = true;

        if (!simulationPaused) {
            scheduleFrame();
        }
    }

    public void onSimulationPaused(boolean paused) {
        this.simulationPaused = paused;
        if (!paused) {
            scheduleFrame();
        }
    }

    public void setMissionOrigin(double lat, double lon, double alt) {
        this.missionOriginLat = lat;
        this.missionOriginLon = lon;
        this.missionOriginAlt = alt;
        this.hasMissionOrigin = true;
        Log.i(TAG, "Mission origin set: " + lat + ", " + lon + ", " + alt);
    }

    // ===================== AR: Sensors & Scene =====================

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        scheduleFrame();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void scheduleFrame() {
        if (!frameScheduled) {
            frameScheduled = true;
            choreographer.postFrameCallback(frameCallback);
        }
    }

    // ===================== Bullet firing =====================

    private void fireBullet() {
        float speed = Bullet.SPEED_MPS;
        float vx = camForwardX * speed;
        float vy = camForwardY * speed;
        float vz = camForwardZ * speed;

        // Выстрел из центра экрана = из позиции камеры + 0.5м вперёд
        float sx = camForwardX * 0.5f;
        float sy = EYE_HEIGHT + camForwardY * 0.5f;
        float sz = camForwardZ * 0.5f;

        Bullet b = new Bullet(sx, sy, sz, vx, vy, vz);
        bullets.add(b);
        Log.i(TAG, "FIRE! count=" + bullets.size());
    }

    private void updateBullets(float dt) {
        if (bullets.isEmpty()) return;
        float droneRadius = arRenderer.getModelRadius();
        List<Bullet> toRemove = new ArrayList<>();

        for (Bullet b : bullets) {
            b.update(dt);
            if (b.active && simulationActive) {
                float d = b.distanceTo(lastDroneX, lastDroneY, lastDroneZ);
                if (d < droneRadius + 0.05f) {
                    b.active = false;
                    b.hit = true;
                    Log.i(TAG, ">>> HIT DRONE! dist=" + d + "m");
                    Toast.makeText(requireContext(), "Попадание!", Toast.LENGTH_SHORT).show();
                    
                    MissionViewModel missionVm = new ViewModelProvider(requireActivity()).get(MissionViewModel.class);
                    missionVm.dispatch(new MissionIntent.ShotDownDrone(currentDroneIndex));
                }
            } else if (!b.hit) {
                toRemove.add(b);
            } else if (b.hit) {
                if (System.currentTimeMillis() - b.spawnTime > 2000) toRemove.add(b);
            }
        }
        bullets.removeAll(toRemove);
    }

    // ===================== Scene update =====================

    private void updateScene(float dt) {
        frameScheduled = false;
        if (arRenderer == null || !arRenderer.isModelLoaded()) {
            Log.w(TAG, "AR not ready");
            return;
        }

        // 1. Камера из RotationVector
        float fx = -rotationMatrix[2];
        float fy = -rotationMatrix[8];
        float fz = -rotationMatrix[5];

        float ux = rotationMatrix[1];
        float uy = rotationMatrix[7];
        float uz = rotationMatrix[4];

        float fl = (float) Math.sqrt(fx*fx + fy*fy + fz*fz);
        if (fl > 0) { fx /= fl; fy /= fl; fz /= fl; }
        float ul = (float) Math.sqrt(ux*ux + uy*uy + uz*uz);
        if (ul > 0) { ux /= ul; uy /= ul; uz /= ul; }
        float dot = fx*ux + fy*uy + fz*uz;
        ux -= dot * fx; uy -= dot * fy; uz -= dot * fz;
        ul = (float) Math.sqrt(ux*ux + uy*uy + uz*uz);
        if (ul > 0) { ux /= ul; uy /= ul; uz /= ul; }

        // Сохраняем базис для выстрела
        camForwardX = fx; camForwardY = fy; camForwardZ = fz;
        camUpX = ux; camUpY = uy; camUpZ = uz;

        arRenderer.updateCameraAR(EYE_HEIGHT, fx, fy, fz, ux, uy, uz);

        // 2. Позиция дрона
        double refLat, refLon, refAlt;
        boolean canLocate = false;
        if (hasUserLocation) {
            refLat = userLat; refLon = userLon; refAlt = userAlt;
            canLocate = true;
        } else if (hasMissionOrigin) {
            refLat = missionOriginLat; refLon = missionOriginLon; refAlt = missionOriginAlt;
            canLocate = true;
            if (gpsWarning != null) {
                gpsWarning.setVisibility(View.VISIBLE);
                gpsWarning.setText("GPS недоступен — origin миссии");
            }
        } else {
            refLat = 0; refLon = 0; refAlt = 0;
        }

        if (canLocate && simulationActive && hasDronePosition) {
            double[] enu = GeoUtils.ecefToEnu(refLat, refLon, refAlt, droneLat, droneLon, droneAlt);
            float[] pos = GeoUtils.enuToFilament(enu);
            lastDroneX = pos[0];
            lastDroneY = pos[1];
            lastDroneZ = pos[2];
            hasRelativePosition = true;

            Log.d(TAG, "Drone ENU: E=" + enu[0] + " N=" + enu[1] + " U=" + enu[2]);
            Log.d(TAG, "Drone Filament: " + lastDroneX + ", " + lastDroneY + ", " + lastDroneZ);

            arRenderer.setModelVisible(true);
            arRenderer.setDronePosition(lastDroneX, lastDroneY, lastDroneZ,
                    (float) Math.toRadians(droneHeading));
        } else if (canLocate && hasRelativePosition) {
            arRenderer.setModelVisible(true);
            arRenderer.setDronePosition(lastDroneX, lastDroneY, lastDroneZ,
                    (float) Math.toRadians(droneHeading));
            if (gpsWarning != null && !hasUserLocation) {
                gpsWarning.setVisibility(View.VISIBLE);
                gpsWarning.setText("GPS недоступен — последняя известная позиция");
            }
        } else {
            arRenderer.setModelVisible(false);
        }

        // 3. Баллистика
        if (dt > 0 && dt < 0.5f) {
            updateBullets(dt);
        }

        // 4. Индикатор за краем экрана
        updateOffscreenIndicator(fx, fy, fz, ux, uy, uz);

        // 5. Обновляем оверлей траекторий (матрицы камеры + пули)
        if (bulletOverlay != null) {
            double[] viewMatDouble = new double[16];
            double[] projMatDouble = new double[16];
            arRenderer.getCamera().getViewMatrix(viewMatDouble);
            arRenderer.getCamera().getProjectionMatrix(projMatDouble);

            float[] viewMat = new float[16];
            float[] projMat = new float[16];
            for (int i = 0; i < 16; i++) {
                viewMat[i] = (float) viewMatDouble[i];
                projMat[i] = (float) projMatDouble[i];
            }

            Viewport vp = arRenderer.getViewport();
            bulletOverlay.setCameraMatrices(viewMat, projMat, vp.width, vp.height);
            bulletOverlay.setBullets(bullets);
        }
    }

    private void updateOffscreenIndicator(float fx, float fy, float fz,
                                          float ux, float uy, float uz) {
        if (!hasRelativePosition || offscreenIndicator == null) {
            if (offscreenIndicator != null) offscreenIndicator.setVisibility(View.GONE);
            return;
        }

        float rx = fy * uz - fz * uy;
        float ry = fz * ux - fx * uz;
        float rz = fx * uy - fy * ux;

        float dx = lastDroneX;
        float dy = lastDroneY - EYE_HEIGHT;
        float dz = lastDroneZ;

        float zCam = dx*fx + dy*fy + dz*fz;
        float xCam = dx*rx + dy*ry + dz*rz;
        float yCam = dx*ux + dy*uy + dz*uz;

        float aspect = arRenderer.getAspectRatio();
        float tanX = aspect * TAN_HALF_FOV_Y;

        boolean visible = zCam > 0.1f &&
                Math.abs(xCam) < zCam * tanX &&
                Math.abs(yCam) < zCam * TAN_HALF_FOV_Y;

        if (visible) {
            offscreenIndicator.setVisibility(View.GONE);
            return;
        }

        offscreenIndicator.setVisibility(View.VISIBLE);

        float ndcX, ndcY;
        if (zCam > 0.1f) {
            ndcX = xCam / (zCam * tanX);
            ndcY = yCam / (zCam * TAN_HALF_FOV_Y);
        } else {
            ndcX = -xCam;
            ndcY = -yCam;
            float m = Math.max(Math.abs(ndcX), Math.abs(ndcY));
            if (m > 0) { ndcX /= m; ndcY /= m; }
        }

        if (Math.abs(ndcX) > 1f || Math.abs(ndcY) > 1f) {
            float s = Math.min(1f / Math.abs(ndcX), 1f / Math.abs(ndcY));
            ndcX *= s;
            ndcY *= s;
        }

        int w = getView() != null ? getView().getWidth() : 0;
        int h = getView() != null ? getView().getHeight() : 0;
        if (w == 0 || h == 0) return;

        float sx = w * (0.5f + 0.5f * ndcX);
        float sy = h * (0.5f - 0.5f * ndcY);

        int iw = offscreenIndicator.getWidth();
        int ih = offscreenIndicator.getHeight();
        if (iw == 0) iw = 48;
        if (ih == 0) ih = 48;

        offscreenIndicator.setX(sx - iw / 2f);
        offscreenIndicator.setY(sy - ih / 2f);

        float angle = (float) Math.toDegrees(Math.atan2(-ndcY, ndcX));
        offscreenIndicator.setRotation(angle);
    }

    // ===================== Lifecycle =====================

    @Override
    public void onResume() {
        super.onResume();
        if (glView != null) glView.onResume();
        if (arRenderer != null) arRenderer.onResume();
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onPause() {
        super.onPause();
        lastFrameNanos = 0;
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (glView != null) glView.onPause();
        if (arRenderer != null) arRenderer.onPause();
        sensorManager.unregisterListener(this);
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bullets.clear();
        if (choreographer != null) {
            choreographer.removeFrameCallback(frameCallback);
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
            cameraExecutor = null;
        }
        if (glView != null) {
            glView.onPause();
            glView.cleanup();
            glView = null;
        }
        if (arRenderer != null) {
            arRenderer.destroy();
            arRenderer = null;
        }
    }
}