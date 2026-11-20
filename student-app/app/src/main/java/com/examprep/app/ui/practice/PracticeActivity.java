package com.examprep.app.ui.practice;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityPracticeBinding;
import com.google.android.material.button.MaterialButton;

import java.util.LinkedHashMap;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 刷题页：出题 → 作答（选项点击 / 填空输入 / 组合大题）→ 判分 → 自评 → 下一题 → 报告 */
public class PracticeActivity extends AppCompatActivity {

    private ActivityPracticeBinding binding;
    private ApiService api;
    private long practiceId;

    private int answeredCount;
    private int correctCount;
    private int totalCount;
    private Models.Question current;
    /** 当前状态：true=已提交待看结果，false=待作答 */
    private boolean judged;
    /** 单选/判断题当前选中的选项 */
    private String singleChoiceAnswer;
    /** 多选题 / 组合大题的暂存作答 */
    private final Map<String, Boolean> multiSelection = new LinkedHashMap<>();
    private final Map<Long, String> subSelection = new LinkedHashMap<>();
    /** 已答对但需自评的题未自评数 */
    private int pendingSelfEval;
    private boolean finishing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPracticeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();
        practiceId = getIntent().getLongExtra("practiceId", 0);

        binding.btnFinish.setOnClickListener(v -> confirmFinish());
        binding.btnCard.setOnClickListener(v -> showCardSheet());
        binding.btnAction.setOnClickListener(v -> onAction());
        loadNext();
    }

    private void loadNext() {
        if (current != null) {
            saveDraft();
        }
        judged = false;
        multiSelection.clear();
        subSelection.clear();
        singleChoiceAnswer = null;
        binding.llResult.setVisibility(View.GONE);
        binding.btnAction.setEnabled(false);

        api.practiceNext(practiceId).enqueue(new Callback<Models.ApiResult<ApiService.NextQuestion>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<ApiService.NextQuestion>> call,
                                   Response<Models.ApiResult<ApiService.NextQuestion>> resp) {
                Models.ApiResult<ApiService.NextQuestion> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    toast("取题失败");
                    return;
                }
                totalCount = r.data.totalCount;
                answeredCount = r.data.answeredCount;
                correctCount = r.data.correctCount;
                updateProgress();
                if (r.data.finished || r.data.question == null) {
                    finishToReport();
                    return;
                }
                render(r.data.question);
            }

            @Override
            public void onFailure(Call<Models.ApiResult<ApiService.NextQuestion>> call, Throwable t) {
                toast("网络错误：" + t.getMessage());
            }
        });
    }

    private void render(Models.Question q) {
        current = q;
        binding.tvTypeName.setText(q.typeName == null ? q.type : q.typeName);
        binding.tvStem.setText(q.stem);
        binding.tvMaterial.setVisibility(q.material == null ? View.GONE : View.VISIBLE);
        binding.tvMaterial.setText(q.material);

        binding.llAnswerArea.removeAllViews();
        binding.btnAction.setText("提交答案");
        binding.btnAction.setEnabled(false);

        if (QuestionTypes.isMatching(q.type)) {
            addInput("按 A:1,B:3 格式作答", false);
            binding.btnAction.setEnabled(true);
        } else if (QuestionTypes.isOrdering(q.type)) {
            addInput("按 C,A,B 格式给出顺序", false);
            binding.btnAction.setEnabled(true);
        } else if (QuestionTypes.isComposite(q.type)) {
            for (Models.SubQ sub : q.subQuestions == null
                    ? java.util.Collections.<Models.SubQ>emptyList() : q.subQuestions) {
                addSubQuestion(sub);
            }
            binding.btnAction.setEnabled(q.subQuestions != null && !q.subQuestions.isEmpty());
        } else if ("FILL_BLANK".equals(q.type) || QuestionTypes.isSubjective(q.type)) {
            boolean subjective = QuestionTypes.isSubjective(q.type);
            EditText et = addInput(subjective ? "输入你的回答（自动保存草稿）" : "多个空用 | 分隔", subjective);
            if (subjective) {
                restoreDraft(et);
            }
            binding.btnAction.setEnabled(true);
        } else {
            // 单选 / 判断 / 多选：动态选项按钮
            multiSelection.clear();
            if (q.options != null) {
                for (Models.Option o : q.options) {
                    binding.llAnswerArea.addView(optionButton(o, "MULTI_CHOICE".equals(q.type)));
                }
            }
        }
    }

    private View optionButton(Models.Option o, boolean multi) {
        MaterialButton btn = new MaterialButton(this);
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) btn.getLayoutParams()).topMargin = dp(6);
        btn.setText(o.key + ". " + o.content);
        btn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        btn.setPadding(dp(14), 0, dp(14), 0);
        btn.setOnClickListener(v -> {
            if (!multi) {
                // A-01：单选题选中后直接允许提交，不能再被下面的多选判断覆盖
                singleChoiceAnswer = o.key;
                binding.btnAction.setEnabled(true);
                return;
            }
            boolean now = !Boolean.TRUE.equals(multiSelection.get(o.key));
            multiSelection.put(o.key, now);
            btn.setStrokeWidth(now ? dp(2) : 0);
            // A-01：按「是否存在值为 TRUE 的项」判断；put(key,false) 会保留键，不能用 isEmpty()
            boolean anySelected = false;
            for (Boolean selected : multiSelection.values()) {
                if (Boolean.TRUE.equals(selected)) {
                    anySelected = true;
                    break;
                }
            }
            binding.btnAction.setEnabled(anySelected);
        });
        return btn;
    }

    private void addSubQuestion(Models.SubQ sub) {
        TextView title = new TextView(this);
        title.setText(sub.typeName + "：" + sub.stem);
        title.setTextSize(14);
        title.setTextColor(0xFF1B1F27);
        title.setPadding(0, dp(10), 0, dp(2));
        binding.llAnswerArea.addView(title);

        if (sub.options != null) {
            for (Models.Option o : sub.options) {
                MaterialButton btn = new MaterialButton(this);
                btn.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                ((LinearLayout.LayoutParams) btn.getLayoutParams()).topMargin = dp(4);
                btn.setText(o.key + ". " + o.content);
                btn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                btn.setOnClickListener(v -> {
                    subSelection.put(sub.id, o.key);
                    binding.btnAction.setEnabled(subSelection.size() >= 1);
                });
                binding.llAnswerArea.addView(btn);
            }
        }
    }

    private EditText addInput(String hint, boolean multiLine) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setBackground(getDrawable(android.R.drawable.editbox_background));
        et.setMinLines(multiLine ? 4 : 1);
        et.setGravity(Gravity.TOP);
        et.setTag("input");
        binding.llAnswerArea.addView(et);
        return et;
    }

    /** 主观题草稿（SharedPreferences 本地保存，大纲 7.6） */
    private void restoreDraft(EditText et) {
        if (current != null && QuestionTypes.isSubjective(current.type)) {
            String draft = getSharedPreferences("draft", MODE_PRIVATE)
                    .getString("draft_" + current.id, "");
            if (!draft.isEmpty()) {
                et.setText(draft);
            }
        }
    }

    private void saveDraft() {
        for (int i = 0; i < binding.llAnswerArea.getChildCount(); i++) {
            View child = binding.llAnswerArea.getChildAt(i);
            if (child instanceof EditText && current != null
                    && QuestionTypes.isSubjective(current.type)) {
                String text = String.valueOf(((EditText) child).getText());
                getSharedPreferences("draft", MODE_PRIVATE).edit()
                        .putString("draft_" + current.id, text).apply();
            }
        }
    }

    /** 答题卡（大纲 7.5）：进度概览底部弹层 */
    private void showCardSheet() {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);

        String[] lines = {
                "总题数：" + totalCount,
                "已作答：" + answeredCount,
                "答　对：" + correctCount,
                "答　错：" + Math.max(0, answeredCount - correctCount),
                "剩　余：" + Math.max(0, totalCount - answeredCount)
        };
        for (String line : lines) {
            TextView tv = new TextView(this);
            tv.setText(line);
            tv.setTextSize(16);
            tv.setPadding(0, dp(8), 0, dp(8));
            box.addView(tv);
        }
        TextView tip = new TextView(this);
        tip.setText("本次练习为顺序出题模式，完成当前题后自动进入下一题");
        tip.setTextSize(12);
        tip.setTextColor(0xFF7A8190);
        box.addView(tip);
        sheet.setContentView(box);
        sheet.show();
    }

    private String currentInput() {
        for (int i = 0; i < binding.llAnswerArea.getChildCount(); i++) {
            View child = binding.llAnswerArea.getChildAt(i);
            if (child instanceof EditText) {
                return String.valueOf(((EditText) child).getText()).trim();
            }
        }
        return "";
    }

    private void onAction() {
        if (!judged) {
            submit();
        } else {
            if (pendingSelfEval > 0) {
                toast("请先完成自评");
                return;
            }
            loadNext();
        }
    }

    private void submit() {
        if (current == null) {
            return;
        }
        String answer = "";
        if ("MULTI_CHOICE".equals(current.type)) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Boolean> e : multiSelection.entrySet()) {
                if (Boolean.TRUE.equals(e.getValue())) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(e.getKey());
                }
            }
            answer = sb.toString();
            if (answer.isEmpty()) {
                toast("请选择答案");
                return;
            }
        } else if (QuestionTypes.isComposite(current.type)) {
            if (subSelection.isEmpty()) {
                toast("请完成子题作答");
                return;
            }
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<Long, String> e : subSelection.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
                first = false;
            }
            answer = sb.append('}').toString();
        } else if ("SINGLE_CHOICE".equals(current.type) || "TRUE_FALSE".equals(current.type)) {
            answer = singleChoiceAnswer;
            if (answer == null || answer.isEmpty()) {
                toast("请选择答案");
                return;
            }
        } else if ("FILL_BLANK".equals(current.type) || QuestionTypes.isMatching(current.type)
                || QuestionTypes.isOrdering(current.type) || QuestionTypes.isSubjective(current.type)) {
            // P0-2 修复：填空 / 匹配 / 排序 / 主观题从输入框取答案。
            // 此前这些题型走到未初始化的 answer 变量，永远提示「请输入答案」交不上去
            answer = currentInput();
            if (answer.isEmpty()) {
                toast("请输入答案");
                return;
            }
            if (QuestionTypes.isMatching(current.type)
                    && !answer.contains(":") && !answer.contains("：") && !answer.contains("=")) {
                toast("匹配题请按 A:1,B:3 格式作答");
                return;
            }
        } else {
            // 未识别题型兜底：一律尝试输入框文本
            answer = currentInput();
            if (answer.isEmpty()) {
                toast("请输入答案");
                return;
            }
        }

        binding.btnAction.setEnabled(false);
        final String answerToSend = answer;
        api.practiceAnswer(practiceId, new ApiService.AnswerBody(current.id, answerToSend))
                .enqueue(new Callback<Models.ApiResult<Models.AnswerResult>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Models.AnswerResult>> call,
                                           Response<Models.ApiResult<Models.AnswerResult>> resp) {
                        Models.ApiResult<Models.AnswerResult> r = resp.body();
                        if (r == null || !r.ok() || r.data == null) {
                            binding.btnAction.setEnabled(true);
                            toast(r == null ? "提交失败" : r.message);
                            return;
                        }
                        showJudge(r.data, answerToSend);
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Models.AnswerResult>> call, Throwable t) {
                        binding.btnAction.setEnabled(true);
                        toast("网络错误：" + t.getMessage());
                    }
                });
    }

    private void showJudge(Models.AnswerResult result, String userAnswer) {
        judged = true;
        answeredCount++;
        boolean needSelfEval = result.needSelfEval;
        if (Boolean.TRUE.equals(result.correct)) {
            correctCount++;
        }
        updateProgress();

        binding.llResult.setVisibility(View.VISIBLE);
        binding.tvJudge.setText(Boolean.TRUE.equals(result.correct) ? "✓ 回答正确" : "✗ 回答错误");
        binding.tvJudge.setTextColor(Boolean.TRUE.equals(result.correct)
                ? 0xFF2E7D32 : 0xFFC62828);
        binding.tvCorrectAnswer.setText("正确答案：" + (result.correctAnswer == null
                || result.correctAnswer.isEmpty() ? "见解析" : result.correctAnswer));
        binding.tvAnalysis.setVisibility(result.analysis == null || result.analysis.isEmpty()
                ? View.GONE : View.VISIBLE);
        binding.tvAnalysis.setText("解析：" + (result.analysis == null ? "" : result.analysis));
        binding.tvAiGrade.setVisibility(View.GONE);

        boolean selfEval = needSelfEval;
        binding.tvSelfEvalTip.setVisibility(selfEval ? View.VISIBLE : View.GONE);
        binding.llSelfEval.setVisibility(selfEval ? View.VISIBLE : View.GONE);
        pendingSelfEval = selfEval ? 1 : 0;

        if (selfEval) {
            binding.tvJudge.setText("✍ 已提交，正在对照参考答案判分…");
            binding.tvJudge.setTextColor(0xFF7A8190);
            // 自评仍然保留：AI 判分只是辅助，最终由用户确认掌握程度
            requestAiGrade(result.questionId, userAnswer);
        }
        binding.btnAction.setText(needSelfEval ? "提交自评后下一题" : "下一题");
        binding.btnAction.setEnabled(!needSelfEval);

        // 无障碍：判分结果区是动态出现的，主动播报一次，令视障用户无需手动滑动即可听到对错
        binding.tvJudge.announceForAccessibility(binding.tvJudge.getText());
    }

    /**
     * 请求 AI 判分（主观题）
     *
     * <p>★ 三条硬约束：
     * <ul>
     *   <li><b>不阻塞作答</b>：判分失败只是不显示结果，自评入口始终可用；</li>
     *   <li><b>不伪造分数</b>：服务端返回 available=false 时明确提示「暂不可用」，
     *       而不是自己算一个百分比糊弄用户；</li>
     *   <li><b>不覆盖用户操作</b>：用户可能已经点了自评，异步结果回来只更新文案，
     *       不改变当前题目的流程状态。</li>
     * </ul>
     */
    private void requestAiGrade(Long questionId, String userAnswer) {
        if (questionId == null) {
            return;
        }
        final Long askedId = questionId;
        api.gradeQuestion(questionId, new ApiService.GradeBody(userAnswer))
                .enqueue(new Callback<Models.ApiResult<Models.GradeResult>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Models.GradeResult>> call,
                                           Response<Models.ApiResult<Models.GradeResult>> resp) {
                        Models.ApiResult<Models.GradeResult> r = resp.body();
                        // 用户可能已翻到下一题：此时不能把判分结果写进新题目
                        if (r == null || r.data == null || current == null
                                || !askedId.equals(current.id)) {
                            return;
                        }
                        renderAiGrade(r.data);
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Models.GradeResult>> call,
                                          Throwable t) {
                        // 网络失败静默：自评入口仍在，不影响作答
                    }
                });
    }

    /** 渲染 AI 判分结果（不可用时明确提示并保留自评） */
    private void renderAiGrade(Models.GradeResult grade) {
        if (!grade.available) {
            binding.tvJudge.setText("✍ 已提交，AI 判分暂不可用，请对照参考答案自评");
            binding.tvJudge.setTextColor(0xFF7A8190);
            return;
        }
        StringBuilder sb = new StringBuilder("AI 判分：")
                .append(levelLabel(grade.level))
                .append("（参考得分率 ").append(Math.round(grade.scoreRate * 100)).append("%）");
        if (grade.hitPoints != null && !grade.hitPoints.isEmpty()) {
            sb.append("\n命中：").append(String.join("；", grade.hitPoints));
        }
        if (grade.missedPoints != null && !grade.missedPoints.isEmpty()) {
            sb.append("\n遗漏：").append(String.join("；", grade.missedPoints));
        }
        if (grade.comment != null && !grade.comment.isEmpty()) {
            sb.append("\n").append(grade.comment);
        }
        binding.tvAiGrade.setText(sb.toString());
        binding.tvAiGrade.setVisibility(View.VISIBLE);
        binding.tvJudge.setText("✍ 已提交，AI 已判分（仍可自评调整）");
        binding.tvJudge.setTextColor(0xFF7A8190);
        // 无障碍：判分结果是异步出现的，主动播报结论与遗漏点
        binding.tvAiGrade.announceForAccessibility(binding.tvAiGrade.getText());
    }

    /** 等级中文标签（集中一处，避免各页面各写一套） */
    static String levelLabel(String level) {
        if (level == null) {
            return "已完成";
        }
        switch (level) {
            case "EXCELLENT":
                return "优秀";
            case "GOOD":
                return "良好";
            case "PARTIAL":
                return "部分掌握";
            case "POOR":
                return "掌握不足";
            case "INVALID":
                return "作答无效";
            default:
                return "已完成";
        }
    }

    private void selfEval(String result) {
        if (current == null) {
            return;
        }
        api.practiceSelfEval(practiceId, new ApiService.SelfEvalBody(current.id, result))
                .enqueue(new Callback<Models.ApiResult<Object>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Object>> call,
                                           Response<Models.ApiResult<Object>> resp) {
                        // P2-10 修复：按业务码分支提示，失败不能再显示「已记录」
                        Models.ApiResult<Object> r = resp.body();
                        if (r == null || !r.ok()) {
                            toast(r == null ? "自评提交失败，请重试" : r.message);
                            return;
                        }
                        pendingSelfEval = 0;
                        binding.llSelfEval.setVisibility(View.GONE);
                        binding.tvSelfEvalTip.setVisibility(View.GONE);
                        binding.btnAction.setEnabled(true);
                        binding.btnAction.setText("下一题");
                        toast("已记录自评");
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Object>> call, Throwable t) {
                        toast("自评提交失败：" + t.getMessage());
                    }
                });
    }

    private void updateProgress() {
        binding.tvProgress.setText(String.format("第 %d / %d 题 · 对 %d",
                Math.min(answeredCount + (judged ? 0 : 1), totalCount), totalCount, correctCount));
    }

    private void confirmFinish() {
        new AlertDialog.Builder(this)
                .setTitle("交卷")
                .setMessage("确定结束本次练习并查看报告吗？")
                .setPositiveButton("交卷", (d, w) -> finishToReport())
                .setNegativeButton("继续", null)
                .show();
    }

    private void finishToReport() {
        if (finishing) {
            return;
        }
        finishing = true;
        api.practiceFinish(practiceId).enqueue(new Callback<Models.ApiResult<Models.Report>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.Report>> call,
                                   Response<Models.ApiResult<Models.Report>> resp) {
                Models.ApiResult<Models.Report> r = resp.body();
                Intent intent = new Intent(PracticeActivity.this, ReportActivity.class);
                if (r != null && r.ok() && r.data != null) {
                    intent.putExtra("report", r.data);
                }
                startActivity(intent);
                finish();
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.Report>> call, Throwable t) {
                finishing = false;
                toast("交卷失败：" + t.getMessage());
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 题型工具（与后端 QuestionType 对齐） */
    public static class QuestionTypes {
        public static boolean isSubjective(String t) {
            return switch (t == null ? "" : t) {
                case "SHORT_ANSWER", "TERM_EXPLAIN", "CALCULATION", "APPLICATION",
                     "ESSAY", "WRITING", "TRANSLATION", "CASE_ANALYSIS", "PROOF",
                     "ERROR_CORRECTION", "WORD_FILL" -> true;
                default -> false;
            };
        }

        public static boolean isMatching(String t) {
            return "MATCHING".equals(t);
        }

        public static boolean isOrdering(String t) {
            return "ORDERING".equals(t);
        }

        public static boolean isComposite(String t) {
            return switch (t == null ? "" : t) {
                case "READING", "CLOZE", "LISTENING", "COMPOSITE" -> true;
                default -> false;
            };
        }
    }
}
