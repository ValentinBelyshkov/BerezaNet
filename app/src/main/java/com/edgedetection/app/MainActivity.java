package com.edgedetection.app;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.edgedetection.R;
import com.edgedetection.core.ScreenMode;
import com.edgedetection.ui.battle.BattleFragment;
import com.edgedetection.ui.planner.MissionPlannerFragment;
import com.edgedetection.ui.settings.SettingsFragment;
import com.edgedetection.ui.shared.MissionViewModel;
import com.edgedetection.ui.test3d.Test3DFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.maplibre.android.MapLibre;

public class MainActivity extends AppCompatActivity {
    private AppViewModel appViewModel;
    private BottomNavigationView bottomNav;
    private MissionViewModel missionViewModel;
    private View contentContainer;
    private Fragment currentFragment = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MapLibre.getInstance(getApplicationContext());
        setContentView(R.layout.activity_main);

        contentContainer = findViewById(R.id.content_container);
        bottomNav = findViewById(R.id.bottom_nav);
        appViewModel = new ViewModelProvider(this).get(AppViewModel.class);
        missionViewModel = new ViewModelProvider(this).get(MissionViewModel.class);

        if (savedInstanceState == null) {
            showFragment("battle", new BattleFragment(), ScreenMode.FULL_BATTLE);
        } else {
            ScreenMode mode = appViewModel.getScreenMode().getValue();
            String tag = mode != null ? getTagForMode(mode) : "battle";
            restoreFragment(tag);
        }

        setupNavigation();
        observeViewModel();
    }

    private void setupNavigation() {
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_battle) {
                showFragment("battle", new BattleFragment(), ScreenMode.FULL_BATTLE);
                return true;
            } else if (id == R.id.nav_planner) {
                showFragment("planner", new MissionPlannerFragment(), ScreenMode.FULL_PLANNER);
                return true;
            } else if (id == R.id.nav_test3d) {
                showFragment("test3d", new Test3DFragment(), ScreenMode.FULL_TEST3D);
                return true;
            } else if (id == R.id.nav_settings) {
                showFragment("settings", new SettingsFragment(), ScreenMode.FULL_SETTINGS);
                return true;
            }
            return false;
        });
    }

    private void observeViewModel() {
        appViewModel.getScreenMode().observe(this, this::updateUiForMode);
    }

    private void showFragment(String tag, Fragment fragment, ScreenMode mode) {
        contentContainer.setVisibility(View.VISIBLE);

        Fragment existing = getSupportFragmentManager().findFragmentByTag(tag);
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        if (existing == null) {
            ft.add(R.id.content_container, fragment, tag);
        } else {
            if (existing.isDetached()) {
                ft.attach(existing);
            } else if (existing != currentFragment) {
                ft.show(existing);
            }
        }

        if (currentFragment != null && currentFragment != existing) {
            ft.hide(currentFragment);
        }

        ft.commitNow();
        currentFragment = existing != null ? existing : fragment;
        appViewModel.setScreenMode(mode);
    }

    private void restoreFragment(String tag) {
        Fragment f = getSupportFragmentManager().findFragmentByTag(tag);
        if (f == null) {
            if ("battle".equals(tag)) {
                showFragment("battle", new BattleFragment(), ScreenMode.FULL_BATTLE);
            } else if ("planner".equals(tag)) {
                showFragment("planner", new MissionPlannerFragment(), ScreenMode.FULL_PLANNER);
            } else if ("test3d".equals(tag)) {
                showFragment("test3d", new Test3DFragment(), ScreenMode.FULL_TEST3D);
            } else if ("settings".equals(tag)) {
                showFragment("settings", new SettingsFragment(), ScreenMode.FULL_SETTINGS);
            } else {
                showFragment("battle", new BattleFragment(), ScreenMode.FULL_BATTLE);
            }
            return;
        }

        if (f.isDetached()) {
            getSupportFragmentManager().beginTransaction().attach(f).commitNow();
        } else {
            getSupportFragmentManager().beginTransaction().show(f).commitNow();
        }
        currentFragment = f;
    }

    private String getTagForMode(ScreenMode mode) {
        if (mode == ScreenMode.FULL_BATTLE) return "battle";
        if (mode == ScreenMode.FULL_PLANNER) return "planner";
        if (mode == ScreenMode.FULL_TEST3D) return "test3d";
        if (mode == ScreenMode.FULL_SETTINGS) return "settings";
        return "battle";
    }

    private void updateUiForMode(ScreenMode mode) {
        if (mode == null) return;
        int menuId = -1;
        if (mode == ScreenMode.FULL_BATTLE) menuId = R.id.nav_battle;
        else if (mode == ScreenMode.FULL_PLANNER) menuId = R.id.nav_planner;
        else if (mode == ScreenMode.FULL_TEST3D) menuId = R.id.nav_test3d;
        else if (mode == ScreenMode.FULL_SETTINGS) menuId = R.id.nav_settings;

        if (menuId != -1 && bottomNav.getSelectedItemId() != menuId) {
            bottomNav.setSelectedItemId(menuId);
        }
    }

    @Override
    public void onBackPressed() {
        ScreenMode current = appViewModel.getScreenMode().getValue();
        if (current != ScreenMode.FULL_BATTLE) {
            bottomNav.setSelectedItemId(R.id.nav_battle);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
    }
}