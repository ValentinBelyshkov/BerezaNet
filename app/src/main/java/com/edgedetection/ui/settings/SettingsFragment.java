package com.edgedetection.ui.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.edgedetection.R;
import com.edgedetection.domain.mission.Mission;
import com.edgedetection.ui.shared.MissionIntent;
import com.edgedetection.ui.shared.MissionViewModel;

public class SettingsFragment extends Fragment {

    private MissionViewModel missionVm;
    private TextView tvLives, tvSpeed, tvAltitude, tvSpawnInterval;
    private SeekBar sbLives, sbSpeed, sbAltitude, sbSpawnInterval;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        missionVm = new ViewModelProvider(requireActivity()).get(MissionViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mission_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvLives = view.findViewById(R.id.tv_lives_label);
        tvSpeed = view.findViewById(R.id.tv_speed_label);
        tvAltitude = view.findViewById(R.id.tv_altitude_label);
        tvSpawnInterval = view.findViewById(R.id.tv_spawn_interval_label);

        sbLives = view.findViewById(R.id.sb_lives);
        sbSpeed = view.findViewById(R.id.sb_speed);
        sbAltitude = view.findViewById(R.id.sb_altitude);
        sbSpawnInterval = view.findViewById(R.id.sb_spawn_interval);

        missionVm.getMissionState().observe(getViewLifecycleOwner(), this::updateUi);

        sbLives.setOnSeekBarChangeListener(new SimpleOnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    missionVm.dispatch(new MissionIntent.SetMaxLives(progress));
                }
            }
        });

        sbSpeed.setOnSeekBarChangeListener(new SimpleOnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    missionVm.dispatch(new MissionIntent.SetSpeed((float) progress));
                }
            }
        });

        sbAltitude.setOnSeekBarChangeListener(new SimpleOnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    missionVm.dispatch(new MissionIntent.SetAltitude((float) progress));
                }
            }
        });

        sbSpawnInterval.setOnSeekBarChangeListener(new SimpleOnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    missionVm.dispatch(new MissionIntent.SetSpawnInterval((float) progress));
                }
            }
        });
    }

    private void updateUi(Mission mission) {
        if (mission == null) return;

        tvLives.setText("Количество жизней: " + mission.maxLives);
        sbLives.setProgress(mission.maxLives);

        tvSpeed.setText("Скорость (км/ч): " + (int) mission.speedKmh);
        sbSpeed.setProgress((int) mission.speedKmh);

        tvAltitude.setText("Высота (м): " + (int) mission.altitudeMeters);
        sbAltitude.setProgress((int) mission.altitudeMeters);

        tvSpawnInterval.setText("Интервал появления (сек): " + (int) mission.spawnIntervalSeconds);
        sbSpawnInterval.setProgress((int) mission.spawnIntervalSeconds);
    }

    private abstract static class SimpleOnSeekBarChangeListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
