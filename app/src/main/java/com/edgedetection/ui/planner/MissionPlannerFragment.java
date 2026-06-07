package com.edgedetection.ui.planner;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.edgedetection.R;
import com.edgedetection.domain.geo.CurveGenerator;
import com.edgedetection.domain.mission.DronePosition;
import com.edgedetection.domain.mission.Mission;
import com.edgedetection.domain.mission.Waypoint;
import com.edgedetection.ui.shared.MissionIntent;
import com.edgedetection.ui.shared.MissionViewModel;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import org.maplibre.android.annotations.IconFactory;
import org.maplibre.android.annotations.Marker;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.annotations.Polyline;
import org.maplibre.android.annotations.PolylineOptions;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.OnMapReadyCallback;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.RasterLayer;
import org.maplibre.android.style.sources.RasterSource;
import org.maplibre.android.style.sources.TileSet;

import java.util.ArrayList;
import java.util.List;

public class MissionPlannerFragment extends Fragment implements OnMapReadyCallback {

    private MissionViewModel missionVm;
    private org.maplibre.android.annotations.Icon droneIcon;
    // Views
    private MapView mapView;
    private MapLibreMap mapLibreMap;
    private FrameLayout view3dContainer;
    private LinearLayout rightPanel;
    private MaterialButtonToggleGroup modeToggle;
    private MaterialButton btnCreateFinish, btnDeleteRoute, btnStart;
    private MaterialButton btnSimStart, btnSimPause, btnSimStop;
    private MaterialButton btnDroneMinus, btnDronePlus;
    private MaterialButton btnDeletePoint;
    private AutoCompleteTextView dropdownRoutes;
    private TextView tvDroneCount, tvPointLat, tvPointLon, tvShotDown, tvSimStatus;

    // State
    private boolean isCreatingRoute = false;
    private String selectedWaypointId = null;
    private final List<Marker> markers = new ArrayList<>();
    private final List<Marker> simMarkers = new ArrayList<>();
    private final java.util.Map<Integer, org.maplibre.android.annotations.Icon> iconCache = new java.util.HashMap<>();
    private Polyline routePolyline;

    // Simulation
    private Handler simHandler = new Handler(Looper.getMainLooper());
    private boolean isSimulationRunning = false;
    private final Runnable simRunnable = new Runnable() {
        @Override
        public void run() {
            Mission m = missionVm.getMissionState().getValue();
            if (m != null && m.simState == Mission.SimulationState.RUNNING && isSimulationRunning) {
                simulationTick(m);
                simHandler.postDelayed(this, 16);
            }
        }
    };
    private long lastFrameTime;
    private List<SimDrone> simDrones = new ArrayList<>();
    private Bitmap droneBitmap;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private boolean centeredInitially = false;
    private List<Mission> currentMissions = new ArrayList<>();
    static class SimDrone {
        int index;
        double distanceMeters;
        boolean visible = false;
        boolean active = true;
        double lat, lon, alt;
        double prevLat, prevLon;   // предыдущая позиция для расчёта курса
        double bearing = 0;        // угол в градусах (0° = север, по часовой)
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        missionVm = new ViewModelProvider(requireActivity()).get(MissionViewModel.class);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_mission_planner, container, false);
        mapView = root.findViewById(R.id.planner_map_view);
        view3dContainer = root.findViewById(R.id.planner_3d_container);
        rightPanel = root.findViewById(R.id.right_panel);
        modeToggle = root.findViewById(R.id.view_mode_toggle);
        btnCreateFinish = root.findViewById(R.id.btn_create_finish);
        btnDeleteRoute = root.findViewById(R.id.btn_delete_route);
        btnStart = root.findViewById(R.id.btn_start);
        btnSimStart = root.findViewById(R.id.btn_sim_start);
        btnSimPause = root.findViewById(R.id.btn_sim_pause);
        btnSimStop = root.findViewById(R.id.btn_sim_stop);
        btnDroneMinus = root.findViewById(R.id.btn_drone_minus);
        btnDronePlus = root.findViewById(R.id.btn_drone_plus);
        btnDeletePoint = root.findViewById(R.id.btn_delete_point);
        dropdownRoutes = root.findViewById(R.id.dropdown_routes);
        tvDroneCount = root.findViewById(R.id.tv_drone_count);
        tvPointLat = root.findViewById(R.id.tv_point_lat);
        tvPointLon = root.findViewById(R.id.tv_point_lon);
        tvShotDown = root.findViewById(R.id.tv_shotdown_count);
        tvSimStatus = root.findViewById(R.id.tv_sim_status);

        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupModeToggle();
        setupCreateFinish();
        setupDeleteRoute();
        setupDroneCounter();
        setupStart();
        setupDeletePoint();
        setupSimulationControls();
        setupRouteDropdown();

        missionVm.getMissionState().observe(getViewLifecycleOwner(), this::onMissionChanged);
        missionVm.getAllMissions().observe(getViewLifecycleOwner(), this::onMissionsListChanged);

        // Кэшируем иконку дрона один раз
        droneBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.drone);
    }

    // === MapLibre ===
    @Override
    public void onMapReady(@NonNull MapLibreMap map) {
        this.mapLibreMap = map;
        map.setStyle(new Style.Builder()
                        .withSource(new RasterSource("osm",
                                new TileSet("2.0", "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png"), 256))
                        .withLayer(new RasterLayer("osm-layer", "osm")),
                style -> {
                    enableLocationComponent(style);
                    startLocationUpdatesForCentering();
                });

        map.addOnMapClickListener(latLng -> {
            if (isCreatingRoute) {
                missionVm.dispatch(new MissionIntent.AddWaypoint(
                        latLng.getLatitude(), latLng.getLongitude(), 100.0));
                Toast.makeText(requireContext(), "Точка добавлена", Toast.LENGTH_SHORT).show();
                return true;
            }
            rightPanel.setVisibility(View.GONE);
            selectedWaypointId = null;
            return false;
        });

        map.setOnMarkerClickListener(marker -> {
            String id = marker.getSnippet();
            if (id != null) selectWaypoint(id);
            return true;
        });
    }

    // === UI Setup ===
    private void setupModeToggle() {
        modeToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btn_mode_2d) {
                mapView.setVisibility(View.VISIBLE);
                view3dContainer.setVisibility(View.GONE);
            } else if (checkedId == R.id.btn_mode_3d) {
                mapView.setVisibility(View.GONE);
                view3dContainer.setVisibility(View.VISIBLE);
            }
        });
    }

    private void setupCreateFinish() {
        btnCreateFinish.setOnClickListener(v -> {
            if (!isCreatingRoute) {
                isCreatingRoute = true;
                btnCreateFinish.setText("Закончить");
                btnDeleteRoute.setVisibility(View.GONE);
                rightPanel.setVisibility(View.GONE);
                selectedWaypointId = null;
                Toast.makeText(requireContext(), "Тапайте по карте", Toast.LENGTH_SHORT).show();
            } else {
                Mission m = missionVm.getMissionState().getValue();
                if (m == null || m.waypoints.size() < 2) {
                    Toast.makeText(requireContext(), "Минимум 2 точки", Toast.LENGTH_SHORT).show();
                    return;
                }
                isCreatingRoute = false;
                btnCreateFinish.setText("Создать");
                btnDeleteRoute.setVisibility(View.VISIBLE);
            }
            updateDropdown(missionVm.getMissionState().getValue());
        });
    }

    private void setupDeleteRoute() {
        btnDeleteRoute.setOnClickListener(v -> {
            Mission current = missionVm.getMissionState().getValue();
            if (current != null) {
                missionVm.dispatch(new MissionIntent.DeleteMission(current.id));
            }
            isCreatingRoute = false;
            btnCreateFinish.setText("Создать");
            btnDeleteRoute.setVisibility(View.GONE);
            rightPanel.setVisibility(View.GONE);
            selectedWaypointId = null;
        });
    }

    private void setupDroneCounter() {
        btnDroneMinus.setOnClickListener(v -> {
            int current = Integer.parseInt(tvDroneCount.getText().toString());
            if (current > 1) {
                int next = current - 1;
                tvDroneCount.setText(String.valueOf(next));
                missionVm.dispatch(new MissionIntent.SetDroneCount(next));
            }
        });
        btnDronePlus.setOnClickListener(v -> {
            int current = Integer.parseInt(tvDroneCount.getText().toString());
            if (current < 10) {
                int next = current + 1;
                tvDroneCount.setText(String.valueOf(next));
                missionVm.dispatch(new MissionIntent.SetDroneCount(next));
            }
        });
    }

    private void setupStart() {
        // Кнопка СТАРТ перенаправляет на кнопку запуска симуляции
        btnStart.setOnClickListener(v -> {
            Mission m = missionVm.getMissionState().getValue();
            if (m == null || m.waypoints.size() < 2) {
                Toast.makeText(requireContext(), "Создайте маршрут", Toast.LENGTH_SHORT).show();
                return;
            }
            btnSimStart.performClick();
        });
    }

    private void setupDeletePoint() {
        btnDeletePoint.setOnClickListener(v -> {
            if (selectedWaypointId == null) return;
            Mission m = missionVm.getMissionState().getValue();
            if (m != null && m.waypoints.size() <= 1) {
                Toast.makeText(requireContext(), "Нельзя удалить последнюю точку", Toast.LENGTH_SHORT).show();
                return;
            }
            missionVm.dispatch(new MissionIntent.RemoveWaypoint(selectedWaypointId));
            rightPanel.setVisibility(View.GONE);
            selectedWaypointId = null;
        });
    }

    // === Route Dropdown ===
    private void setupRouteDropdown() {
        dropdownRoutes.setOnItemClickListener((parent, view, position, id) -> {
            if (position == 0) { // "Создать новый"
                missionVm.dispatch(new MissionIntent.ClearMission());
                isCreatingRoute = true;
                btnCreateFinish.setText("Закончить");
                btnDeleteRoute.setVisibility(View.GONE);
                rightPanel.setVisibility(View.GONE);
                selectedWaypointId = null;
                Toast.makeText(requireContext(), "Тапайте по карте", Toast.LENGTH_SHORT).show();
            } else {
                btnSimStop.performClick();
                Mission selected = currentMissions.get(position - 1);
                missionVm.dispatch(new MissionIntent.LoadMission(selected.id));
                isCreatingRoute = false;
                btnCreateFinish.setText("Создать");
            }
        });
    }

    // === СИМУЛЯЦИЯ ===
    private void setupSimulationControls() {
        btnSimStart.setOnClickListener(v -> {
            if (isSimulationRunning) return;
            Mission m = missionVm.getMissionState().getValue();
            if (m == null || m.waypoints.size() < 2) {
                Toast.makeText(requireContext(), "Нет маршрута", Toast.LENGTH_SHORT).show();
                return;
            }
            if (m.simState == Mission.SimulationState.IDLE) initSimDrones(m);
            missionVm.dispatch(new MissionIntent.StartSimulation());
            lastFrameTime = SystemClock.elapsedRealtime();
            isSimulationRunning = true;
            simHandler.post(simRunnable);
        });

        btnSimPause.setOnClickListener(v -> {
            missionVm.dispatch(new MissionIntent.PauseSimulation());
            isSimulationRunning = false;
            simHandler.removeCallbacks(simRunnable);
        });

        btnSimStop.setOnClickListener(v -> {
            missionVm.dispatch(new MissionIntent.StopSimulation());
            isSimulationRunning = false;
            simHandler.removeCallbacks(simRunnable);
            simDrones.clear();
            updateSimMarkers(); // очистит слой
        });
    }

    private void initSimDrones(Mission m) {
        simDrones.clear();
        Waypoint start = m.waypoints.get(0);
        for (int i = 0; i < m.droneCount; i++) {
            SimDrone d = new SimDrone();
            d.index = i;
            d.distanceMeters = -i * 5000.0; // 5 км интервал
            d.visible = false;
            d.active = true;
            d.lat = start.latitude;
            d.lon = start.longitude;
            d.prevLat = start.latitude;
            d.prevLon = start.longitude;
            d.alt = start.altitudeAmsl;
            d.bearing = 0;
            simDrones.add(d);
        }
    }

    private double calculateBearing(double lat1, double lon1, double lat2, double lon2) {
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);

        double y = Math.sin(dLon) * Math.cos(lat2Rad);
        double x = Math.cos(lat1Rad) * Math.sin(lat2Rad)
                - Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(dLon);

        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (bearing + 360.0) % 360.0;
    }

    private void simulationTick(Mission mission) {
        long now = SystemClock.elapsedRealtime();
        double dt = (now - lastFrameTime) / 1000.0;
        lastFrameTime = now;

        double speedMps = 200.0 * 1000.0 / 3600.0; // 55.56 м/с
        double totalLen = calculateRouteLength(mission.waypoints);

        for (SimDrone d : simDrones) {
            if (!d.active) continue;

            // запоминаем старую позицию
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
                double[] pos = interpolatePosition(mission.waypoints, d.distanceMeters);
                d.lat = pos[0];
                d.lon = pos[1];
                d.alt = pos[2];

                // считаем курс, только если реально сдвинулись (избегаем дрожания при старте)
                double dx = (d.lon - d.prevLon) * Math.cos(Math.toRadians(d.lat)) * 111320.0;
                double dy = (d.lat - d.prevLat) * 110540.0;
                if (Math.hypot(dx, dy) > 1.0) { // > 1 метра
                    d.bearing = calculateBearing(d.prevLat, d.prevLon, d.lat, d.lon);
                }
            }
        }

        updateSimMarkers();
    }

    private double calculateRouteLength(List<Waypoint> wps) {
        double len = 0;
        for (int i = 1; i < wps.size(); i++) {
            Waypoint a = wps.get(i - 1);
            Waypoint b = wps.get(i);
            double dx = (b.longitude - a.longitude) * Math.cos(Math.toRadians(a.latitude)) * 111320;
            double dy = (b.latitude - a.latitude) * 110540;
            len += Math.sqrt(dx * dx + dy * dy);
        }
        return len;
    }

    private double[] interpolatePosition(List<Waypoint> wps, double dist) {
        double acc = 0;
        for (int i = 1; i < wps.size(); i++) {
            Waypoint a = wps.get(i - 1);
            Waypoint b = wps.get(i);
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
        Waypoint last = wps.get(wps.size() - 1);
        return new double[]{last.latitude, last.longitude, last.altitudeAmsl};
    }

    private static final String DRONE_LAYER_ID = "drone-layer";
    private static final String DRONE_SOURCE_ID = "drone-source";

    private void updateSimMarkers() {
        if (mapLibreMap == null) return;

        Style style = mapLibreMap.getStyle();
        if (style == null) return;

        // Используем кэшированную иконку дрона
        if (droneBitmap != null) {
            style.removeImage("drone");
            style.addImage("drone", droneBitmap);
        }

        // Собираем GeoJSON
        List<Feature> features = new ArrayList<>();
        for (SimDrone d : simDrones) {
            if (d.active && d.visible) {
                Point point = Point.fromLngLat(d.lon, d.lat);
                Feature f = Feature.fromGeometry(point);
                f.addNumberProperty("bearing", d.bearing);
                f.addNumberProperty("index", d.index);
                f.addNumberProperty("alt", d.alt);
                features.add(f);
            }
        }
        if (!simDrones.isEmpty()) {
            SimDrone d = simDrones.get(0);
            if (d.active) {
                missionVm.setDronePosition(new DronePosition(
                        d.index, d.lat, d.lon, d.alt,
                        (float) d.bearing, d.visible
                ));
            }
        }

        FeatureCollection collection = FeatureCollection.fromFeatures(features);

        // Обновляем или создаём источник
        GeoJsonSource source = style.getSourceAs(DRONE_SOURCE_ID);
        if (source == null) {
            source = new GeoJsonSource(DRONE_SOURCE_ID, collection);
            style.addSource(source);

            SymbolLayer layer = new SymbolLayer(DRONE_LAYER_ID, DRONE_SOURCE_ID)
                    .withProperties(
                            PropertyFactory.iconImage("drone"),
                            PropertyFactory.iconSize(0.5f),
                            PropertyFactory.iconRotate(Expression.get("bearing")),
                            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true)
                    );
            style.addLayer(layer);
        } else {
            source.setGeoJson(collection);
        }
    }

    // === Mission observer ===
    private void onMissionChanged(Mission mission) {
        if (mission == null) return;

        // 1. Статус симуляции
        String statusText;
        switch (mission.simState) {
            case RUNNING: statusText = "запущена"; break;
            case PAUSED:  statusText = "пауза"; break;
            case IDLE: default: statusText = "остановлена"; break;
        }
        tvSimStatus.setText("СИМУЛЯЦИЯ (" + statusText + ")");

        // 2. Счётчик сбитых
        tvShotDown.setText(String.valueOf(mission.shotDownCount));

        // 3. Счётчик БПЛА — источник правды только из Mission
        tvDroneCount.setText(String.valueOf(mission.droneCount));

        // 4. Маркеры и линия
        updateMarkers(mission);
        updateRouteLine(mission);
        updateDropdown(mission);

        // 5. Кнопка удаления маршрута
        boolean hasRoute = !mission.waypoints.isEmpty();
        if (!isCreatingRoute) {
            btnDeleteRoute.setVisibility(hasRoute ? View.VISIBLE : View.GONE);
        }

        // 6. Проверка выбранной точки
        if (selectedWaypointId != null) {
            boolean exists = false;
            for (Waypoint wp : mission.waypoints) {
                if (wp.id.equals(selectedWaypointId)) { exists = true; break; }
            }
            if (!exists) {
                rightPanel.setVisibility(View.GONE);
                selectedWaypointId = null;
            }
        }
    }

    private void updateMarkers(Mission mission) {
        if (mapLibreMap == null) return;
        for (Marker m : markers) mapLibreMap.removeMarker(m);
        markers.clear();

        org.maplibre.android.annotations.Icon icon = getIconForColor(mission.color);

        for (Waypoint wp : mission.waypoints) {
            Marker m = mapLibreMap.addMarker(new MarkerOptions()
                    .position(new LatLng(wp.latitude, wp.longitude))
                    .title("WP " + (wp.orderIndex + 1))
                    .icon(icon)
                    .snippet(wp.id));
            markers.add(m);
        }
    }

    private org.maplibre.android.annotations.Icon getIconForColor(int color) {
        if (iconCache.containsKey(color)) return iconCache.get(color);

        Drawable drawable = ContextCompat.getDrawable(requireContext(), org.maplibre.android.R.drawable.maplibre_marker_icon_default);
        if (drawable == null) return null;

        Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        drawable.draw(canvas);

        org.maplibre.android.annotations.Icon icon = IconFactory.getInstance(requireContext()).fromBitmap(bitmap);
        iconCache.put(color, icon);
        return icon;
    }

    private void updateRouteLine(Mission mission) {
        if (mapLibreMap == null) return;
        if (routePolyline != null) {
            mapLibreMap.removePolyline(routePolyline);
            routePolyline = null;
        }
        if (mission == null || mission.waypoints.size() < 2) return;

        List<LatLng> curve = CurveGenerator.generateCatmullRom(mission.waypoints, 20);
        routePolyline = mapLibreMap.addPolyline(new PolylineOptions()
                .addAll(curve)
                .color(mission.color)
                .width(5f));
    }

    private void onMissionsListChanged(List<Mission> missions) {
        this.currentMissions = missions;
        updateDropdown(missionVm.getMissionState().getValue());
    }

    private void updateDropdown(Mission activeMission) {
        if (getContext() == null) return;
        List<String> items = new ArrayList<>();
        items.add("Создать новый");

        int activeIndex = 0;
        for (int i = 0; i < currentMissions.size(); i++) {
            Mission m = currentMissions.get(i);
            String label = m.name + " (" + m.waypoints.size() + " точек)";
            items.add(label);
            if (activeMission != null && m.id.equals(activeMission.id)) {
                activeIndex = i + 1;
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, items);
        dropdownRoutes.setAdapter(adapter);

        if (activeIndex < items.size()) {
            dropdownRoutes.setText(items.get(activeIndex), false);
        }
    }

    private void selectWaypoint(String id) {
        Mission m = missionVm.getMissionState().getValue();
        if (m == null) return;
        for (Waypoint wp : m.waypoints) {
            if (wp.id.equals(id)) {
                selectedWaypointId = id;
                tvPointLat.setText(String.format(java.util.Locale.US, "%.6f", wp.latitude));
                tvPointLon.setText(String.format(java.util.Locale.US, "%.6f", wp.longitude));
                rightPanel.setVisibility(View.VISIBLE);
                return;
            }
        }
    }

    private void enableLocationComponent(Style style) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        LocationComponent locationComponent = mapLibreMap.getLocationComponent();
        locationComponent.activateLocationComponent(
                LocationComponentActivationOptions.builder(requireContext(), style)
                        .useDefaultLocationEngine(true)
                        .build()
        );
        locationComponent.setLocationComponentEnabled(true);
        locationComponent.setRenderMode(RenderMode.COMPASS);
    }

    private void startLocationUpdatesForCentering() {
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
                if (loc != null && !centeredInitially) {
                    centeredInitially = true;
                    LatLng latLng = new LatLng(loc.getLatitude(), loc.getLongitude());
                    mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16.0));
                    fusedLocationClient.removeLocationUpdates(this);
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
    }
    // === Lifecycle ===
    @Override public void onStart() { super.onStart(); mapView.onStart(); }
    @Override public void onResume() {
        super.onResume();
        mapView.onResume();
        missionVm.cleanupMissions();
    }
    @Override public void onPause() { super.onPause(); mapView.onPause(); }
    @Override public void onStop() { super.onStop(); mapView.onStop(); }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        isSimulationRunning = false;
        simHandler.removeCallbacks(simRunnable);
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (droneBitmap != null) {
            droneBitmap.recycle();
            droneBitmap = null;
        }
        if (mapLibreMap != null) {
            for (Marker m : markers) mapLibreMap.removeMarker(m);
            for (Marker m : simMarkers) mapLibreMap.removeMarker(m);
        }
        mapView.onDestroy();
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }
}