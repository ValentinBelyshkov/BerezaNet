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
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
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
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.edgedetection.R;
import com.edgedetection.EdgeDetector;
import com.edgedetection.core.camera.CameraManager;
import com.edgedetection.core.camera.CameraSource;
import com.edgedetection.core.camera.ExternalCameraSource;
import com.edgedetection.core.camera.InternalCameraSource;
import com.edgedetection.domain.ballistics.Bullet;
import com.edgedetection.domain.ballistics.CalibrationPoint;
import com.edgedetection.domain.geo.GeoUtils;
import com.edgedetection.domain.mission.DronePosition;
import com.edgedetection.domain.mission.Mission;
import com.edgedetection.jni.VITTracker;
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

import org.json.JSONArray;
import org.json.JSONObject;
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
    private static final float TARGET_WIDTH_M = 2.0f;
    private static final float TARGET_LENGTH_M = 4.0f;
    private static final float T_FLIGHT_SEC = 2.0f;

    static {
        try {
            System.loadLibrary("opencv_java4");
            Log.i(TAG, "OpenCV loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "OpenCV failed: " + e.getMessage());
        }
    }

    // --- VIT Tracker ---
    private VITTracker vitTracker;
    private volatile float lastGyroX = 0f, lastGyroY = 0f, lastGyroZ = 0f;
    private volatile long lastGyroTimestampNs = 0;
    private Handler imuHandler;
    private Sensor gyroscopeSensor;
    private volatile VITTracker.TargetState lastTargetState = VITTracker.TargetState.EMPTY;

    // --- OpenCV / CameraX ---
    private BattleViewModel viewModel;
    private EdgeDetectionGLView glView;
    private PreviewView previewView;
    private ExecutorService cameraExecutor;
    private long lastFrameTime = 0;

    // --- Camera management ---
    private CameraManager cameraManager;
    private Button switchCameraButton;

    // --- AR overlay ---
    private SurfaceView arSurface;
    private Filament3DRenderer arRenderer;
    private ImageView offscreenIndicator;
    private TextView gpsWarning;

    // --- Bullet system ---
    private BulletTrajectoryView bulletOverlay;
    private ImageButton fireButton;
    private final List<Bullet> bullets = new ArrayList<>();

    // --- Calibration UI ---
    private Button calibrateButton;
    private ImageView calibrationMarker;
    private TextView vitInfoText;
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
    private final float[] remappedMatrix = new float[16];
    private final float[] orientation = new float[3];
    private float[] lastRotationVector = new float[4];
    private long lastLogTime = 0;

    // Calibration
    private boolean isCalibrating = false;
    private float calibSumX = 0f;
    private float calibSumY = 0f;
    private int calibCount = 0;
    private static final int CALIBRATION_SAMPLES = 30;
    private float initialAzimuth = 0f;
    private boolean calibrationDone = false;

    // Periodic compass recalibration every 5 seconds
    private Handler calibrationHandler;
    private static final long CALIBRATION_INTERVAL_MS = 5000;
    private final Runnable calibrationRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || isDetached()) return;
            Log.d(TAG, "Compass recalibration trigger (every 5s)");
            // Reset calibration state to start fresh recalibration
            isCalibrating = true;
            calibrationDone = false;
            calibSumX = 0f;
            calibSumY = 0f;
            calibCount = 0;
            // Schedule next recalibration
            calibrationHandler.postDelayed(this, CALIBRATION_INTERVAL_MS);
        }
    };

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

        // Инициализация VIT Tracker
        vitTracker = new VITTracker();
        boolean vitOk = vitTracker.init(requireContext(), TARGET_WIDTH_M, TARGET_LENGTH_M);
        Log.i(TAG, "VIT Tracker init: " + vitOk);

        // IMU HandlerThread
        HandlerThread imuThread = new HandlerThread("IMU");
        imuThread.start();
        imuHandler = new Handler(imuThread.getLooper());

        // Регистрируем гироскоп на фоновом потоке
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (gyroscopeSensor != null) {
            sensorManager.registerListener(this, gyroscopeSensor,
                    SensorManager.SENSOR_DELAY_GAME, imuHandler);
            Log.i(TAG, "Gyroscope registered on IMU thread");
        } else {
            Log.w(TAG, "Gyroscope not available on this device");
        }

        // Также регистрируем rotation vector на фоновом потоке
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor,
                    SensorManager.SENSOR_DELAY_GAME, imuHandler);
        }
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

        // Calibration UI
        calibrateButton = view.findViewById(R.id.calibrate_button);
        calibrationMarker = view.findViewById(R.id.calibration_marker);
        vitInfoText = view.findViewById(R.id.vit_info);

        calibrateButton.setOnClickListener(v -> handleCalibrateClick());

        // Camera switch button
        switchCameraButton = view.findViewById(R.id.switch_camera_button);
        switchCameraButton.setOnClickListener(v -> {
            if (cameraManager != null) {
                cameraManager.toggleSource();
                Toast.makeText(requireContext(), "Переключение камеры...", Toast.LENGTH_SHORT).show();
            }
        });

        // Тап по экрану для установки точки калибровки
        bulletOverlay.setOnTouchListener((v, event) -> {
            if (Boolean.TRUE.equals(viewModel.isCalibrationActive().getValue())) {
                float nx = event.getX() / v.getWidth();
                float ny = event.getY() / v.getHeight();
                viewModel.setCalibrationPosition(nx, ny);
                updateCalibrationMarker(nx, ny);
                return true;
            }
            return false;
        });

        // Наблюдаем состояние калибровки
        viewModel.isCalibrationActive().observe(getViewLifecycleOwner(), active -> {
            if (Boolean.TRUE.equals(active)) {
                calibrateButton.setText("Подтвердить");
                Toast.makeText(requireContext(), "Нажми на экран где был выстрел", Toast.LENGTH_LONG).show();
            } else {
                CalibrationPoint cp = viewModel.getCalibrationPoint().getValue();
                if (cp == null || !cp.confirmed) {
                    calibrateButton.setText("Калибровка");
                    calibrationMarker.setVisibility(View.GONE);
                }
            }
        });

        viewModel.getCalibrationPoint().observe(getViewLifecycleOwner(), cp -> {
            if (cp != null && cp.confirmed) {
                calibrateButton.setText("Калибровка");
                Toast.makeText(requireContext(),
                        "Калибровка сохранена: (" + String.format("%.2f", cp.x) + ", " + String.format("%.2f", cp.y) + ")",
                        Toast.LENGTH_SHORT).show();
            }
        });

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

    // ===================== Camera Source Handling =====================

    private void startCamera() {
        if (cameraExecutor == null) {
            cameraExecutor = Executors.newSingleThreadExecutor();
        }

        if (cameraManager == null) {
            CameraSource internal = new InternalCameraSource(requireContext(), getViewLifecycleOwner(), previewView, cameraExecutor);
            CameraSource external = new ExternalCameraSource();
            cameraManager = new CameraManager(internal, external);

            cameraManager.getCurrentSource().observe(getViewLifecycleOwner(), source -> {
                if (source != null) {
                    source.start(this::processFrame);
                }
            });
        } else {
            CameraSource current = cameraManager.getCurrentSource().getValue();
            if (current != null && !current.isRunning()) {
                current.start(this::processFrame);
            }
        }
    }

    private void processFrame(ImageProxy image) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();

            long frameTimestampNs = image.getImageInfo().getTimestamp();

            // Get RGBA data as ByteBuffer for VIT tracker
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            int ySize = yBuffer.remaining();

            // Convert YUV_420_888 to RGBA using OpenCV
            byte[] yuvBytes = new byte[image.getPlanes()[0].getBuffer().remaining()
                    + image.getPlanes()[1].getBuffer().remaining()
                    + image.getPlanes()[2].getBuffer().remaining()];

            int offset = 0;
            for (int i = 0; i < 3; i++) {
                ByteBuffer buffer = image.getPlanes()[i].getBuffer();
                byte[] planeData = new byte[buffer.remaining()];
                buffer.get(planeData);
                System.arraycopy(planeData, 0, yuvBytes, offset, planeData.length);
                offset += planeData.length;
            }

            Mat yuvMat = new Mat(height + height / 2, width, CvType.CV_8UC1);
            yuvMat.put(0, 0, yuvBytes);

            Mat rgba = new Mat(height, width, CvType.CV_8UC4);
            org.opencv.imgproc.Imgproc.cvtColor(yuvMat, rgba, org.opencv.imgproc.Imgproc.COLOR_YUV2RGBA_NV21);

            yuvMat.release();

            // ======== VIT Tracker processing ========
            if (vitTracker != null && VITTracker.isLibraryLoaded() && lastGyroTimestampNs > 0) {
                int bufSize = rgba.width() * rgba.height() * 4;
                ByteBuffer rgbaBuffer = ByteBuffer.allocateDirect(bufSize);
                byte[] rgbaBytes = new byte[bufSize];
                rgba.get(0, 0, rgbaBytes);
                rgbaBuffer.put(rgbaBytes);
                rgbaBuffer.position(0);

                lastTargetState = vitTracker.processFrame(
                        rgbaBuffer,
                        rgba.width(), rgba.height(),
                        frameTimestampNs,
                        lastGyroX, lastGyroY, lastGyroZ, lastGyroTimestampNs,
                        T_FLIGHT_SEC
                );
            }

            // ======== Edge detection with reticle ========
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
                VITTracker.TargetState ts = lastTargetState;
                EdgeDetector.detectEdgesWithReticle(
                        rgba.getNativeObjAddr(),
                        edges.getNativeObjAddr(),
                        50, 150, 5,
                        ts.detected ? Math.round(ts.bboxX + ts.bboxW / 2f) : 0,
                        ts.detected ? Math.round(ts.bboxY + ts.bboxH / 2f) : 0,
                        ts.detected,
                        false
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
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            // Store latest gyro sample for VIT tracker
            lastGyroX = event.values[0];
            lastGyroY = event.values[1];
            lastGyroZ = event.values[2];
            lastGyroTimestampNs = event.timestamp;
            return;
        }

        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        System.arraycopy(event.values, 0, lastRotationVector, 0, Math.min(event.values.length, lastRotationVector.length));
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);

        // Расчет азимута для калибровки
        SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remappedMatrix);
        SensorManager.getOrientation(remappedMatrix, orientation);
        float azimuth = orientation[0];

        if (isCalibrating) {
            calibSumX += (float) Math.cos(azimuth);
            calibSumY += (float) Math.sin(azimuth);
            calibCount++;
            if (calibCount >= CALIBRATION_SAMPLES) {
                initialAzimuth = (float) Math.atan2(calibSumY / calibCount, calibSumX / calibCount);
                isCalibrating = false;
                calibrationDone = true;
                Log.d(TAG, "Compass calibrated: " + Math.toDegrees(initialAzimuth) + "°");
            }
        }

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

    // ===================== Calibration =====================

    private void handleCalibrateClick() {
        Boolean active = viewModel.isCalibrationActive().getValue();
        CalibrationPoint current = viewModel.getCalibrationPoint().getValue();

        if (Boolean.TRUE.equals(active)) {
            // Режим калибровки — нажатие на "Подтвердить"
            if (current != null && !current.confirmed) {
                viewModel.confirmCalibration();
                calibrationMarker.setVisibility(View.VISIBLE);
                updateCalibrationMarker(current.x, current.y);
            } else {
                // Точка не выбрана — отменяем калибровку
                viewModel.cancelCalibration();
                calibrateButton.setText("Калибровка");
                calibrationMarker.setVisibility(View.GONE);
            }
        } else if (current != null && current.confirmed) {
            // Уже есть подтверждённая калибровка — сбрасываем
            viewModel.cancelCalibration();
            calibrateButton.setText("Калибровка");
            calibrationMarker.setVisibility(View.GONE);
        } else {
            // Начинаем калибровку
            viewModel.startCalibration();
        }
    }

    private void updateCalibrationMarker(float nx, float ny) {
        if (calibrationMarker == null || getView() == null) return;
        int w = getView().getWidth();
        int h = getView().getHeight();
        if (w == 0 || h == 0) return;

        float px = nx * w - calibrationMarker.getWidth() / 2f;
        float py = ny * h - calibrationMarker.getHeight() / 2f;

        calibrationMarker.setX(px);
        calibrationMarker.setY(py);
        calibrationMarker.setVisibility(View.VISIBLE);
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

        if (!calibrationDone && isCalibrating) return;

        // 1. Камера из RotationVector
        float[] landscapeMatrix = new float[16];
        // Для Landscape (Landscape Left, 90 deg CCW):
        // New X = Old Y, New Y = Old -X
        SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, landscapeMatrix);

        // В landscapeMatrix:
        // Столбец 0 (0,4,8) - Вправо (Screen Right) в мировых координатах
        // Столбец 1 (1,5,9) - Вверх (Screen Up) в мировых координатах
        // Столбец 2 (2,6,10) - Вперед (Out of screen) в мировых координатах

        // Камера (back) смотрит в -Z экрана
        float fBaseE = -landscapeMatrix[2];
        float fBaseN = -landscapeMatrix[6];
        float fBaseU = -landscapeMatrix[10];

        // Вектор Up для камеры - это Y экрана
        float uBaseE = landscapeMatrix[1];
        float uBaseN = landscapeMatrix[5];
        float uBaseU = landscapeMatrix[9];

        // Векторы для Filament: [E, U, -N]
        float fx = fBaseE; float fy = fBaseU; float fz = -fBaseN;
        float ux = uBaseE; float uy = uBaseU; float uz = -uBaseN;

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

        Log.d(TAG, String.format("Camera: F=[%.2f, %.2f, %.2f] U=[%.2f, %.2f, %.2f] Az=%.1f° InitAz=%.1f°",
                fx, fy, fz, ux, uy, uz, Math.toDegrees(orientation[0]), Math.toDegrees(initialAzimuth)));

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

            Log.d(TAG, String.format("GPS User: %.6f, %.6f, %.1f | Drone: %.6f, %.6f, %.1f",
                    refLat, refLon, refAlt, droneLat, droneLon, droneAlt));
            Log.d(TAG, String.format("ENU: E=%.2f N=%.2f U=%.2f | Filament: X=%.2f Y=%.2f Z=%.2f",
                    enu[0], enu[1], enu[2], lastDroneX, lastDroneY, lastDroneZ));

            long now = System.currentTimeMillis();
            if (now - lastLogTime > 1000) {
                lastLogTime = now;
                // We'll calculate visibility here or use the one from updateOffscreenIndicator if we move it
                boolean visible = isDroneVisible(fx, fy, fz, ux, uy, uz);
                logState(fx, fy, fz, ux, uy, uz, refLat, refLon, refAlt, enu, lastDroneX, lastDroneY, lastDroneZ, visible);
            }

            arRenderer.setModelVisible(true);
            arRenderer.setDronePosition(lastDroneX, lastDroneY, lastDroneZ,
                    (float) Math.toRadians(droneHeading) + (float) Math.PI);
        } else if (canLocate && hasRelativePosition) {
            arRenderer.setModelVisible(true);
            arRenderer.setDronePosition(lastDroneX, lastDroneY, lastDroneZ,
                    (float) Math.toRadians(droneHeading) + (float) Math.PI);
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

        // 6. VIT Tracker info
        updateVITInfo();
    }

    private boolean isDroneVisible(float fx, float fy, float fz, float ux, float uy, float uz) {
        if (!hasRelativePosition) return false;

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

        return zCam > 0.1f &&
                Math.abs(xCam) < zCam * tanX &&
                Math.abs(yCam) < zCam * TAN_HALF_FOV_Y;
    }

    private void logState(float fx, float fy, float fz, float ux, float uy, float uz,
                         double refLat, double refLon, double refAlt,
                         double[] enu, float lastDroneX, float lastDroneY, float lastDroneZ,
                         boolean droneVisible) {
        try {
            JSONObject root = new JSONObject();
            root.put("timestamp", System.currentTimeMillis() / 1000);

            JSONObject user = new JSONObject();
            JSONObject userGps = new JSONObject();
            userGps.put("lat", refLat);
            userGps.put("lon", refLon);
            userGps.put("alt", refAlt);
            user.put("gps", userGps);

            JSONObject sensors = new JSONObject();
            JSONArray rv = new JSONArray();
            for (float v : lastRotationVector) rv.put(v);
            sensors.put("rotationVector", rv);
            
            float[] standardOrientation = new float[3];
            SensorManager.getOrientation(rotationMatrix, standardOrientation);
            sensors.put("azimuth", Math.toDegrees(standardOrientation[0]));
            sensors.put("pitch", Math.toDegrees(standardOrientation[1]));
            sensors.put("roll", Math.toDegrees(standardOrientation[2]));
            user.put("sensors", sensors);
            root.put("user", user);

            JSONObject drone = new JSONObject();
            JSONObject droneGps = new JSONObject();
            droneGps.put("lat", droneLat);
            droneGps.put("lon", droneLon);
            droneGps.put("alt", droneAlt);
            drone.put("gps", droneGps);
            root.put("drone", drone);

            JSONObject enuObj = new JSONObject();
            JSONObject origin = new JSONObject();
            origin.put("lat", refLat);
            origin.put("lon", refLon);
            enuObj.put("origin", origin);
            JSONObject droneEnu = new JSONObject();
            droneEnu.put("E", enu[0]);
            droneEnu.put("N", enu[1]);
            droneEnu.put("U", enu[2]);
            enuObj.put("drone", droneEnu);
            root.put("enu", enuObj);

            JSONObject engine = new JSONObject();
            engine.put("convention", "Y-up, right-handed");
            JSONObject dronePos = new JSONObject();
            dronePos.put("x", lastDroneX);
            dronePos.put("y", lastDroneY);
            dronePos.put("z", lastDroneZ);
            engine.put("dronePos", dronePos);

            JSONObject camera = new JSONObject();
            JSONObject camPos = new JSONObject();
            camPos.put("x", 0);
            camPos.put("y", EYE_HEIGHT);
            camPos.put("z", 0);
            camera.put("pos", camPos);

            JSONObject forward = new JSONObject();
            forward.put("x", fx);
            forward.put("y", fy);
            forward.put("z", fz);
            camera.put("forward", forward);

            JSONObject up = new JSONObject();
            up.put("x", ux);
            up.put("y", uy);
            up.put("z", uz);
            camera.put("up", up);

            // Right vector: forward x up
            float rx = fy * uz - fz * uy;
            float ry = fz * ux - fx * uz;
            float rz = fx * uy - fy * ux;
            JSONObject right = new JSONObject();
            right.put("x", rx);
            right.put("y", ry);
            right.put("z", rz);
            camera.put("right", right);
            engine.put("camera", camera);

            double[] viewMat = new double[16];
            double[] projMat = new double[16];
            arRenderer.getCamera().getViewMatrix(viewMat);
            arRenderer.getCamera().getProjectionMatrix(projMat);
            JSONArray viewMatArr = new JSONArray();
            for (double v : viewMat) viewMatArr.put(v);
            engine.put("viewMatrix", viewMatArr);
            JSONArray projMatArr = new JSONArray();
            for (double v : projMat) projMatArr.put(v);
            engine.put("projectionMatrix", projMatArr);

            float[] modelMat = arRenderer.getDroneModelMatrix();
            JSONArray modelMatArr = new JSONArray();
            for (float v : modelMat) modelMatArr.put(v);
            engine.put("modelMatrixDrone", modelMatArr);
            root.put("engine", engine);

            JSONObject screen = new JSONObject();
            // Calculate NDC
            float[] v_world = {lastDroneX, lastDroneY, lastDroneZ, 1.0f};
            float[] v_view = new float[4];
            for (int i = 0; i < 4; i++) {
                v_view[i] = 0;
                for (int j = 0; j < 4; j++) {
                    v_view[i] += (float)viewMat[j * 4 + i] * v_world[j];
                }
            }
            float[] v_clip = new float[4];
            for (int i = 0; i < 4; i++) {
                v_clip[i] = 0;
                for (int j = 0; j < 4; j++) {
                    v_clip[i] += (float)projMat[j * 4 + i] * v_view[j];
                }
            }
            JSONObject ndc = new JSONObject();
            if (v_clip[3] != 0) {
                ndc.put("x", v_clip[0] / v_clip[3]);
                ndc.put("y", v_clip[1] / v_clip[3]);
                ndc.put("z", v_clip[2] / v_clip[3]);
            } else {
                ndc.put("x", 0); ndc.put("y", 0); ndc.put("z", 0);
            }
            screen.put("droneNDC", ndc);

            Viewport vp = arRenderer.getViewport();
            JSONObject pixel = new JSONObject();
            if (v_clip[3] != 0) {
                float nx = v_clip[0] / v_clip[3];
                float ny = v_clip[1] / v_clip[3];
                pixel.put("x", (nx * 0.5f + 0.5f) * vp.width);
                pixel.put("y", (1.0f - (ny * 0.5f + 0.5f)) * vp.height);
            } else {
                pixel.put("x", 0); pixel.put("y", 0);
            }
            screen.put("dronePixel", pixel);
            screen.put("visible", droneVisible);
            root.put("screen", screen);

            Log.i("DRONE_STATE_JSON", root.toString(2));

        } catch (Exception e) {
            Log.e(TAG, "Error logging state", e);
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

    /**
     * Отображает информацию VIT трекера
     */
    private void updateVITInfo() {
        if (vitInfoText == null) return;

        VITTracker.TargetState ts = lastTargetState;
        if (ts == null || !ts.detected) {
            vitInfoText.setText("VIT: поиск...");
            return;
        }

        String info = String.format(
            "VIT: %s | D=%.0fм | V=(%.1f,%.1f,%.1f)м/с\nAz=%.1f° El=%.1f° | C=%.2f",
            ts.tracking ? "ТРЕКИНГ" : "DETECT",
            ts.distanceM,
            ts.velX, ts.velY, ts.velZ,
            ts.azimuthDeg, ts.elevationDeg,
            ts.confidence
        );

        if (ts.tracking) {
            info += String.format("\nLead: (%.1f,%.1f,%.1f)м", ts.leadX, ts.leadY, ts.leadZ);
        }

        vitInfoText.setText(info);
    }

    // ===================== Lifecycle =====================

    @Override
    public void onResume() {
        super.onResume();
        if (glView != null) glView.onResume();
        if (arRenderer != null) arRenderer.onResume();
        if (rotationVectorSensor != null) {
            // Стартуем калибровку
            isCalibrating = true;
            calibrationDone = false;
            calibSumX = 0f;
            calibSumY = 0f;
            calibCount = 0;
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME, imuHandler);
        }
        // Запускаем периодическую перекалибровку компаса каждые 5 секунд
        if (calibrationHandler == null) {
            calibrationHandler = new Handler(Looper.getMainLooper());
        }
        calibrationHandler.removeCallbacks(calibrationRunnable);
        calibrationHandler.postDelayed(calibrationRunnable, CALIBRATION_INTERVAL_MS);
        // Перерегистрируем гироскоп
        if (gyroscopeSensor != null) {
            sensorManager.registerListener(this, gyroscopeSensor,
                    SensorManager.SENSOR_DELAY_GAME, imuHandler);
        }
        if (vitTracker != null) {
            vitTracker.reset();
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
        if (cameraManager != null && cameraManager.getCurrentSource().getValue() != null) {
            cameraManager.getCurrentSource().getValue().stop();
        }
        if (glView != null) glView.onPause();
        if (arRenderer != null) arRenderer.onPause();
        sensorManager.unregisterListener(this);
        // Останавливаем периодическую перекалибровку компаса
        if (calibrationHandler != null) {
            calibrationHandler.removeCallbacks(calibrationRunnable);
        }
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        // Сброс VIT при паузе (очищаем IMU буфер, но не удаляем калибровку)
        if (vitTracker != null) {
            vitTracker.reset();
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
        if (cameraManager != null && cameraManager.getCurrentSource().getValue() != null) {
            cameraManager.getCurrentSource().getValue().stop();
            cameraManager = null;
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
        // Освобождение VIT
        if (vitTracker != null) {
            vitTracker.release();
            vitTracker = null;
        }
        if (imuHandler != null) {
            imuHandler.getLooper().quit();
            imuHandler = null;
        }
    }
}