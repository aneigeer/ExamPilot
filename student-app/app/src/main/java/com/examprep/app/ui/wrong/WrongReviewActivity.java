package com.examprep.app.ui.wrong;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityWrongReviewBinding;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 错题间隔重复复习（SRS）：只练「到期」的错题，按遗忘曲线安排节奏。
 *
 * <p>流程：回忆 → 对答案 → 自评对错 → 服务端推进/重置复习阶段 → 下一题。
 * 走完全部阶段（1/2/4/7/15/30/60 天）即视为掌握，不再进入复习队列。
 */
public class WrongReviewActivity extends AppCompatActivity {

    private static final int LOAD_LIMIT = 50;

    private ActivityWrongReviewBinding binding;
    private ApiService api;
    private List<Models.ReviewItem> queue = List.of();
    private int index = 0;
    private int masteredCount = 0;
    private Models.Question current;
    private String stageInfo = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityWrongReviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnReveal.setOnClickListener(v -> reveal());
        binding.btnCorrect.setOnClickListener(v -> submit(true));
        binding.btnWrong.setOnClickListener(v -> submit(false));
        binding.btnNext.setOnClickListener(v -> advance());

        loadQueue();
    }

    private void loadQueue() {
        api.reviewDue(LOAD_LIMIT).enqueue(new Callback<Models.ApiResult<List<Models.ReviewItem>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<Models.ReviewItem>>> call,
                                   Response<Models.ApiResult<List<Models.ReviewItem>>> resp) {
                Models.ApiResult<List<Models.ReviewItem>> r = resp.body();
                queue = (r != null && r.ok() && r.data != null) ? r.data : List.of();
                if (queue.isEmpty()) {
                    toast("当前没有到期的错题，去刷题或稍后再来");
                    finish();
                    return;
                }
                index = 0;
                masteredCount = 0;
                showCurrent();
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.ReviewItem>>> call, Throwable t) {
                toast("网络错误：" + t.getMessage());
                finish();
            }
        });
    }

    /** 展示当前题：先只给题干与选项，答案与解析留到「对答案」后 */
    private void showCurrent() {
        Models.ReviewItem item = queue.get(index);
        current = null;

        binding.tvReviewProgress.setText(String.format("第 %d / %d 题", index + 1, queue.size()));
        stageInfo = String.format("错了 %d 次 · 复习第 %d/%d 轮",
                item.wrongCount,
                Math.min(item.reviewStage + 1, Math.max(item.totalStages, 1)),
                Math.max(item.totalStages, 1));
        binding.tvReviewType.setText(stageInfo);
        binding.tvReviewStem.setText(item.stem == null ? "" : item.stem);
        binding.tvReviewMaterial.setVisibility(View.GONE);
        binding.tvReviewOptions.setText("");
        binding.cardAnswer.setVisibility(View.GONE);
        binding.tvReviewTip.setVisibility(View.GONE);
        binding.llJudge.setVisibility(View.GONE);
        binding.btnNext.setVisibility(View.GONE);
        binding.btnCorrect.setEnabled(true);
        binding.btnWrong.setEnabled(true);
        binding.btnReveal.setVisibility(View.VISIBLE);
        binding.btnReveal.setEnabled(true);
        binding.btnReveal.setText("对答案");

        // 复习队列是轻量载荷（不含选项），这里补一次题目详情
        api.questionDetail(item.questionId).enqueue(new Callback<Models.ApiResult<Models.Question>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.Question>> call,
                                   Response<Models.ApiResult<Models.Question>> resp) {
                Models.ApiResult<Models.Question> r = resp.body();
                if (r != null && r.ok() && r.data != null) {
                    current = r.data;
                    bindQuestion(current);
                }
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.Question>> call, Throwable t) {
                // 详情拉取失败不阻断复习：仍可用题干 + 服务端正确答案
            }
        });
    }

    private void bindQuestion(Models.Question q) {
        String typeName = q.typeName == null || q.typeName.isEmpty() ? "" : q.typeName + " · ";
        binding.tvReviewType.setText(typeName + stageInfo);
        binding.tvReviewStem.setText(q.stem == null ? "" : q.stem);
        if (q.material != null && !q.material.isEmpty()) {
            binding.tvReviewMaterial.setText("【材料】" + q.material);
            binding.tvReviewMaterial.setVisibility(View.VISIBLE);
        }
        StringBuilder options = new StringBuilder();
        if (q.options != null) {
            for (Models.Option o : q.options) {
                // 刻意不标注正确答案，先让用户自己回忆
                options.append(o.key).append(". ").append(o.content).append('\n');
            }
        }
        binding.tvReviewOptions.setText(options.length() == 0 ? "" : options.toString().trim());
    }

    private void reveal() {
        binding.btnReveal.setVisibility(View.GONE);
        binding.cardAnswer.setVisibility(View.VISIBLE);
        binding.tvReviewAnswer.setText("正确答案：" + buildAnswer());
        binding.tvReviewAnalysis.setText("解析：" + (current == null || current.analysis == null
                || current.analysis.isEmpty() ? "无" : current.analysis));
        binding.llJudge.setVisibility(View.VISIBLE);
    }

    private String buildAnswer() {
        if (current == null) {
            return "见解析";
        }
        if (current.answerContent != null && !current.answerContent.isEmpty()) {
            return current.answerContent;
        }
        StringBuilder sb = new StringBuilder();
        if (current.options != null) {
            for (Models.Option o : current.options) {
                if (Boolean.TRUE.equals(o.isAnswer)) {
                    sb.append(o.key).append(' ');
                }
            }
        }
        return sb.length() == 0 ? "见解析" : sb.toString().trim();
    }

    /** 自评对错并提交给服务端推进 SRS 调度 */
    private void submit(boolean correct) {
        Models.ReviewItem item = queue.get(index);
        binding.llJudge.setVisibility(View.GONE);
        binding.btnCorrect.setEnabled(false);
        binding.btnWrong.setEnabled(false);

        api.submitReview(item.questionId, correct)
                .enqueue(new Callback<Models.ApiResult<Models.ReviewResult>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Models.ReviewResult>> call,
                                           Response<Models.ApiResult<Models.ReviewResult>> resp) {
                        Models.ApiResult<Models.ReviewResult> r = resp.body();
                        if (r != null && r.ok() && r.data != null) {
                            if (Boolean.TRUE.equals(r.data.mastered)) {
                                masteredCount++;
                            }
                            binding.tvReviewTip.setVisibility(View.VISIBLE);
                            binding.tvReviewTip.setText("✓ " + (r.data.message == null ? "" : r.data.message));
                            binding.btnNext.setVisibility(View.VISIBLE);
                            binding.btnNext.setText(index + 1 >= queue.size() ? "完成复习" : "下一题");
                        } else {
                            toast(r == null ? "提交失败" : r.message);
                            binding.llJudge.setVisibility(View.VISIBLE);
                            binding.btnCorrect.setEnabled(true);
                            binding.btnWrong.setEnabled(true);
                        }
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Models.ReviewResult>> call, Throwable t) {
                        toast("网络错误：" + t.getMessage());
                        binding.llJudge.setVisibility(View.VISIBLE);
                        binding.btnCorrect.setEnabled(true);
                        binding.btnWrong.setEnabled(true);
                    }
                });
    }

    private void advance() {
        index++;
        if (index >= queue.size()) {
            toast("复习完成，本次新掌握 " + masteredCount + " 题");
            finish();
            return;
        }
        showCurrent();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
