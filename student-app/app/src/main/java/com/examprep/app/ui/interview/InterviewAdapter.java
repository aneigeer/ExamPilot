package com.examprep.app.ui.interview;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.examprep.app.R;
import com.examprep.app.data.model.Models;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * 面试对话适配器（4 种气泡：提问 / 我的作答 / 评分卡 / 报告卡）
 *
 * <p><b>无障碍要点</b>（项目用户含视障用户）：
 * <ul>
 *   <li>评分卡整块设置 contentDescription，TalkBack 会一次念完「得分 + 命中 + 遗漏」，
 *       而不是让用户逐条 TextView 听（那样几乎无法理解结构）；</li>
 *   <li>要点列表用「· 」逐条换行，读屏会按行停顿；</li>
 *   <li>报告卡上的「再面一场」按钮保留真实的 button 语义与 48dp 触控高度。</li>
 * </ul>
 */
public class InterviewAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final List<InterviewMessage> items = new ArrayList<>();

    /** 报告卡「再面一场」回调 */
    private Runnable onAgain;

    public void setOnAgain(Runnable onAgain) {
        this.onAgain = onAgain;
    }

    /** 追加一条消息并刷新（返回追加后的位置，供调用方滚动到底部） */
    public int append(InterviewMessage message) {
        items.add(message);
        int index = items.size() - 1;
        notifyItemInserted(index);
        return index;
    }

    /** 清空（重新开始一场面试时） */
    public void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case InterviewMessage.TYPE_MY_ANSWER:
                return new AnswerHolder(inflater.inflate(R.layout.item_interview_answer, parent, false));
            case InterviewMessage.TYPE_SCORE:
                return new ScoreHolder(inflater.inflate(R.layout.item_interview_score, parent, false));
            case InterviewMessage.TYPE_REPORT:
                return new ReportHolder(inflater.inflate(R.layout.item_interview_report, parent, false));
            case InterviewMessage.TYPE_QUESTION:
            default:
                return new QuestionHolder(inflater.inflate(R.layout.item_interview_question, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        InterviewMessage m = items.get(position);
        if (holder instanceof QuestionHolder) {
            ((QuestionHolder) holder).bind(m);
        } else if (holder instanceof AnswerHolder) {
            ((AnswerHolder) holder).bind(m);
        } else if (holder instanceof ScoreHolder) {
            ((ScoreHolder) holder).bind(m);
        } else if (holder instanceof ReportHolder) {
            ((ReportHolder) holder).bind(m, onAgain);
        }
    }

    /** 把字符串列表渲染成「· 条目」多行文本 */
    private static String bullet(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (s == null || s.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("· ").append(s.trim());
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }

    // ------------------------------------------------------------------
    // 提问
    // ------------------------------------------------------------------
    static class QuestionHolder extends RecyclerView.ViewHolder {
        private final TextView tvIndex;
        private final TextView tvText;
        private final TextView tvSource;
        private final TextView tvFollowUp;

        QuestionHolder(@NonNull View itemView) {
            super(itemView);
            tvIndex = itemView.findViewById(R.id.tvQuestionIndex);
            tvText = itemView.findViewById(R.id.tvQuestionText);
            tvSource = itemView.findViewById(R.id.tvQuestionSource);
            tvFollowUp = itemView.findViewById(R.id.tvFollowUp);
        }

        void bind(InterviewMessage m) {
            tvIndex.setText(m.meta == null ? "" : m.meta);
            tvText.setText(m.text == null ? "" : m.text);
            if (TextUtils.isEmpty(m.followUp)) {
                tvFollowUp.setVisibility(View.GONE);
            } else {
                tvFollowUp.setVisibility(View.VISIBLE);
                tvFollowUp.setText("面试官追问：" + m.followUp);
            }
            // 出处（语料来源）体现「题目不是模型瞎编的」，有就显示
            if (TextUtils.isEmpty(m.source)) {
                tvSource.setVisibility(View.GONE);
            } else {
                tvSource.setVisibility(View.VISIBLE);
                tvSource.setText(m.source);
            }
            itemView.setContentDescription("面试官提问：" + (m.text == null ? "" : m.text));
        }
    }

    // ------------------------------------------------------------------
    // 我的作答
    // ------------------------------------------------------------------
    static class AnswerHolder extends RecyclerView.ViewHolder {
        private final TextView tvAnswer;

        AnswerHolder(@NonNull View itemView) {
            super(itemView);
            tvAnswer = itemView.findViewById(R.id.tvMyAnswer);
        }

        void bind(InterviewMessage m) {
            tvAnswer.setText(m.text == null ? "" : m.text);
            itemView.setContentDescription("我的作答：" + (m.text == null ? "" : m.text));
        }
    }

    // ------------------------------------------------------------------
    // 评分卡片
    // ------------------------------------------------------------------
    static class ScoreHolder extends RecyclerView.ViewHolder {
        private final TextView tvIndex;
        private final TextView tvScore;
        private final TextView tvVerdict;
        private final TextView tvHit;
        private final TextView tvMiss;
        private final TextView tvReference;

        ScoreHolder(@NonNull View itemView) {
            super(itemView);
            tvIndex = itemView.findViewById(R.id.tvScoreIndex);
            tvScore = itemView.findViewById(R.id.tvScoreValue);
            tvVerdict = itemView.findViewById(R.id.tvVerdict);
            tvHit = itemView.findViewById(R.id.tvHitPoints);
            tvMiss = itemView.findViewById(R.id.tvMissPoints);
            tvReference = itemView.findViewById(R.id.tvReference);
        }

        void bind(InterviewMessage m) {
            Models.InterviewAnswerResp s = m.score;
            tvIndex.setText(m.meta == null ? "" : m.meta);
            double score = s == null || s.score == null ? 0 : s.score;
            tvScore.setText(String.valueOf(Math.round(score)));
            tvVerdict.setText(s == null || TextUtils.isEmpty(s.verdict) ? "已评分" : s.verdict);
            tvHit.setText(bullet(s == null ? null : s.hitPoints));
            tvMiss.setText(bullet(s == null ? null : s.missPoints));
            tvReference.setText(s == null || TextUtils.isEmpty(s.reference)
                    ? "（暂无参考答案）" : s.reference);

            String spoken = (m.meta == null ? "" : m.meta + "，")
                    + "得分 " + Math.round(score) + " 分。"
                    + (s == null || TextUtils.isEmpty(s.verdict) ? "" : s.verdict + "。")
                    + "命中要点：" + bullet(s == null ? null : s.hitPoints).replace('\n', ' ')
                    + "。遗漏要点：" + bullet(s == null ? null : s.missPoints).replace('\n', ' ');
            itemView.setContentDescription(spoken);
        }
    }

    // ------------------------------------------------------------------
    // 整场报告
    // ------------------------------------------------------------------
    static class ReportHolder extends RecyclerView.ViewHolder {
        private final TextView tvScore;
        private final TextView tvLevel;
        private final TextView tvDegraded;
        private final TextView tvPerQuestion;
        private final TextView tvStrengths;
        private final TextView tvWeaknesses;
        private final TextView tvSuggestions;
        private final MaterialButton btnAgain;

        ReportHolder(@NonNull View itemView) {
            super(itemView);
            tvScore = itemView.findViewById(R.id.tvReportScore);
            tvLevel = itemView.findViewById(R.id.tvReportLevel);
            tvDegraded = itemView.findViewById(R.id.tvReportDegraded);
            tvPerQuestion = itemView.findViewById(R.id.tvPerQuestion);
            tvStrengths = itemView.findViewById(R.id.tvStrengths);
            tvWeaknesses = itemView.findViewById(R.id.tvWeaknesses);
            tvSuggestions = itemView.findViewById(R.id.tvSuggestions);
            btnAgain = itemView.findViewById(R.id.btnAgain);
        }

        void bind(InterviewMessage m, Runnable onAgain) {
            Models.InterviewReport r = m.report;
            double total = r == null || r.totalScore == null ? 0 : r.totalScore;
            tvScore.setText(String.valueOf(Math.round(total)));
            tvLevel.setText(r == null || TextUtils.isEmpty(r.level) ? "已结束" : r.level);

            if (r != null && r.degraded) {
                tvDegraded.setVisibility(View.VISIBLE);
            } else {
                tvDegraded.setVisibility(View.GONE);
            }

            StringBuilder per = new StringBuilder();
            if (r != null && r.perQuestion != null) {
                int i = 1;
                for (Models.InterviewPerQuestion q : r.perQuestion) {
                    if (q == null) {
                        continue;
                    }
                    per.append(i++).append(". ")
                            .append(q.question == null ? "" : q.question)
                            .append(" —— ").append(Math.round(q.score == null ? 0 : q.score))
                            .append(" 分");
                    if (!TextUtils.isEmpty(q.comment)) {
                        per.append("（").append(q.comment).append("）");
                    }
                    per.append('\n');
                }
            }
            tvPerQuestion.setText(per.length() == 0 ? "（无）" : per.toString().trim());

            tvStrengths.setText(bullet(r == null ? null : r.strengths));
            tvWeaknesses.setText(bullet(r == null ? null : r.weaknesses));
            tvSuggestions.setText(bullet(r == null ? null : r.suggestions));

            btnAgain.setOnClickListener(v -> {
                if (onAgain != null) {
                    onAgain.run();
                }
            });
            itemView.setContentDescription("面试报告：总分 " + Math.round(total) + " 分，"
                    + (r == null || TextUtils.isEmpty(r.level) ? "" : r.level));
        }
    }
}
