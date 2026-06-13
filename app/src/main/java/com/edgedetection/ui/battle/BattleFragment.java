package com.edgedetection.ui.battle;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.LayoutInflater;

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
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.edgedetection.R;
import com.edgedetection.EdgeDetector;
import com.edgedetection.core.camera.CameraManager;
import com.edgedetection.core.camera.CameraSource;
import com.edgedetection.core.camera.InternalCameraSource;
import com.edgedetection.core.camera.RtspCameraSource;
import com.alexvas.rtsp.widget.RtspSurfaceView;
import com.edgedetection.domain.ballistics.CalibrationPoint;
import com.edgedetection.domain.mission.Mission;
import com.edgedetection.jni.VITTracker;
import com.edgedetection.opengl.EdgeDetectionGLView;
import com.edgedetection.ui.battle.components.BattleBallisticsManager;
import com.edgedetection.ui.battle.components.BattleFrameProcessor;
import com.edgedetection.ui.battle.components.BattleLocationManager;
import com.edgedetection.ui.battle.components.BattleLogger;
import com.edgedetection.ui.battle.components.BattleSceneRenderer;
import com.edgedetection.ui.battle.components.BattleSensorProvider;
import com.edgedetection.ui.battle.components.BattleSoundManager;
import com.edgedetection.ui.battle.ThermalOverlayView;
import com.edgedetection.ui.shared.MissionIntent;
import com.edgedetection.ui.shared.MissionViewModel;
import com.google.android.filament.Viewport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BattleFragment extends Fragment {
    private static final String TAG = "BattleFragment";
    private static final int AR_PERMISSION_REQUEST = 3;
    private static final float TARGET_WIDTH_M = 3.09f;
    private static final float TARGET_LENGTH_M = 2.86f;
    private static final long CALIBRATION_INTERVAL_MS = 5000;
    private static final float LEAD_TIME_SEC = 2.0f;

    // --- Components ---
    private BattleSensorProvider sensorProvider;
    private BattleLocationManager locationManager;
    private BattleBallisticsManager ballisticsManager;
    private BattleSceneRenderer sceneRenderer;
    private BattleFrameProcessor frameProcessor;
    private BattleSoundManager soundManager;

    // --- State ---
    private BattleViewModel viewModel;
    private MissionViewModel missionVm;
    private CameraManager cameraManager;
    private ExecutorService cameraExecutor;
    private Handler imuHandler;
    private Handler calibrationHandler;
    
    private volatile float lastGyroX = 0f, lastGyroY = 0f, lastGyroZ = 0f;
    private volatile long lastGyroTimestampNs = 0;
    private volatile float lastPitch = 0f, lastYaw = 0f, lastRoll = 0f;
    private long lastLogTime = 0;

    private static final String RTSP_URL = "rtsp://192.168.42.1:8554/video";

    // --- Views ---
    private EdgeDetectionGLView glView;
    private PreviewView         previewView;
    private RtspSurfaceView     rtspSurfaceView;

    private BulletTrajectoryView bulletOverlay;
    private ThermalOverlayView thermalOverlay;
    private Button thermalButton;
    private boolean thermalActive = false;
    private ImageView calibrationMarker;
    private TextView vitInfoText;
    private TextView rtspStatusView;
    private Button calibrateButton;
    private Button toggleEdgesButton;
    private Button compassCubesButton;
    private CompassCubeOverlay compassCubeOverlay;
    private boolean compassCubesVisible = false;
    private Button simulationButton;

    // --- Drone velocity for lead point ---
    private float prevDroneFilX, prevDroneFilY, prevDroneFilZ;
    private float droneVelFilX = 0, droneVelFilY = 0, droneVelFilZ = 0;
    private long prevDroneFilTimeMs = 0;
    private boolean hasPrevDroneFilPos = false;
    private TextView gpsWarning;

    // --- Simulation state ---
    private volatile double droneLat, droneLon, droneAlt;
    private volatile float droneHeading = 0f;
    private volatile boolean simulationActive = false;
    private volatile boolean simulationPaused = false;
    private volatile boolean hasDronePosition = false;
    private volatile int currentDroneIndex = -1;
    private double missionOriginLat, missionOriginLon, missionOriginAlt;
    private boolean hasMissionOrigin = false;

    // --- Own simulation loop ---
    private Handler simHandler;
    private boolean isSimRunning = false;
    private long lastSimFrameTime = 0;
    private final List<SimDrone> simDrones = new ArrayList<>();
    private java.util.List<com.edgedetection.domain.mission.Waypoint> mCachedWaypoints = null;

    static class SimDrone {
        int index;
        double distanceMeters;
        boolean visible = false;
        boolean active = true;
        int lives;
        double lat, lon, alt;
        double prevLat, prevLon;
        double bearing = 0;
    }

    private Choreographer choreographer;
    private long lastFrameNanos = 0;
    private boolean frameScheduled = false;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (isDetached() || !isAdded()) return;
            float dt = (lastFrameNanos == 0) ? 0.016f : (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
            lastFrameNanos = frameTimeNanos;
            updateScene(dt);
        }
    };

    private final Runnable calibrationRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || isDetached()) return;
            if (sensorProvider != null) {
                sensorProvider.triggerRecalibration();
            }
            calibrationHandler.postDelayed(this, CALIBRATION_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(this).get(BattleViewModel.class);
        missionVm = new ViewModelProvider(requireActivity()).get(MissionViewModel.class);
        choreographer = Choreographer.getInstance();

        HandlerThread imuThread = new HandlerThread("IMU");
        imuThread.start();
        imuHandler = new Handler(imuThread.getLooper());

        sensorProvider = new BattleSensorProvider(requireContext(), imuHandler, new BattleSensorProvider.OnSensorChangedListener() {
            @Override
            public void onRotationMatrixUpdated(float[] rotationMatrix, float[] orientation, float initialAzimuth) {
                lastYaw = (float) Math.toDegrees(orientation[0]);
                lastPitch = (float) Math.toDegrees(orientation[1]);
                lastRoll = (float) Math.toDegrees(orientation[2]);
                scheduleFrame();
            }

            @Override
            public void onGyroscopeUpdated(float x, float y, float z, long timestampNs) {
                lastGyroX = x; lastGyroY = y; lastGyroZ = z;
                lastGyroTimestampNs = timestampNs;
            }
        });

        locationManager = new BattleLocationManager(requireContext(), (lat, lon, alt) -> {
            if (gpsWarning != null) gpsWarning.setVisibility(View.GONE);
        });

        ballisticsManager = new BattleBallisticsManager(requireContext(), requireActivity());
        soundManager = new BattleSoundManager();
        ballisticsManager.setOnHitCallback(() -> soundManager.onHit());
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

        initViews(view);
        setupObservers();

        sceneRenderer = new BattleSceneRenderer(requireContext(), 
                view.findViewById(R.id.ar_overlay), 
                view.findViewById(R.id.offscreen_indicator), 
                view.findViewById(R.id.gps_warning));
        
        frameProcessor = new BattleFrameProcessor(requireContext(), viewModel, glView, TARGET_WIDTH_M, TARGET_LENGTH_M);

        checkPermissions();
    }

    private void initViews(View view) {
        previewView     = view.findViewById(R.id.preview_view);
        rtspSurfaceView = view.findViewById(R.id.rtsp_surface_view);
        previewView.setVisibility(View.INVISIBLE);

        glView = view.findViewById(R.id.camera_view);
        gpsWarning = view.findViewById(R.id.gps_warning);
        bulletOverlay = view.findViewById(R.id.bullet_overlay);
        thermalOverlay = view.findViewById(R.id.thermal_overlay);
        thermalButton = view.findViewById(R.id.thermal_button);
        calibrationMarker = view.findViewById(R.id.calibration_marker);
        vitInfoText = view.findViewById(R.id.vit_info);
        calibrateButton = view.findViewById(R.id.calibrate_button);
        toggleEdgesButton = view.findViewById(R.id.toggle_edges_button);
        compassCubesButton = view.findViewById(R.id.compass_cubes_button);
        simulationButton = view.findViewById(R.id.simulation_button);
        compassCubeOverlay = view.findViewById(R.id.compass_cube_overlay);
        rtspStatusView = view.findViewById(R.id.rtsp_status);

        simulationButton.setOnClickListener(v -> {
            Mission currentMission = missionVm.getMissionState().getValue();
            Mission.SimulationState state = currentMission != null ? currentMission.simState : Mission.SimulationState.IDLE;
            if (state == Mission.SimulationState.RUNNING) {
                stopSimulationLoop();
                missionVm.dispatch(new MissionIntent.StopSimulation());
            } else {
                if (currentMission == null || currentMission.waypoints.size() < 2) {
                    Toast.makeText(requireContext(), "Нет маршрута (добавьте точки в Планировщике)", Toast.LENGTH_SHORT).show();
                    return;
                }
                initBattleSimDrones(currentMission);
                missionVm.dispatch(new MissionIntent.StartSimulation());
                startSimulationLoop();
            }
        });

        compassCubesButton.setOnClickListener(v -> {
            compassCubesVisible = !compassCubesVisible;
            compassCubeOverlay.setVisibility(compassCubesVisible ? View.VISIBLE : View.GONE);
            compassCubesButton.setText(compassCubesVisible ? "Скрыть стороны" : "Стороны света");
        });

        thermalButton.setOnClickListener(v -> {
            thermalActive = !thermalActive;
            thermalOverlay.setVisibility(thermalActive ? View.VISIBLE : View.GONE);
            thermalButton.setBackgroundColor(thermalActive ? 0xCC004400 : 0xCC001A00);
            thermalButton.setText(thermalActive ? "Тепловизор: ВКЛ" : "Тепловизор");
        });

        view.findViewById(R.id.fire_button).setOnClickListener(v -> {
            ballisticsManager.fireBullet(camForwardX, camForwardY, camForwardZ);
        });

        view.findViewById(R.id.switch_camera_button).setOnClickListener(v -> {
            if (cameraManager != null) {
                cameraManager.toggleSource();
                CameraSource current = cameraManager.getCurrentSource().getValue();
                boolean isRtsp = current instanceof RtspCameraSource;
                Toast.makeText(requireContext(),
                        isRtsp ? "Камера: RTSP" : "Камера: встроенная",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), "Камера не инициализирована", Toast.LENGTH_SHORT).show();
            }
        });

        toggleEdgesButton.setOnClickListener(v -> {
            Boolean current = viewModel.isEdgeDetectionEnabled().getValue();
            if (current == null) current = false;
            viewModel.setEdgeDetectionEnabled(!current);
        });


        calibrateButton.setOnClickListener(v -> handleCalibrateClick());

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
    }

    private void setupObservers() {
        viewModel.isEdgeDetectionEnabled().observe(getViewLifecycleOwner(), enabled -> {
            if (Boolean.TRUE.equals(enabled)) {
                toggleEdgesButton.setText("Обработка: Вкл");
            } else {
                toggleEdgesButton.setText("Обработка: Выкл");
            }
        });

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

        missionVm.getMissionState().observe(getViewLifecycleOwner(), mission -> {
            if (mission != null && simulationButton != null) {
                switch (mission.simState) {
                    case RUNNING:
                        simulationButton.setText("Стоп симуляции");
                        break;
                    default:
                        simulationButton.setText("Старт симуляции");
                        break;
                }
            }
        });
        missionVm.getMissionState().observe(getViewLifecycleOwner(), mission -> {
            if (mission == null) return;
            if (mission.originLatitude != null && mission.originLongitude != null) {
                double alt = mission.originAltitudeAmsl != null ? mission.originAltitudeAmsl : 0.0;
                setMissionOrigin(mission.originLatitude, mission.originLongitude, alt);
            }
            onSimulationPaused(mission.simState == Mission.SimulationState.PAUSED);
            this.simulationActive = (mission.simState != Mission.SimulationState.IDLE);
            if (!this.simulationActive) {
                sceneRenderer.setHasRelativePosition(false);
            }
            if (soundManager != null) soundManager.setEnabled(mission.soundEnabled);
            mCachedWaypoints = mission.waypoints.size() >= 2 ? mission.waypoints : null;
        });

        missionVm.getDronePosition().observe(getViewLifecycleOwner(), pos -> {
            if (pos != null && pos.active) {
                this.currentDroneIndex = pos.index;
                updateDronePosition(pos.lat, pos.lon, pos.alt, pos.heading);
            }
        });

        missionVm.getTargetSize().observe(getViewLifecycleOwner(), sizeM -> {
            if (sizeM == null) return;
            if (thermalOverlay != null) thermalOverlay.setTargetSizeM(sizeM);
            if (sceneRenderer != null) sceneRenderer.setModelScale(sizeM);
            if (ballisticsManager != null) ballisticsManager.setTargetRadiusM(sizeM / 2f);
        });

        missionVm.getBulletDiameter().observe(getViewLifecycleOwner(), diamM -> {
            if (diamM == null) return;
            if (ballisticsManager != null) ballisticsManager.setBulletDiameterM(diamM);
        });
    }

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
            locationManager.startLocationUpdates();
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
            if (loc) locationManager.startLocationUpdates();
        }
    }

    private void startCamera() {
        if (cameraExecutor == null) {
            cameraExecutor = Executors.newSingleThreadExecutor();
        }

        if (cameraManager == null) {
            CameraSource internal = new InternalCameraSource(requireContext(), getViewLifecycleOwner(), previewView, cameraExecutor);

            RtspCameraSource rtspSource = new RtspCameraSource(rtspSurfaceView, RTSP_URL);
            rtspSource.setStatusListener((status, attempt) -> updateRtspStatus(status, attempt));

            cameraManager = new CameraManager(internal, rtspSource);

            cameraManager.getCurrentSource().observe(getViewLifecycleOwner(), source -> {
                boolean isRtsp = source instanceof RtspCameraSource;
                if (rtspStatusView != null) {
                    rtspStatusView.setVisibility(isRtsp ? View.VISIBLE : View.GONE);
                }
                updateRtspViewVisibility(isRtsp);
            });

            CameraSource.CameraSourceListener listener = new CameraSource.CameraSourceListener() {
                @Override
                public void onFrame(androidx.camera.core.ImageProxy image) {
                    frameProcessor.processFrame(image, lastGyroX, lastGyroY, lastGyroZ, lastGyroTimestampNs, lastPitch, lastYaw, lastRoll);
                }

                @Override
                public void onFrameMat(org.opencv.core.Mat rgba, long timestampNs) {
                    frameProcessor.processFrameMat(rgba, timestampNs, lastGyroX, lastGyroY, lastGyroZ, lastGyroTimestampNs, lastPitch, lastYaw, lastRoll);
                }
            };

            cameraManager.getCurrentSource().observe(getViewLifecycleOwner(), source -> {
                if (source != null) {
                    source.start(listener);
                }
            });
        } else {
            CameraSource current = cameraManager.getCurrentSource().getValue();
            if (current != null && !current.isRunning()) {
                CameraSource.CameraSourceListener listener = new CameraSource.CameraSourceListener() {
                    @Override
                    public void onFrame(androidx.camera.core.ImageProxy image) {
                        frameProcessor.processFrame(image, lastGyroX, lastGyroY, lastGyroZ, lastGyroTimestampNs, lastPitch, lastYaw, lastRoll);
                    }

                    @Override
                    public void onFrameMat(org.opencv.core.Mat rgba, long timestampNs) {
                        frameProcessor.processFrameMat(rgba, timestampNs, lastGyroX, lastGyroY, lastGyroZ, lastGyroTimestampNs, lastPitch, lastYaw, lastRoll);
                    }
                };
                current.start(listener);
            }
        }
    }

    private void updateRtspViewVisibility(boolean isRtsp) {
        // Frames from C++ decoder feed directly into glView via processFrameMat.
        // No TextureView or extra SurfaceView needed — glView stays visible always.
        if (glView != null) glView.setVisibility(View.VISIBLE);
    }

    private void updateRtspStatus(RtspCameraSource.Status status, int attempt) {
        if (rtspStatusView == null) return;
        switch (status) {
            case CONNECTING:
                rtspStatusView.setText("● RTSP: подключение...");
                rtspStatusView.setTextColor(0xFFFFAA00);
                break;
            case ONLINE:
                rtspStatusView.setText("● RTSP: онлайн");
                rtspStatusView.setTextColor(0xFF00FF44);
                break;
            case RECONNECTING:
                rtspStatusView.setText("● RTSP: попытка " + attempt + "...");
                rtspStatusView.setTextColor(0xFFFF4444);
                break;
        }
    }

    private float camForwardX, camForwardY, camForwardZ;

    private void updateScene(float dt) {
        frameScheduled = false;
        if (sceneRenderer == null) return;

        float[] afx = {0}, afy = {0}, afz = {0}, aux = {0}, auy = {0}, auz = {0};
        sensorProvider.getLandscapeVectors(afx, afy, afz, aux, auy, auz);
        float fx = afx[0], fy = afy[0], fz = afz[0], ux = aux[0], uy = auy[0], uz = auz[0];

        camForwardX = fx; camForwardY = fy; camForwardZ = fz;
        sceneRenderer.updateCamera(fx, fy, fz, ux, uy, uz);

        double refLat, refLon, refAlt;
        Mission currentMission = missionVm.getMissionState().getValue();
        boolean usingManualGps = currentMission != null
                && currentMission.useManualGps
                && currentMission.userLatitude != null
                && currentMission.userLongitude != null;

        if (usingManualGps) {
            refLat = currentMission.userLatitude;
            refLon = currentMission.userLongitude;
            refAlt = currentMission.userAltitudeAmsl != null ? currentMission.userAltitudeAmsl : 0.0;
            if (gpsWarning != null) {
                gpsWarning.setVisibility(View.VISIBLE);
                gpsWarning.setText("GPS: ручная точка");
            }
        } else if (locationManager.hasUserLocation()) {
            refLat = locationManager.getUserLat(); refLon = locationManager.getUserLon(); refAlt = locationManager.getUserAlt();
            if (gpsWarning != null) gpsWarning.setVisibility(View.GONE);
        } else if (hasMissionOrigin) {
            refLat = missionOriginLat; refLon = missionOriginLon; refAlt = missionOriginAlt;
            if (gpsWarning != null) {
                gpsWarning.setVisibility(View.VISIBLE);
                gpsWarning.setText("GPS недоступен — origin миссии");
            }
        } else {
            refLat = 0; refLon = 0; refAlt = 0;
        }

sceneRenderer.updateDronePosition(refLat, refLon, refAlt, droneLat, droneLon, droneAlt, droneHeading, simulationActive, hasDronePosition);
        // --- Drone velocity + lead point ---
        if (simulationActive && hasDronePosition && sceneRenderer.hasRelativePosition()) {
            float cx = sceneRenderer.getLastDroneX();
            float cy = sceneRenderer.getLastDroneY();
            float cz = sceneRenderer.getLastDroneZ();
            long nowMs = System.currentTimeMillis();
            if (!hasPrevDroneFilPos) {
                prevDroneFilX = cx; prevDroneFilY = cy; prevDroneFilZ = cz;
                prevDroneFilTimeMs = nowMs;
                hasPrevDroneFilPos = true;
            } else {
                float dtSec = (nowMs - prevDroneFilTimeMs) / 1000f;
                float ddx = cx - prevDroneFilX;
                float ddy = cy - prevDroneFilY;
                float ddz = cz - prevDroneFilZ;
                if (dtSec >= 0.05f && (ddx*ddx + ddy*ddy + ddz*ddz) > 1e-6f) {
                    droneVelFilX = ddx / dtSec;
                    droneVelFilY = ddy / dtSec;
                    droneVelFilZ = ddz / dtSec;
                    prevDroneFilX = cx; prevDroneFilY = cy; prevDroneFilZ = cz;
                    prevDroneFilTimeMs = nowMs;
                }
            }
            if (bulletOverlay != null) {
                bulletOverlay.setLeadPoint(
                    sceneRenderer.getLastDroneX() + droneVelFilX * LEAD_TIME_SEC,
                    sceneRenderer.getLastDroneY() + droneVelFilY * LEAD_TIME_SEC,
                    sceneRenderer.getLastDroneZ() + droneVelFilZ * LEAD_TIME_SEC,
                    true
                );
            }
        } else {
            hasPrevDroneFilPos = false;
            droneVelFilX = 0; droneVelFilY = 0; droneVelFilZ = 0;
            if (bulletOverlay != null) bulletOverlay.setLeadPoint(0, 0, 0, false);
        }

        // Sound proximity update
        if (soundManager != null) {
            if (sceneRenderer.hasRelativePosition() && hasDronePosition) {
                float sdx = sceneRenderer.getLastDroneX();
                float sdy = sceneRenderer.getLastDroneY() - 1.6f;
                float sdz = sceneRenderer.getLastDroneZ();
                float sDist = (float) Math.sqrt(sdx * sdx + sdy * sdy + sdz * sdz);
                soundManager.updateDistance(sDist);
            } else {
                soundManager.onDroneLost();
            }
        }

        // Thermal trajectory update (independent of drone visibility)
        if (thermalActive && thermalOverlay != null && mCachedWaypoints != null) {
            java.util.List<com.edgedetection.domain.mission.Waypoint> wps = mCachedWaypoints;
            float[][] trajPts = new float[wps.size()][];
            for (int i = 0; i < wps.size(); i++) {
                com.edgedetection.domain.mission.Waypoint wp = wps.get(i);
                double[] enu = com.edgedetection.domain.geo.GeoUtils.ecefToEnu(
                        refLat, refLon, refAlt, wp.latitude, wp.longitude, wp.altitudeAmsl);
                trajPts[i] = com.edgedetection.domain.geo.GeoUtils.enuToFilament(enu);
            }
            thermalOverlay.setTrajectory(trajPts);
        }

        // Thermal overlay update
        if (thermalActive && thermalOverlay != null && sceneRenderer.hasRelativePosition()) {
            float ldx = sceneRenderer.getLastDroneX();
            float ldy = sceneRenderer.getLastDroneY();
            float ldz = sceneRenderer.getLastDroneZ();
            float eddx = ldx, eddy = ldy - 1.6f, eddz = ldz;
            float currentDist = (float) Math.sqrt(eddx * eddx + eddy * eddy + eddz * eddz);
            float bestMiss = ballisticsManager.hasBestMiss() ? ballisticsManager.getBestMissDistance() : -1f;
            boolean lv = simulationActive && hasDronePosition;
            float tlx = lv ? ldx + droneVelFilX * LEAD_TIME_SEC : 0;
            float tly = lv ? ldy + droneVelFilY * LEAD_TIME_SEC : 0;
            float tlz = lv ? ldz + droneVelFilZ * LEAD_TIME_SEC : 0;
            thermalOverlay.update(fx, fy, fz, ux, uy, uz,
                    ldx, ldy, ldz, tlx, tly, tlz, lv,
                    currentDist, bestMiss);
        }

        long now = System.currentTimeMillis();
        if (now - lastLogTime > 1000 && sceneRenderer.hasRelativePosition()) {
            lastLogTime = now;
            double[] enu = com.edgedetection.domain.geo.GeoUtils.ecefToEnu(refLat, refLon, refAlt, droneLat, droneLon, droneAlt);
            double distM = Math.sqrt(enu[0]*enu[0] + enu[1]*enu[1] + enu[2]*enu[2]);
            BattleLogger.logState(fx, fy, fz, ux, uy, uz, refLat, refLon, refAlt, enu, distM,
                sceneRenderer.getLastDroneX(), sceneRenderer.getLastDroneY(), sceneRenderer.getLastDroneZ(),
                droneLat, droneLon, droneAlt, sensorProvider.getLastRotationVector(), sensorProvider.getRotationMatrix(),
                sceneRenderer.getArRenderer(), sceneRenderer.isDroneVisible(fx, fy, fz, ux, uy, uz),
                ballisticsManager.hasBestMiss() ? ballisticsManager.getBestMissDistance() : -1f);
        }

        if (dt > 0 && dt < 0.5f) {
            ballisticsManager.updateBullets(dt, simulationActive, sceneRenderer.getLastDroneX(), sceneRenderer.getLastDroneY(), sceneRenderer.getLastDroneZ(), sceneRenderer.getModelRadius(), currentDroneIndex);
        }

        sceneRenderer.updateOffscreenIndicator(fx, fy, fz, ux, uy, uz, getView());

        if (sceneRenderer.getArRenderer() != null &&
                (bulletOverlay != null || (compassCubeOverlay != null && compassCubesVisible))) {
            double[] viewMatDouble = new double[16];
            double[] projMatDouble = new double[16];
            sceneRenderer.getArRenderer().getCamera().getViewMatrix(viewMatDouble);
            sceneRenderer.getArRenderer().getCamera().getProjectionMatrix(projMatDouble);
            float[] viewMat = new float[16];
            float[] projMat = new float[16];
            for (int i = 0; i < 16; i++) {
                viewMat[i] = (float) viewMatDouble[i];
                projMat[i] = (float) projMatDouble[i];
            }
            Viewport vp = sceneRenderer.getArRenderer().getViewport();
            if (bulletOverlay != null) {
                bulletOverlay.setCameraMatrices(viewMat, projMat, vp.width, vp.height);
                bulletOverlay.setBullets(ballisticsManager.getBullets());
            }
            if (compassCubeOverlay != null && compassCubesVisible) {
                compassCubeOverlay.setCameraMatrices(viewMat, projMat, vp.width, vp.height);
            }
        }

        updateVITInfo();
    }

    private void updateVITInfo() {
        if (vitInfoText == null || frameProcessor == null) return;
        VITTracker.TargetState ts = frameProcessor.getLastTargetState();
        
        StringBuilder sb = new StringBuilder();
        sb.append("=== CAMERA ===\n");
        sb.append(String.format("Pitch: %7.2f (Rate: %6.1f deg/s)\n", lastPitch, Math.toDegrees(lastGyroX)));
        sb.append(String.format("Yaw:   %7.2f (Rate: %6.1f deg/s)\n", lastYaw, Math.toDegrees(lastGyroY)));
        sb.append(String.format("Roll:  %7.2f (Rate: %6.1f deg/s)\n", lastRoll, Math.toDegrees(lastGyroZ)));
        
        sb.append("\n=== TRACKER ===\n");
        if (ts != null && ts.detected) {
            sb.append(String.format("Blob: x=%.0f y=%.0f\n", ts.bboxX + ts.bboxW/2f, ts.bboxY + ts.bboxH/2f));
            sb.append(String.format("Size: %.1fpx\n", ts.bboxW));
            sb.append(String.format("Conf: %.2f\n", ts.confidence));
        } else {
            sb.append("ПОИСК...\n");
        }

        sb.append("\n=== БАЛЛИСТИКА ===\n");
        if (sceneRenderer != null && sceneRenderer.hasRelativePosition() && hasDronePosition) {
            float ldx = sceneRenderer.getLastDroneX();
            float ldy = sceneRenderer.getLastDroneY() - 1.6f;
            float ldz = sceneRenderer.getLastDroneZ();
            float dist = (float) Math.sqrt(ldx * ldx + ldy * ldy + ldz * ldz);
            sb.append(String.format("Дист. до цели: %5.0f м\n", dist));
        } else {
            sb.append("Дист. до цели: ---\n");
        }
        if (ballisticsManager != null && ballisticsManager.hasBestMiss()) {
            sb.append(String.format("Лучш. промах:  %5.0f м\n", ballisticsManager.getBestMissDistance()));
        } else {
            sb.append("Лучш. промах:  ---\n");
        }
        
        vitInfoText.setText(sb.toString());
    }

    private void handleCalibrateClick() {
        Boolean active = viewModel.isCalibrationActive().getValue();
        CalibrationPoint current = viewModel.getCalibrationPoint().getValue();
        if (Boolean.TRUE.equals(active)) {
            if (current != null && !current.confirmed) {
                viewModel.confirmCalibration();
                calibrationMarker.setVisibility(View.VISIBLE);
                updateCalibrationMarker(current.x, current.y);
            } else {
                viewModel.cancelCalibration();
                calibrateButton.setText("Калибровка");
                calibrationMarker.setVisibility(View.GONE);
            }
        } else if (current != null && current.confirmed) {
            viewModel.cancelCalibration();
            calibrateButton.setText("Калибровка");
            calibrationMarker.setVisibility(View.GONE);
        } else {
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

    private void scheduleFrame() {
        if (!frameScheduled) {
            frameScheduled = true;
            choreographer.postFrameCallback(frameCallback);
        }
    }

    public void updateDronePosition(double lat, double lon, double alt, float headingDegrees) {
        this.droneLat = lat; this.droneLon = lon; this.droneAlt = alt; this.droneHeading = headingDegrees;
        this.simulationActive = true; this.hasDronePosition = true;
        if (!simulationPaused) scheduleFrame();
    }

    public void onSimulationPaused(boolean paused) {
        this.simulationPaused = paused;
        if (!paused) scheduleFrame();
    }

    public void setMissionOrigin(double lat, double lon, double alt) {
        this.missionOriginLat = lat; this.missionOriginLon = lon; this.missionOriginAlt = alt;
        this.hasMissionOrigin = true;
    }

    public void fireBullet() {
        if (ballisticsManager != null) {
            ballisticsManager.fireBullet(camForwardX, camForwardY, camForwardZ);
        }
    }

    public void toggleThermal() {
        if (thermalOverlay == null || thermalButton == null) return;
        thermalActive = !thermalActive;
        thermalOverlay.setVisibility(thermalActive ? View.VISIBLE : View.GONE);
        thermalButton.setBackgroundColor(thermalActive ? 0xCC004400 : 0xCC001A00);
        thermalButton.setText(thermalActive ? "Тепловизор: ВКЛ" : "Тепловизор");
    }

    // --- Own simulation loop ---

    private void initBattleSimDrones(Mission m) {
        simDrones.clear();
        com.edgedetection.domain.mission.Waypoint start = m.waypoints.get(0);
        double speedMps = m.speedKmh * 1000.0 / 3600.0;
        double intervalMeters = speedMps * m.spawnIntervalSeconds;
        double baseAlt = m.originAltitudeAmsl != null ? m.originAltitudeAmsl : start.altitudeAmsl;
        for (int i = 0; i < m.droneCount; i++) {
            SimDrone d = new SimDrone();
            d.index = i;
            d.distanceMeters = -i * intervalMeters;
            d.visible = false;
            d.active = true;
            d.lives = m.maxLives;
            d.lat = start.latitude;
            d.lon = start.longitude;
            d.prevLat = start.latitude;
            d.prevLon = start.longitude;
            d.alt = baseAlt + m.altitudeMeters;
            d.bearing = 0;
            simDrones.add(d);
        }
    }

    private void startSimulationLoop() {
        if (simHandler == null) {
            simHandler = new Handler(Looper.getMainLooper());
        }
        isSimRunning = true;
        lastSimFrameTime = SystemClock.elapsedRealtime();
        simHandler.post(simRunnable);
    }

    private void stopSimulationLoop() {
        isSimRunning = false;
        if (simHandler != null) simHandler.removeCallbacks(simRunnable);
        simDrones.clear();
        hasDronePosition = false;
    }

    private final Runnable simRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isSimRunning || !isAdded() || isDetached()) return;
            Mission mission = missionVm.getMissionState().getValue();
            if (mission != null && mission.simState == Mission.SimulationState.RUNNING) {
                simulationTick(mission);
            }
            simHandler.postDelayed(this, 33);
        }
    };

    private void simulationTick(Mission mission) {
        long now = SystemClock.elapsedRealtime();
        double dt = (now - lastSimFrameTime) / 1000.0;
        lastSimFrameTime = now;

        if (mission.waypoints.size() < 2) return;

        double speedMps = mission.speedKmh * 1000.0 / 3600.0;
        double totalLen = simCalculateRouteLength(mission.waypoints);

        for (SimDrone d : simDrones) {
            if (!d.active) continue;
            d.prevLat = d.lat;
            d.prevLon = d.lon;
            d.distanceMeters += speedMps * dt;

            if (d.distanceMeters < 0) {
                d.visible = false;
            } else if (d.distanceMeters >= totalLen) {
                d.active = false;
                d.visible = false;
            } else {
                d.visible = true;
                double[] pos = simInterpolatePosition(mission.waypoints, d.distanceMeters);
                d.lat = pos[0];
                d.lon = pos[1];
                double baseAlt = mission.originAltitudeAmsl != null ? mission.originAltitudeAmsl : mission.waypoints.get(0).altitudeAmsl;
                d.alt = baseAlt + mission.altitudeMeters;
                double dx = (d.lon - d.prevLon) * Math.cos(Math.toRadians(d.lat)) * 111320.0;
                double dy = (d.lat - d.prevLat) * 110540.0;
                if (Math.hypot(dx, dy) > 1.0) {
                    d.bearing = simCalculateBearing(d.prevLat, d.prevLon, d.lat, d.lon);
                }
            }
        }

        if (!simDrones.isEmpty()) {
            SimDrone d = simDrones.get(0);
            if (d.active) {
                missionVm.setDronePosition(new com.edgedetection.domain.mission.DronePosition(
                        d.index, d.lat, d.lon, d.alt,
                        (float) d.bearing, d.visible
                ));
            }
        }

        boolean anyActive = false;
        for (SimDrone d : simDrones) {
            if (d.active) { anyActive = true; break; }
        }
        if (!anyActive) {
            stopSimulationLoop();
            missionVm.dispatch(new MissionIntent.StopSimulation());
        }
    }

    private double simCalculateRouteLength(List<com.edgedetection.domain.mission.Waypoint> wps) {
        double len = 0;
        for (int i = 1; i < wps.size(); i++) {
            com.edgedetection.domain.mission.Waypoint a = wps.get(i - 1);
            com.edgedetection.domain.mission.Waypoint b = wps.get(i);
            double dx = (b.longitude - a.longitude) * Math.cos(Math.toRadians(a.latitude)) * 111320;
            double dy = (b.latitude - a.latitude) * 110540;
            len += Math.sqrt(dx * dx + dy * dy);
        }
        return len;
    }

    private double[] simInterpolatePosition(List<com.edgedetection.domain.mission.Waypoint> wps, double dist) {
        double acc = 0;
        for (int i = 1; i < wps.size(); i++) {
            com.edgedetection.domain.mission.Waypoint a = wps.get(i - 1);
            com.edgedetection.domain.mission.Waypoint b = wps.get(i);
            double dx = (b.longitude - a.longitude) * Math.cos(Math.toRadians(a.latitude)) * 111320;
            double dy = (b.latitude - a.latitude) * 110540;
            double seg = Math.sqrt(dx * dx + dy * dy);
            if (acc + seg >= dist) {
                double t = (dist - acc) / seg;
                return new double[]{
                        a.latitude + (b.latitude - a.latitude) * t,
                        a.longitude + (b.longitude - a.longitude) * t,
                        a.altitudeAmsl + (b.altitudeAmsl - a.altitudeAmsl) * t
                };
            }
            acc += seg;
        }
        com.edgedetection.domain.mission.Waypoint last = wps.get(wps.size() - 1);
        return new double[]{last.latitude, last.longitude, last.altitudeAmsl};
    }

    private double simCalculateBearing(double lat1, double lon1, double lat2, double lon2) {
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(lat2Rad);
        double x = Math.cos(lat1Rad) * Math.sin(lat2Rad)
                - Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (glView != null) glView.onResume();
        if (sceneRenderer != null) sceneRenderer.onResume();
        if (sensorProvider != null) sensorProvider.start();
        if (calibrationHandler == null) calibrationHandler = new Handler(Looper.getMainLooper());
        calibrationHandler.removeCallbacks(calibrationRunnable);
        calibrationHandler.post(calibrationRunnable);
        if (frameProcessor != null) frameProcessor.resetTracker();
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) locationManager.startLocationUpdates();
        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onPause() {
        super.onPause();
        lastFrameNanos = 0;
        stopSimulationLoop();
        if (cameraManager != null && cameraManager.getCurrentSource().getValue() != null) cameraManager.getCurrentSource().getValue().stop();
        if (glView != null) glView.onPause();
        if (sceneRenderer != null) sceneRenderer.onPause();
        if (sensorProvider != null) sensorProvider.stop();
        if (calibrationHandler != null) calibrationHandler.removeCallbacks(calibrationRunnable);
        if (locationManager != null) locationManager.stopLocationUpdates();
        if (frameProcessor != null) frameProcessor.resetTracker();
        requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopSimulationLoop();
        if (soundManager != null) { soundManager.release(); soundManager = null; }
        ballisticsManager.clear();
        if (choreographer != null) choreographer.removeFrameCallback(frameCallback);
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
        if (sceneRenderer != null) sceneRenderer.destroy();
        if (frameProcessor != null) {
            frameProcessor.release();
            frameProcessor = null;
        }
        if (imuHandler != null) {
            imuHandler.getLooper().quit();
            imuHandler = null;
        }
    }
}
