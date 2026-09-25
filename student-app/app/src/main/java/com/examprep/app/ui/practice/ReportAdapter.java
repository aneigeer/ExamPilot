package com.examprep.app.ui.practice;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ItemReportBinding;

import java.util.List;

/** 练习报告明细列表 */
public class ReportAdapter extends RecyclerView.Adapter<ReportAdapter.VH> {

    private final List<Models.ReportItem> data;

    public ReportAdapter(List<Models.ReportItem> data) {
        this.data = data == null ? List.of() : data;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemReportBinding binding = ItemReportBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Models.ReportItem item = data.get(position);
        String stem = item.stem == null ? "" : item.stem;
        h.binding.tvItemTitle.setText(String.format("%d. [%s] %s",
                position + 1, item.typeName == null ? "" : item.typeName,
                stem.length() > 40 ? stem.substring(0, 40) + "…" : stem));
        String result = item.correct != null && item.correct
                ? "✓ 正确　你的答案：" + safe(item.userAnswer)
                : "✗ 错误　你的答案：" + safe(item.userAnswer)
                + "　正确：" + safe(item.correctAnswer);
        h.binding.tvItemResult.setText(result);
        h.binding.tvItemResult.setTextColor(item.correct != null && item.correct
                ? 0xFF2E7D32 : 0xFFC62828);
    }

    private String safe(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemReportBinding binding;

        VH(ItemReportBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
