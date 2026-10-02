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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.data.prefs.TokenStore;
import com.examprep.app.databinding.FragmentHomeBinding;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 首页：渐变头部（问候+搜索）+ 平台统计 + 刷题入口 + 公告 + 考试类别 + 热门资料。
 * 所有跳转与游客拦截逻辑与旧版保持一致。
 */
public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;
    private ApiService api;
    private ResourceAdapter adapter;
    private HomeExamAdapter examAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        api = ApiClient.api();

        adapter = new ResourceAdapter();
        adapter.setOnClick(r -> openResource(r.id));
        binding.rvHotResources.setAdapter(adapter);
        binding.rvHotResources.setNestedScrollingEnabled(false);

        examAdapter = new HomeExamAdapter();
        examAdapter.setOnClick(this::openExam);
        binding.rvExams.setLayoutManager(new LinearLayoutManager(
                getContext(), RecyclerView.HORIZONTAL, false));
        binding.rvExams.setAdapter(examAdapter);
        binding.rvExams.setNestedScrollingEnabled(false);

        binding.refresh.setOnRefreshListener(this::loadHome);
        binding.btnSearch.setOnClickListener(v -> startActivity(
                new Intent(getContext(), com.examprep.app.ui.search.SearchActivity.class)));
        binding.fabUpload.setOnClickListener(v -> {
            if (!com.examprep.app.data.prefs.TokenStore.get(requireContext()).hasToken()) {
                toast("游客不可上传，请先登录");
                startActivity(new Intent(getContext(), com.examprep.app.ui.login.LoginActivity.class));
                return;
            }
            startActivity(new Intent(getContext(), com.examprep.app.ui.upload.UploadActivity.class));
        });
        binding.btnStartPractice.setOnClickListener(v ->
                startActivity(new Intent(getContext(),
                        com.examprep.app.ui.practice.PracticeConfigActivity.class)));
        binding.btnStartMock.setOnClickListener(v ->
                startActivity(new Intent(getContext(),
                        com.examprep.app.ui.mock.MockExamActivity.class)));

        loadHome();
        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 登录/退出后回到首页时刷新问候语
        if (binding != null) {
            updateGreeting();
        }
    }

    /** 登录用户显示「你好，昵称」，游客显示「你好，游客」 */
    private void updateGreeting() {
        TokenStore ts = TokenStore.get(requireContext());
        binding.tvGreeting.setText(ts.hasToken()
                ? "你好，" + ts.userName() : "你好，游客");
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
        updateGreeting();

        if (home.stats != null) {
            binding.tvStatSubject.setText(String.valueOf(home.stats.subjectCount));
            binding.tvStatQuestion.setText(String.valueOf(home.stats.questionCount));
            binding.tvStatResource.setText(String.valueOf(home.stats.resourceCount));
            binding.tvStatUser.setText(String.valueOf(home.stats.userCount));
        }

        // 公告：只展示最新 1 条
        List<Models.Announcement> announcements = home.announcements;
        if (announcements == null || announcements.isEmpty()) {
            binding.tvAnnouncements.setText("暂无公告");
        } else {
            binding.tvAnnouncements.setText(
                    "· " + announcements.get(0).title);
        }

        // 考试类别：横滑卡片；无数据时显示文字提示
        List<Models.ExamBrief> exams = home.exams;
        if (exams == null || exams.isEmpty()) {
            binding.tvExams.setVisibility(View.VISIBLE);
            binding.tvExams.setText("暂无考试类别");
            binding.rvExams.setVisibility(View.GONE);
        } else {
            binding.tvExams.setVisibility(View.GONE);
            binding.rvExams.setVisibility(View.VISIBLE);
            examAdapter.submit(exams);
        }

        adapter.submit(home.hotResources == null ? List.of() : home.hotResources);
    }

    /** 考试类别卡片：切到题库 tab 并直达该类别科目列表 */
    private void openExam(Models.ExamBrief exam) {
        if (exam.id == null || !(getActivity() instanceof MainActivity)) {
            return;
        }
        ((MainActivity) requireActivity()).openExamSubjects(exam.id, exam.name);
    }

    private void openResource(long id) {
        Intent intent = new Intent(getContext(),
                com.examprep.app.ui.resource.ResourceDetailActivity.class);
        intent.putExtra("resourceId", id);
        startActivity(intent);
    }

    private void toast(String msg) {
        if (!isAdded()) return; // 页面已离开时 getContext() 为 null，忽略以防空指针闪退
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
