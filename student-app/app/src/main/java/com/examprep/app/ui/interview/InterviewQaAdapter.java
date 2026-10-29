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

import java.util.ArrayList;
import java.util.List;

/**
 * 复盘页的逐题卡片适配器（复用 {@code item_interview_score} 布局）
 *
 * <p>与面试页评分卡的差别只有两处：
 * <ol>
 *   <li>卡片顶部标出「第 N 题 · 追问」——复盘时用户要看的是「哪一题」，不是「当时答了几轮」；</li>
 *   <li>额外显示「我的作答」一行：复盘的核心诉求就是回看自己当时写了什么。</li>
 * </ol>
 */
public class InterviewQaAdapter extends RecyclerView.Adapter<InterviewQaAdapter.Holder> {

    private final List<Models.InterviewQaItem> items = new ArrayList<>();

    void submit(List<Models.InterviewQaItem> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_interview_score, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Models.InterviewQaItem qa = items.get(position);
        boolean followUp = qa.isFollowUp != null && qa.isFollowUp == 1;
        holder.tvIndex.setText("第 " + (qa.questionNo == null ? 0 : qa.questionNo) + " 题"
                + (followUp ? " · 追问" : ""));
        holder.tvScore.setText(String.valueOf(Math.round(qa.score == null ? 0 : qa.score)));
        holder.tvVerdict.setText(TextUtils.isEmpty(qa.verdict) ? "已评分" : qa.verdict);
        holder.tvHit.setText(bullet(qa.hitPoints));
        holder.tvMiss.setText(bullet(qa.missPoints));
        String reference = TextUtils.isEmpty(qa.reference) ? "（暂无参考答案）" : qa.reference;
        holder.tvReference.setText(reference);

        // 复盘要多看的「我的作答」：拼在参考答案上方，不额外造布局
        String answer = TextUtils.isEmpty(qa.answer) ? "" : "我的作答：" + qa.answer + "\n\n";
        holder.tvVerdict.setText(holder.tvVerdict.getText()
                + (answer.isEmpty() ? "" : "\n" + answer.trim()));
        holder.itemView.setContentDescription("第 " + (qa.questionNo == null ? 0 : qa.questionNo)
                + " 题，得分 " + Math.round(qa.score == null ? 0 : qa.score) + " 分");
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

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

    static class Holder extends RecyclerView.ViewHolder {
        final TextView tvIndex;
        final TextView tvScore;
        final TextView tvVerdict;
        final TextView tvHit;
        final TextView tvMiss;
        final TextView tvReference;

        Holder(@NonNull View itemView) {
            super(itemView);
            tvIndex = itemView.findViewById(R.id.tvScoreIndex);
            tvScore = itemView.findViewById(R.id.tvScoreValue);
            tvVerdict = itemView.findViewById(R.id.tvVerdict);
            tvHit = itemView.findViewById(R.id.tvHitPoints);
            tvMiss = itemView.findViewById(R.id.tvMissPoints);
            tvReference = itemView.findViewById(R.id.tvReference);
        }
    }
}
