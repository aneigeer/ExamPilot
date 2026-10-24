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

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.data.prefs.TokenStore;
import com.examprep.app.databinding.FragmentMeBinding;
import com.examprep.app.ui.login.LoginActivity;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 我的：登录信息 + 学习统计 + 功能入口 */
public class MeFragment extends Fragment {

    private FragmentMeBinding binding;
    private ApiService api;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMeBinding.inflate(inflater, container, false);
        api = ApiClient.api();

        TokenStore store = TokenStore.get(requireContext());
        binding.tvNickname.setText(store.hasToken() ? store.userName() : "游客");
        binding.tvStudentNo.setText(store.hasToken() ? "学号 " + store.userNo() : "游客模式 · 登录后可刷题");

        binding.btnLogout.setOnClickListener(v -> {
            if (!store.hasToken()) {
                toast("当前是游客模式");
                return;
            }
            api.logout().enqueue(new Callback<Models.ApiResult<Object>>() {
                @Override
                public void onResponse(Call<Models.ApiResult<Object>> call,
                                       Response<Models.ApiResult<Object>> resp) {
                    store.clear(); // 清除本地凭证与页面是否存活无关，必须执行
                    if (!isAdded()) return; // Activity 可能已销毁，跳过跳转登录页的 UI 操作
                    goLogin();
                }

                @Override
                public void onFailure(Call<Models.ApiResult<Object>> call, Throwable t) {
                    store.clear();
                    if (!isAdded()) return;
                    goLogin();
                }
            });
        });

        binding.btnWiki.setOnClickListener(v -> open(com.examprep.app.ui.wiki.WikiListActivity.class));
        binding.btnStudyPlan.setOnClickListener(v -> open(com.examprep.app.ui.plan.StudyPlanActivity.class));
        binding.btnWrongBook.setOnClickListener(v -> open(com.examprep.app.ui.wrong.WrongBookActivity.class));
        binding.btnFavorites.setOnClickListener(v -> open(com.examprep.app.ui.list.FavoritesActivity.class));
        binding.btnDownloads.setOnClickListener(v -> open(com.examprep.app.ui.list.DownloadsActivity.class));
        binding.btnNotifications.setOnClickListener(v -> open(com.examprep.app.ui.list.NotificationsActivity.class));
        binding.btnNote.setOnClickListener(v -> open(com.examprep.app.ui.note.NoteActivity.class));
        binding.btnFavQuestion.setOnClickListener(v -> open(com.examprep.app.ui.list.FavoriteQuestionActivity.class));
        binding.btnUploadRecords.setOnClickListener(v -> open(com.examprep.app.ui.list.UploadRecordsActivity.class));
        binding.btnFollows.setOnClickListener(v -> open(com.examprep.app.ui.follow.FollowActivity.class));
        binding.btnAppeals.setOnClickListener(v -> open(com.examprep.app.ui.appeal.AppealActivity.class));
        binding.btnAccount.setOnClickListener(v -> open(com.examprep.app.ui.account.AccountActivity.class));

        if (store.hasToken()) {
            loadStats();
        } else {
            binding.tvPractice.setText("-");
            binding.tvAccuracy.setText("-");
            binding.tvWrong.setText("-");
            binding.tvFav.setText("-");
        }
        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null; // 释放视图绑定，避免内存泄漏与操作已销毁视图
    }

    private void loadStats() {
        api.myStats().enqueue(new Callback<Models.ApiResult<Models.MyStats>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.MyStats>> call,
                                   Response<Models.ApiResult<Models.MyStats>> resp) {
                if (!isAdded()) return; // 异步响应到达时页面可能已销毁
                Models.ApiResult<Models.MyStats> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    return;
                }
                binding.tvPractice.setText(String.valueOf(r.data.practiceCount));
                binding.tvAccuracy.setText(String.format("%.1f%%", r.data.accuracy));
                binding.tvWrong.setText(String.valueOf(r.data.wrongBookCount));
                binding.tvFav.setText(String.valueOf(
                        r.data.favoriteResourceCount + r.data.favoriteQuestionCount));
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.MyStats>> call, Throwable t) {
                if (!isAdded()) return;
                toast("统计加载失败");
            }
        });
    }

    private void open(Class<?> clazz) {
        startActivity(new Intent(requireContext(), clazz));
    }

    private void goLogin() {
        Intent intent = new Intent(requireContext(), LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    private void toast(String msg) {
        if (!isAdded()) return; // 页面已离开时 getContext() 为 null，忽略以防空指针闪退
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
