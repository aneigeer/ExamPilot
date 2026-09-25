package com.examprep.app.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.FragmentHomeBinding;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 首页：平台统计 + 刷题入口 + 公告 + 考试类别 + 热门资料 */
public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;
    private ApiService api;
    private ResourceAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        api = ApiClient.api();

        adapter = new ResourceAdapter();
        binding.rvHotResources.setAdapter(adapter);
        binding.rvHotResources.setNestedScrollingEnabled(false);

        binding.refresh.setOnRefreshListener(this::loadHome);
        binding.btnStartPractice.setOnClickListener(v ->
                startActivity(new Intent(getContext(),
                        com.examprep.app.ui.practice.PracticeConfigActivity.class)));
        binding.btnStartMock.setOnClickListener(v -> toast("模拟考试页开发中（下一轮交付）"));

        loadHome();
        return binding.getRoot();
    }

    private void loadHome() {
        binding.refresh.setRefreshing(true);
        api.home().enqueue(new Callback<Models.ApiResult<Models.Home>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.Home>> call,
                                   Response<Models.ApiResult<Models.Home>> resp) {
                binding.refresh.setRefreshing(false);
                Models.ApiResult<Models.Home> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    toast("加载失败");
                    return;
                }
                bind(r.data);
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.Home>> call, Throwable t) {
                binding.refresh.setRefreshing(false);
                toast("网络错误：" + t.getMessage());
            }
        });
    }

    private void bind(Models.Home home) {
        if (home.stats != null) {
            binding.tvStatSubject.setText(String.valueOf(home.stats.subjectCount));
            binding.tvStatQuestion.setText(String.valueOf(home.stats.questionCount));
            binding.tvStatResource.setText(String.valueOf(home.stats.resourceCount));
            binding.tvStatUser.setText(String.valueOf(home.stats.userCount));
        }

        StringBuilder ann = new StringBuilder();
        List<Models.Announcement> announcements = home.announcements;
        if (announcements == null || announcements.isEmpty()) {
            ann.append("暂无公告");
        } else {
            for (Models.Announcement a : announcements) {
                ann.append("· ").append(a.title).append('\n');
            }
        }
        binding.tvAnnouncements.setText(ann.toString());

        StringBuilder exam = new StringBuilder();
        List<Models.ExamBrief> exams = home.exams;
        if (exams == null || exams.isEmpty()) {
            exam.append("暂无考试类别");
        } else {
            for (Models.ExamBrief e : exams) {
                exam.append("· ").append(e.name)
                        .append("（").append(e.subjectCount).append(" 个科目）\n");
            }
        }
        binding.tvExams.setText(exam.toString());

        adapter.submit(home.hotResources == null ? List.of() : home.hotResources);
    }

    private void toast(String msg) {
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
