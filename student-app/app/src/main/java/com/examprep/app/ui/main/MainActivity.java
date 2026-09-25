package com.examprep.app.ui.main;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.examprep.app.R;
import com.examprep.app.databinding.ActivityMainBinding;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/** 主框架：底部导航切换 4 个 Fragment */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        BottomNavigationView nav = binding.bottomNav;
        nav.setOnItemSelectedListener(item -> {
            Fragment f;
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                f = new HomeFragment();
            } else if (id == R.id.nav_course) {
                f = new CourseFragment();
            } else if (id == R.id.nav_resource) {
                f = new ResourceFragment();
            } else {
                f = new MeFragment();
            }
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragmentContainer, f)
                    .commit();
            return true;
        });
        nav.setSelectedItemId(R.id.nav_home);
    }
}
