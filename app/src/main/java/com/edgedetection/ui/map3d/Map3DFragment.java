package com.edgedetection.ui.map3d;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.edgedetection.R;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.RasterLayer;
import org.maplibre.android.style.sources.RasterSource;
import org.maplibre.android.style.sources.TileSet;

public class Map3DFragment extends Fragment {
    private MapView mapView;
    private Map3DViewModel viewModel;
    private MapLibreMap mapLibreMap;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(this).get(Map3DViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_map3d, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mapView = view.findViewById(R.id.map_view);

        // КРИТИЧНО: null вместо savedInstanceState, иначе Mali восстанавливает битый GL
        mapView.onCreate(null);

        mapView.getMapAsync(map -> {
            if (getView() == null || mapView == null) return;
            mapLibreMap = map;

            map.setStyle(new Style.Builder()
                            .withSource(new RasterSource(
                                    "osm",
                                    new TileSet("2.0", "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png"),
                                    256
                            ))
                            .withLayer(new RasterLayer("osm-layer", "osm")),
                    style -> {
                        if (mapLibreMap == null || getView() == null) return;

                        CameraPosition savedPos = viewModel.getCameraPosition().getValue();
                        if (savedPos != null) {
                            mapLibreMap.moveCamera(CameraUpdateFactory.newCameraPosition(savedPos));
                        } else {
                            CameraPosition position = new CameraPosition.Builder()
                                    .target(new LatLng(55.7558, 37.6173))
                                    .zoom(15.0).tilt(60.0).bearing(30.0)
                                    .build();
                            map.animateCamera(CameraUpdateFactory.newCameraPosition(position), 2000);
                        }
                    }
            );

            viewModel.getCameraPosition().observe(getViewLifecycleOwner(), pos -> {
                if (pos != null && mapLibreMap != null) {
                    mapLibreMap.moveCamera(CameraUpdateFactory.newCameraPosition(pos));
                }
            });

            map.addOnCameraMoveListener(() -> {
                if (mapLibreMap != null) {
                    viewModel.setCameraPosition(mapLibreMap.getCameraPosition());
                }
            });
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mapView != null) mapView.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mapView != null) mapView.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mapView != null) mapView.onStop();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mapView != null) {
            mapView.onDestroy();
            mapView = null;
        }
        mapLibreMap = null;
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mapView != null) mapView.onLowMemory();
    }
}