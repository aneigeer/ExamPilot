package com.examprep.app.ui.interview;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityInterviewDetailBinding;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 面试详情（只读复盘）
 *
 * <p>顶部是会话摘要（总分 / 等级 / 实评题数 / 时间），下面是逐题评分卡。
 * 评分卡直接复用面试页的 {@code item_interview_score} 布局与字段口径，
 * 用户在看"当时的评分"和"现在的复盘"时不会看到两套不同的展示语言。
 */
public class InterviewDetailActivity extends AppCompatActivity {

    private ActivityInterviewDetailBinding binding;
    private ApiService api;
    private InterviewQaAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityInterviewDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();

        long sessionId = getIntent().getLongExtra("sessionId", 0L);
        if (sessionId <= 0) {
            toast("记录不存在");
            finish();
            return;
        }

        adapter = new InterviewQaAdapter();
        binding.rvQa.setLayoutManager(new LinearLayoutManager(this));
        binding.rvQa.setAdapter(adapter);
        binding.btnBack.setOnClickListener(v -> finish());

        load(sessionId);
    }

    private void load(long sessionId) {
        api.interviewSessionDetail(sessionId).enqueue(
                new Callback<Models.ApiResult<Models.InterviewSessionDetail>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Models.InterviewSessionDetail>> call,
                                           Response<Models.ApiResult<Models.InterviewSessionDetail>> resp) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        Models.ApiResult<Models.InterviewSessionDetail> r = resp.body();
                        if (r == null || !r.ok() || r.data == null) {
                            // 403 = 别人的记录；404 = 已删除。文案要区分，别都说"加载失败"
                            toast(resp.code() == 403 ? "无权查看该面试记录"
                                    : resp.code() == 404 ? "面试记录不存在" : "加载失败，请重试");
                            return;
                        }
                        bind(r.data);
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Models.InterviewSessionDetail>> call,
                                          Throwable t) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        toast("网络错误：" + (t.getMessage() == null ? "请重试" : t.getMessage()));
                    }
                });
    }

    private void bind(Models.InterviewSessionDetail d) {
        binding.tvTitle.setText(d.topic == null || d.topic.isEmpty() ? "面试复盘" : d.topic);
        binding.tvSummaryScore.setText(d.totalScore == null
                ? "--" : String.valueOf(Math.round(d.totalScore)));
        binding.tvSummaryLevel.setText(d.level == null ? "进行中" : d.level);

        int answered = d.qaList == null ? 0 : d.qaList.size();
        StringBuilder meta = new StringBuilder();
        meta.append("实评 ").append(answered);
        if (d.totalQuestions != null) {
            meta.append(" / 计划 ").append(d.totalQuestions);
        }
        meta.append(" 题");
        if (!TextUtils.isEmpty(d.startedAt)) {
            meta.append(" · ").append(d.startedAt.replace('T', ' '));
        }
        if (Integer.valueOf(1).equals(d.finished)) {
            meta.append(" · 已结束");
        }
        binding.tvSummaryMeta.setText(meta.toString());
        adapter.submit(d.qaList == null ? java.util.List.of() : d.qaList);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
