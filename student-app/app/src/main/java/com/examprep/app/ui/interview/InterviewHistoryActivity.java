package com.examprep.app.ui.interview;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.examprep.app.R;
import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityInterviewHistoryBinding;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 面试历史列表
 *
 * <p><b>为什么要做这个页面</b>：面试记录以前只活在 AI 服务进程内存里（30 分钟 TTL），
 * 面完就没了。落库后学生能复盘「上次哪题丢分、面试官追问了什么」，
 * 这也是学情画像的第一手数据。
 *
 * <p>列表只显示「主题 · 分数 · 时间」——信息密度足够判断「要不要点进去看」，
 * 逐题细节在详情页。空态与失败都有明确文案，不给白屏。
 */
public class InterviewHistoryActivity extends AppCompatActivity {

    private ActivityInterviewHistoryBinding binding;
    private ApiService api;
    private HistoryAdapter adapter;
    private int page = 1;
    private static final int PAGE_SIZE = 20;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityInterviewHistoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();

        adapter = new HistoryAdapter(item -> {
            startActivity(new Intent(this, InterviewDetailActivity.class)
                    .putExtra("sessionId", item.id));
        });
        binding.rvHistory.setLayoutManager(new LinearLayoutManager(this));
        binding.rvHistory.setAdapter(adapter);
        binding.btnBack.setOnClickListener(v -> finish());
        binding.refresh.setOnRefreshListener(this::load);

        load();
    }

    private void load() {
        binding.progress.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        api.interviewHistory(page, PAGE_SIZE).enqueue(
                new Callback<Models.ApiResult<Models.Page<Models.InterviewHistoryItem>>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Models.Page<Models.InterviewHistoryItem>>> call,
                                           Response<Models.ApiResult<Models.Page<Models.InterviewHistoryItem>>> resp) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        binding.progress.setVisibility(View.GONE);
                        binding.refresh.setRefreshing(false);
                        Models.ApiResult<Models.Page<Models.InterviewHistoryItem>> r = resp.body();
                        if (r == null || !r.ok() || r.data == null) {
                            toast(resp.code() == 401 ? "请先登录" : "加载失败，请下拉重试");
                            updateEmptyState();
                            return;
                        }
                        List<Models.InterviewHistoryItem> list =
                                r.data.list == null ? java.util.Collections.emptyList() : r.data.list;
                        adapter.submit(list);
                        updateEmptyState();
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Models.Page<Models.InterviewHistoryItem>>> call,
                                          Throwable t) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        binding.progress.setVisibility(View.GONE);
                        binding.refresh.setRefreshing(false);
                        toast("网络错误，请下拉重试");
                        updateEmptyState();
                    }
                });
    }

    private void updateEmptyState() {
        boolean empty = adapter.getItemCount() == 0;
        binding.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.rvHistory.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------

    /** 列表适配器（单 ViewType，逻辑极简：主题 + 分数 + 时间） */
    static class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.Holder> {

        interface OnClick {
            void onClick(Models.InterviewHistoryItem item);
        }

        private final List<Models.InterviewHistoryItem> items = new ArrayList<>();
        private final OnClick onClick;

        HistoryAdapter(OnClick onClick) {
            this.onClick = onClick;
        }

        void submit(List<Models.InterviewHistoryItem> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_interview_history, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Models.InterviewHistoryItem item = items.get(position);
            holder.tvTopic.setText(item.topic == null || item.topic.isEmpty()
                    ? "随机主题" : item.topic);
            // 实评题数 vs 计划题数分开显示：提前收尾时两者不同，用户不该以为漏算了
            String score = item.totalScore == null ? "进行中" : String.valueOf(Math.round(item.totalScore));
            holder.tvScore.setText(score);
            String level = item.level == null ? "" : " · " + item.level;
            String count = "实评 " + (item.answeredCount == null ? 0 : item.answeredCount)
                    + (item.totalQuestions == null ? "" : " / 计划 " + item.totalQuestions) + " 题";
            String time = item.startedAt == null ? "" : " · " + item.startedAt.replace('T', ' ');
            holder.tvMeta.setText(count + level + time);
            holder.itemView.setContentDescription("面试记录：" + holder.tvTopic.getText()
                    + "，得分 " + score + "，" + holder.tvMeta.getText());
            holder.itemView.setOnClickListener(v -> onClick.onClick(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final TextView tvTopic;
            final TextView tvMeta;
            final TextView tvScore;

            Holder(@NonNull View itemView) {
                super(itemView);
                tvTopic = itemView.findViewById(R.id.tvTopic);
                tvMeta = itemView.findViewById(R.id.tvMeta);
                tvScore = itemView.findViewById(R.id.tvScore);
            }
        }
    }
}
