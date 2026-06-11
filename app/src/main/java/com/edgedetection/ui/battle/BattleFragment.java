package com.edgedetection.ui.battle;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
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
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import android.media.MediaPlayer;

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
import com.edgedetection.core.camera.ExternalCameraSource;
import com.edgedetection.core.camera.InternalCameraSource;
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
import com.edgedetection.ui.shared.MissionViewModel;
import com.google.android.filament.Viewport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BattleFragment extends Fragment {
    private static final String TAG = "BattleFragment";
    private static final int AR_PERMISSION_REQUEST = 3;
    private static final float TARGET_WIDTH_M = 2.0f;
    private static final float TARGET_LENGTH_M = 4.0f;
    private static final long CALIBRATION_INTERVAL_MS = 5000;

    // --- Components ---
    private BattleSensorProvider sensorProvider;
    private BattleLocationManager locationManager;
    private BattleBallisticsManager ballisticsManager;
    private BattleSceneRenderer sceneRenderer;
    private BattleFrameProcessor frameProcessor;

    // --- State ---
    private BattleViewModel viewModel;
    private CameraManager cameraManager;
    private ExecutorService cameraExecutor;
    private Handler imuHandler;
    private Handler calibrationHandler;
    
    private volatile float lastGyroX = 0f, lastGyroY = 0f, lastGyroZ = 0f;
    private volatile long lastGyroTimestampNs = 0;
    private long lastLogTime = 0;

    // --- Views ---
    private EdgeDetectionGLView glView;
    private PreviewView previewView;
    private BulletTrajectoryView bulletOverlay;
    private ImageView calibrationMarker;
    private TextView vitInfoText;
    private Button calibrateButton;
    private Button toggleEdgesButton;
    private TextView gpsWarning;

    // RTSP Views
    private FrameLayout rtspContainer;
    private VideoView rtspVideoView;
    private View rtspErrorOverlay;
    private ProgressBar rtspProgressBar;
    private TextView rtspErrorText;
    private TextView rtspRetryText;

    // RTSP State Tracking
    private final Handler rtspHandler = new Handler(Looper.getMainLooper());
    private boolean isRtspActive = false;
    private boolean isVideoRendering = false;

    // --- Simulation state ---
    private volatile double droneLat, droneLon, droneAlt;
    private volatile float droneHeading = 0f;
    private volatile boolean simulationActive = false;
    private volatile boolean simulationPaused = false;
    private volatile boolean hasDronePosition = false;
    private volatile int currentDroneIndex = -1;
    private double missionOriginLat, missionOriginLon, missionOriginAlt;
    private boolean hasMissionOrigin = false;

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
        choreographer = Choreographer.getInstance();

        HandlerThread imuThread = new HandlerThread("IMU");
        imuThread.start();
        imuHandler = new Handler(imuThread.getLooper());

        sensorProvider = new BattleSensorProvider(requireContext(), imuHandler, new BattleSensorProvider.OnSensorChangedListener() {
            @Override
            public void onRotationMatrixUpdated(float[] rotationMatrix, float[] orientation, float initialAzimuth) {
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
        previewView = view.findViewById(R.id.preview_view);
        previewView.setVisibility(View.INVISIBLE);
        glView = view.findViewById(R.id.camera_view);
        gpsWarning = view.findViewById(R.id.gps_warning);
        bulletOverlay = view.findViewById(R.id.bullet_overlay);
        calibrationMarker = view.findViewById(R.id.calibration_marker);
        vitInfoText = view.findViewById(R.id.vit_info);
        calibrateButton = view.findViewById(R.id.calibrate_button);
        toggleEdgesButton = view.findViewById(R.id.toggle_edges_button);

        rtspContainer = view.findViewById(R.id.rtsp_container);
        rtspVideoView = view.findViewById(R.id.rtsp_video_view);
        rtspErrorOverlay = view.findViewById(R.id.rtsp_error_overlay);
        rtspProgressBar = view.findViewById(R.id.rtsp_progress_bar);
        rtspErrorText = view.findViewById(R.id.rtsp_error_text);
        rtspRetryText = view.findViewById(R.id.rtsp_retry_text);

        view.findViewById(R.id.fire_button).setOnClickListener(v -> {
            ballisticsManager.fireBullet(camForwardX, camForwardY, camForwardZ);
        });

        view.findViewById(R.id.switch_camera_button).setOnClickListener(v -> {
            if (cameraManager != null) {
                cameraManager.toggleSource();
                Toast.makeText(requireContext(), "Переключение камеры...", Toast.LENGTH_SHORT).show();
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
                sceneRenderer.setHasRelativePosition(false);
            }
        });

        missionVm.getDronePosition().observe(getViewLifecycleOwner(), pos -> {
            if (pos != null && pos.active) {
                this.currentDroneIndex = pos.index;
                updateDronePosition(pos.lat, pos.lon, pos.alt, pos.heading);
            }
        });
    }

    private final Runnable rtspRetryRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRtspActive) return;
            checkConnectionAndStartStream();
        }
    };

    private final Runnable rtspTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRtspActive) return;
            if (!isVideoRendering) {
                showRtspError("Нет изображения по rtsp://192.168.42.1:8554/video");
                scheduleRtspRetry();
            }
        }
    };

    private void startRtspStreaming() {
        isRtspActive = true;
        checkConnectionAndStartStream();
    }

    private void stopRtspStreaming() {
        isRtspActive = false;
        isVideoRendering = false;
        rtspHandler.removeCallbacks(rtspRetryRunnable);
        rtspHandler.removeCallbacks(rtspTimeoutRunnable);
        stopRtspPlayback();
    }

    private void stopRtspPlayback() {
        try {
            if (rtspVideoView != null) {
                rtspVideoView.stopPlayback();
                rtspVideoView.setOnPreparedListener(null);
                rtspVideoView.setOnErrorListener(null);
                rtspVideoView.setOnInfoListener(null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping RTSP playback", e);
        }
    }

    private void checkConnectionAndStartStream() {
        if (rtspProgressBar != null) rtspProgressBar.setVisibility(View.VISIBLE);
        if (rtspErrorText != null) rtspErrorText.setText("Проверка связи (192.168.42.1)...");
        if (rtspRetryText != null) rtspRetryText.setVisibility(View.GONE);
        if (rtspErrorOverlay != null) rtspErrorOverlay.setVisibility(View.VISIBLE);

        stopRtspPlayback();

        if (cameraExecutor == null) return;

        cameraExecutor.execute(() -> {
            boolean pingOk = pingHost("192.168.42.1", 1500);
            rtspHandler.post(() -> {
                if (!isRtspActive) return;
                if (!pingOk) {
                    showRtspError("Нет пинга до 192.168.42.1");
                    scheduleRtspRetry();
                } else {
                    startRtspPlayback();
                }
            });
        });
    }

    private void startRtspPlayback() {
        isVideoRendering = false;
        if (rtspProgressBar != null) rtspProgressBar.setVisibility(View.VISIBLE);
        if (rtspErrorText != null) rtspErrorText.setText("Загрузка RTSP потока...");
        if (rtspErrorOverlay != null) rtspErrorOverlay.setVisibility(View.VISIBLE);

        rtspHandler.removeCallbacks(rtspTimeoutRunnable);
        rtspHandler.postDelayed(rtspTimeoutRunnable, 3000); // 3-second timeout

        try {
            rtspVideoView.setVideoURI(android.net.Uri.parse("rtsp://192.168.42.1:8554/video"));
            
            rtspVideoView.setOnPreparedListener(mp -> {
                if (!isRtspActive) return;
                rtspVideoView.start();
            });

            rtspVideoView.setOnErrorListener((mp, what, extra) -> {
                if (!isRtspActive) return true;
                rtspHandler.removeCallbacks(rtspTimeoutRunnable);
                showRtspError("Нет изображения по rtsp://192.168.42.1:8554/video");
                scheduleRtspRetry();
                return true;
            });

            rtspVideoView.setOnInfoListener((mp, what, extra) -> {
                if (!isRtspActive) return false;
                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    isVideoRendering = true;
                    rtspHandler.removeCallbacks(rtspTimeoutRunnable);
                    if (rtspErrorOverlay != null) rtspErrorOverlay.setVisibility(View.GONE);
                }
                return false;
            });
        } catch (Exception e) {
            Log.e(TAG, "Error setting up RTSP VideoView", e);
            showRtspError("Нет изображения по rtsp://192.168.42.1:8554/video");
            scheduleRtspRetry();
        }
    }

    private void showRtspError(String message) {
        if (rtspProgressBar != null) rtspProgressBar.setVisibility(View.GONE);
        if (rtspErrorText != null) rtspErrorText.setText(message);
        if (rtspErrorOverlay != null) rtspErrorOverlay.setVisibility(View.VISIBLE);
    }

    private void scheduleRtspRetry() {
        if (rtspRetryText != null) rtspRetryText.setVisibility(View.VISIBLE);
        rtspHandler.removeCallbacks(rtspRetryRunnable);
        rtspHandler.postDelayed(rtspRetryRunnable, 3000); // Retry every 3 seconds
    }

    private boolean pingHost(String host, int timeoutMs) {
        try {
            Process process = Runtime.getRuntime().exec("/system/bin/ping -c 1 -W 2 " + host);
            int exitValue = process.waitFor();
            if (exitValue == 0) return true;
        } catch (Exception e) {
            Log.w(TAG, "Shell ping command failed, trying fallback reachable check", e);
        }
        try {
            java.net.InetAddress address = java.net.InetAddress.getByName(host);
            return address.isReachable(timeoutMs);
        } catch (Exception e) {
            Log.e(TAG, "InetAddress check failed for " + host, e);
            return false;
        }
    }

    private void showRtspStreamingUI() {
        if (rtspContainer != null) rtspContainer.setVisibility(View.VISIBLE);
        if (glView != null) glView.setVisibility(View.GONE);
        View ar = getView() != null ? getView().findViewById(R.id.ar_overlay) : null;
        if (ar != null) ar.setVisibility(View.GONE);
        if (bulletOverlay != null) bulletOverlay.setVisibility(View.GONE);
        if (calibrateButton != null) calibrateButton.setVisibility(View.GONE);
        if (toggleEdgesButton != null) toggleEdgesButton.setVisibility(View.GONE);

        startRtspStreaming();
    }

    private void hideRtspStreamingUI() {
        stopRtspStreaming();

        if (rtspContainer != null) rtspContainer.setVisibility(View.GONE);
        if (glView != null) glView.setVisibility(View.VISIBLE);
        View ar = getView() != null ? getView().findViewById(R.id.ar_overlay) : null;
        if (ar != null) ar.setVisibility(View.VISIBLE);
        if (bulletOverlay != null) bulletOverlay.setVisibility(View.VISIBLE);
        if (calibrateButton != null) calibrateButton.setVisibility(View.VISIBLE);
        if (toggleEdgesButton != null) toggleEdgesButton.setVisibility(View.VISIBLE);
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
            CameraSource external = new ExternalCameraSource();
            cameraManager = new CameraManager(internal, external);

            cameraManager.getCurrentSource().observe(getViewLifecycleOwner(), source -> {
                if (source != null) {
                    if (source instanceof ExternalCameraSource) {
                        showRtspStreamingUI();
                    } else {
                        hideRtspStreamingUI();
                        source.start(image -> frameProcessor.processFrame(image, lastGyroX, lastGyroY, lastGyroZ, lastGyroTimestampNs));
                    }
                }
            });
        } else {
            CameraSource current = cameraManager.getCurrentSource().getValue();
            if (current != null && !current.isRunning()) {
                if (current instanceof ExternalCameraSource) {
                    showRtspStreamingUI();
                } else {
                    hideRtspStreamingUI();
                    current.start(image -> frameProcessor.processFrame(image, lastGyroX, lastGyroY, lastGyroZ, lastGyroTimestampNs));
                }
            }
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
        if (locationManager.hasUserLocation()) {
            refLat = locationManager.getUserLat(); refLon = locationManager.getUserLon(); refAlt = locationManager.getUserAlt();
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

        long now = System.currentTimeMillis();
        if (now - lastLogTime > 1000 && sceneRenderer.hasRelativePosition()) {
            lastLogTime = now;
            double[] enu = com.edgedetection.domain.geo.GeoUtils.ecefToEnu(refLat, refLon, refAlt, droneLat, droneLon, droneAlt);
            BattleLogger.logState(fx, fy, fz, ux, uy, uz, refLat, refLon, refAlt, enu, 
                sceneRenderer.getLastDroneX(), sceneRenderer.getLastDroneY(), sceneRenderer.getLastDroneZ(),
                droneLat, droneLon, droneAlt, sensorProvider.getLastRotationVector(), sensorProvider.getRotationMatrix(),
                sceneRenderer.getArRenderer(), sceneRenderer.isDroneVisible(fx, fy, fz, ux, uy, uz));
        }

        if (dt > 0 && dt < 0.5f) {
            ballisticsManager.updateBullets(dt, simulationActive, sceneRenderer.getLastDroneX(), sceneRenderer.getLastDroneY(), sceneRenderer.getLastDroneZ(), sceneRenderer.getModelRadius(), currentDroneIndex);
        }

        sceneRenderer.updateOffscreenIndicator(fx, fy, fz, ux, uy, uz, getView());

        if (bulletOverlay != null && sceneRenderer.getArRenderer() != null) {
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
            bulletOverlay.setCameraMatrices(viewMat, projMat, vp.width, vp.height);
            bulletOverlay.setBullets(ballisticsManager.getBullets());
        }

        updateVITInfo();
    }

    private void updateVITInfo() {
        if (vitInfoText == null || frameProcessor == null) return;
        VITTracker.TargetState ts = frameProcessor.getLastTargetState();
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

    @Override
    public void onResume() {
        super.onResume();
        if (glView != null) glView.onResume();
        if (sceneRenderer != null) sceneRenderer.onResume();
        if (sensorProvider != null) sensorProvider.start();
        if (calibrationHandler == null) calibrationHandler = new Handler(Looper.getMainLooper());
        calibrationHandler.removeCallbacks(calibrationRunnable);
        calibrationHandler.postDelayed(calibrationRunnable, CALIBRATION_INTERVAL_MS);
        if (frameProcessor != null) frameProcessor.resetTracker();
        
        if (cameraManager != null) {
            CameraSource current = cameraManager.getCurrentSource().getValue();
            if (current instanceof ExternalCameraSource) {
                showRtspStreamingUI();
            } else {
                hideRtspStreamingUI();
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
            }
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) locationManager.startLocationUpdates();
        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onPause() {
        super.onPause();
        lastFrameNanos = 0;
        stopRtspStreaming();
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
        ballisticsManager.clear();
        if (choreographer != null) choreographer.removeFrameCallback(frameCallback);
        stopRtspStreaming();
        rtspContainer = null;
        rtspVideoView = null;
        rtspErrorOverlay = null;
        rtspProgressBar = null;
        rtspErrorText = null;
        rtspRetryText = null;
        rtspHandler.removeCallbacksAndMessages(null);

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
