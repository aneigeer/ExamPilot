package com.examprep.app.ui.main;

import android.content.Intent;
import android.text.InputType;
import android.widget.EditText;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
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

        binding.tvServer.setText("服务器地址：" + ApiClient.getBaseUrl(requireContext()));
        binding.tvServer.setOnClickListener(v -> showServerConfigDialog());

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
                    if (binding == null || !isAdded()) return; // Activity 可能已销毁，跳过跳转登录页的 UI 操作
                    goLogin();
                }

                @Override
                public void onFailure(Call<Models.ApiResult<Object>> call, Throwable t) {
                    store.clear();
                    if (binding == null || !isAdded()) return;
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
        // D14 上传资料双入口之二：与首页 FAB 一致跳转 UploadActivity；游客沿用 store.hasToken()/goLogin() 锁定上传
        binding.btnUploadResource.setOnClickListener(v -> {
            if (!store.hasToken()) {
                toast("游客不可上传，请先登录");
                goLogin();
                return;
            }
            startActivity(new Intent(requireContext(),
                    com.examprep.app.ui.upload.UploadActivity.class));
        });
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
                if (binding == null || !isAdded()) return; // 异步响应到达时页面可能已销毁
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
                if (binding == null || !isAdded()) return;
                toast("统计加载失败");
            }
        });
    }

    private void open(Class<?> clazz) {
        startActivity(new Intent(requireContext(), clazz));
    }

    /** 「我的」内切换服务器：保存后清登录态，回到登录页重新认证 */
    private void showServerConfigDialog() {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(ApiClient.getBaseUrl(requireContext()));
        input.setSelection(input.getText().length());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new AlertDialog.Builder(requireContext())
                .setTitle("服务器地址设置")
                .setMessage("切换后需重新登录（地址须以 http:// 或 https:// 开头）")
                .setView(input)
                .setPositiveButton("保存并重新登录", (dialog, which) -> {
                    String url = String.valueOf(input.getText()).trim();
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        toast("地址须以 http:// 或 https:// 开头");
                        return;
                    }
                    ApiClient.setBaseUrl(requireContext(), url);
                    TokenStore.get(requireContext()).clear();
                    toast("已切换服务器：" + ApiClient.getBaseUrl(requireContext()));
                    goLogin();
                })
                .setNeutralButton("恢复默认", (dialog, which) -> {
                    ApiClient.setBaseUrl(requireContext(), null);
                    TokenStore.get(requireContext()).clear();
                    toast("已恢复默认地址：" + ApiClient.getBaseUrl(requireContext()));
                    goLogin();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void goLogin() {
        Intent intent = new Intent(requireContext(), LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    private void toast(String msg) {
        if (binding == null || !isAdded()) return; // 页面已离开时 getContext() 为 null，忽略以防空指针闪退
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
