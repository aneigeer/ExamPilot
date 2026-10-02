package com.examprep.app.ui.main;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.examprep.app.R;
import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.model.Models;
import com.examprep.app.data.prefs.TokenStore;
import com.examprep.app.databinding.ActivityMainBinding;
import com.examprep.app.ui.bank.QuestionBankFragment;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
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
        nav.setOnItemSelectedListener(this::onNavItemSelected);
        nav.setSelectedItemId(R.id.nav_home);
    }

    private boolean onNavItemSelected(@NonNull MenuItem item) {
        Fragment f;
        int id = item.getItemId();
        if (id == R.id.nav_home) {
            f = new HomeFragment();
        } else if (id == R.id.nav_bank) {
            f = new QuestionBankFragment();
        } else if (id == R.id.nav_course) {
            f = new CourseFragment();
        } else {
            f = new MeFragment();
        }
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragmentContainer, f)
                .commit();
        return true;
    }

    /** 首页考试类别卡片入口：切到题库 tab 并直达指定类别科目列表 */
    public void openExamSubjects(long examId, String examName) {
        BottomNavigationView nav = binding.bottomNav;
        // 先静默切换选中态（不触发 replace），再带上参数替换 Fragment
        nav.setOnItemSelectedListener(null);
        nav.setSelectedItemId(R.id.nav_bank);
        nav.setOnItemSelectedListener(this::onNavItemSelected);

        QuestionBankFragment f = new QuestionBankFragment();
        Bundle args = new Bundle();
        args.putLong("examId", examId);
        args.putString("examName", examName);
        f.setArguments(args);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragmentContainer, f)
                .commit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBadge();
    }

    /** 通知未读角标（大纲 7.2 BadgeView） */
    private void refreshBadge() {
        if (!TokenStore.get(this).hasToken()) {
            binding.bottomNav.removeBadge(R.id.nav_me);
            return;
        }
        ApiClient.api().unreadCount().enqueue(new Callback<Models.ApiResult<Long>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Long>> call, Response<Models.ApiResult<Long>> resp) {
                Models.ApiResult<Long> r = resp.body();
                if (r != null && r.ok() && r.data != null && r.data > 0) {
                    binding.bottomNav.getOrCreateBadge(R.id.nav_me).setNumber(r.data.intValue());
                } else {
                    binding.bottomNav.removeBadge(R.id.nav_me);
                }
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Long>> call, Throwable t) {
            }
        });
    }
}
