package com.examprep.app.ui.plan;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.examprep.app.R;
import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityStudyPlanBinding;
import com.examprep.app.ui.practice.PracticeConfigActivity;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 今日学习计划
 *
 * <p><b>为什么这个页面重要</b>：学习计划此前只存在于后端接口，
 * 用户摸不到就不算闭环。本页把「今天该做什么、为什么、做到哪了」三件事讲清楚。
 *
 * <p><b>设计约束</b>：
 * <ul>
 *   <li>任务理由必须显著展示——用户有权知道「为什么给我排这个」；</li>
 *   <li>计划说明（顺延、配额没排完、冷启动）必须展示，不隐藏不利信息；</li>
 *   <li>点任务直接跳到已配好参数的刷题页，避免用户再手工选一遍科目/模式。</li>
 * </ul>
 */
public class StudyPlanActivity extends AppCompatActivity {

    private ActivityStudyPlanBinding binding;
    private ApiService api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityStudyPlanBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnTarget.setOnClickListener(v -> showTargetDialog());
        loadPlan();
    }

    private void loadPlan() {
        binding.tvEmpty.setVisibility(View.VISIBLE);
        binding.tvEmpty.setText("加载中…");
        api.studyPlan().enqueue(new Callback<Models.ApiResult<Models.StudyPlan>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.StudyPlan>> call,
                                   Response<Models.ApiResult<Models.StudyPlan>> resp) {
                Models.ApiResult<Models.StudyPlan> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    showEmpty(r == null ? "计划加载失败" : r.message);
                    return;
                }
                render(r.data);
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.StudyPlan>> call, Throwable t) {
                showEmpty("网络错误：" + t.getMessage());
            }
        });
    }

    private void render(Models.StudyPlan plan) {
        int planned = plan.plannedCount == null ? 0 : plan.plannedCount;
        int done = plan.doneCount == null ? 0 : plan.doneCount;
        int percent = plan.completionPercent == null ? 0 : plan.completionPercent;

        binding.tvProgress.setText("今日 " + done + " / " + planned + " 题");
        binding.tvProgressSub.setText("完成 " + percent + "%　目标 "
                + (plan.dailyTarget == null ? "-" : plan.dailyTarget) + " 题/天"
                + (plan.subjectId == null ? "（不限科目）" : "（指定科目）"));
        binding.pbPlan.setProgress(Math.max(0, Math.min(100, percent)));

        binding.llTasks.removeAllViews();
        List<Models.StudyPlanItem> items = plan.items;
        if (items == null || items.isEmpty()) {
            showEmpty("今天还没有可排的任务\n先在「刷题」里做一组题，明天就能给出针对性计划");
        } else {
            binding.tvEmpty.setVisibility(View.GONE);
            for (Models.StudyPlanItem item : items) {
                binding.llTasks.addView(taskView(item));
            }
        }

        if (plan.notes != null && !plan.notes.isEmpty()) {
            StringBuilder sb = new StringBuilder("说明：");
            for (String note : plan.notes) {
                sb.append("\n· ").append(note);
            }
            binding.tvNotes.setText(sb.toString());
            binding.tvNotes.setVisibility(View.VISIBLE);
        } else {
            binding.tvNotes.setVisibility(View.GONE);
        }
    }

    /**
     * 构造一条任务视图
     *
     * <p>整块可点（≥48dp），点击后带参数跳刷题页；已完成的任务用文字标注
     * （不只靠颜色，符合无障碍要求）。
     */
    private View taskView(Models.StudyPlanItem item) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.color.card);
        int pad = dp(14);
        box.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        boxParams.bottomMargin = dp(10);
        box.setLayoutParams(boxParams);
        box.setMinimumHeight(dp(48));

        TextView title = new TextView(this);
        String name = item.knowledgeName == null ? "任务" : item.knowledgeName;
        title.setText(name + "　" + (item.targetCount == null ? 0 : item.targetCount) + " 题"
                + (Boolean.TRUE.equals(item.completed) ? "　✓ 已完成" : ""));
        title.setTextSize(15);
        title.setTextColor(getColor(R.color.text_main));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        box.addView(title);

        TextView progress = new TextView(this);
        int target = item.targetCount == null ? 0 : item.targetCount;
        int done = item.doneCount == null ? 0 : item.doneCount;
        progress.setText("进度 " + done + " / " + target
                + "　预计 " + (item.estimatedMinutes == null ? 0 : item.estimatedMinutes) + " 分钟");
        progress.setTextSize(13);
        progress.setTextColor(getColor(R.color.text_sub));
        progress.setPadding(0, dp(4), 0, 0);
        box.addView(progress);

        TextView reason = new TextView(this);
        reason.setText("为什么： " + (item.reason == null ? "" : item.reason));
        reason.setTextSize(13);
        reason.setTextColor(getColor(R.color.text_sub));
        reason.setPadding(0, dp(4), 0, 0);
        box.addView(reason);

        // 无障碍：把整块任务的语义合成一句，避免读屏时碎片化朗读
        box.setContentDescription(name + "，" + target + " 题，已完成 " + done + " 题。"
                + "原因：" + (item.reason == null ? "" : item.reason));
        box.setClickable(true);
        box.setFocusable(true);
        box.setOnClickListener(v -> startPractice(item));
        return box;
    }

    /** 带参数跳到刷题页（任务里已经给出建议模式，用户不必再选一遍） */
    private void startPractice(Models.StudyPlanItem item) {
        if ("REVIEW_DUE".equals(item.type)) {
            startActivity(new Intent(this, com.examprep.app.ui.wrong.WrongBookActivity.class));
            return;
        }
        Intent intent = new Intent(this, PracticeConfigActivity.class);
        intent.putExtra("presetMode", item.practiceMode);
        intent.putExtra("presetKnowledgeId", item.knowledgeId);
        intent.putExtra("presetCount", item.targetCount);
        startActivity(intent);
    }

    /** 修改每日目标：直接输入题量，服务端会做区间钳制 */
    private void showTargetDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("每日目标题量（5-100）");
        new AlertDialog.Builder(this)
                .setTitle("设定每日目标")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    int target;
                    try {
                        target = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        toast("请输入数字");
                        return;
                    }
                    api.setStudyTarget(new ApiService.StudyTargetBody(target, null))
                            .enqueue(new Callback<Models.ApiResult<Models.StudyPlan>>() {
                                @Override
                                public void onResponse(Call<Models.ApiResult<Models.StudyPlan>> call,
                                                       Response<Models.ApiResult<Models.StudyPlan>> resp) {
                                    Models.ApiResult<Models.StudyPlan> r = resp.body();
                                    if (r == null || !r.ok() || r.data == null) {
                                        toast(r == null ? "保存失败" : r.message);
                                        return;
                                    }
                                    // 用服务端返回的生效值回显，让「填 200 变 100」这件事可见
                                    toast("目标已设为每天 " + r.data.dailyTarget + " 题");
                                    render(r.data);
                                }

                                @Override
                                public void onFailure(Call<Models.ApiResult<Models.StudyPlan>> call,
                                                      Throwable t) {
                                    toast("网络错误：" + t.getMessage());
                                }
                            });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showEmpty(String message) {
        binding.tvEmpty.setVisibility(View.VISIBLE);
        binding.tvEmpty.setText(message);
        binding.llTasks.removeAllViews();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
