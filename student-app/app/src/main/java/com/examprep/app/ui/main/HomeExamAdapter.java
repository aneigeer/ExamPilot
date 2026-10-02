package com.examprep.app.ui.main;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.examprep.app.R;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ItemHomeExamBinding;

import java.util.ArrayList;
import java.util.List;

/** 首页考试类别横滑卡片（蓝/绿/橙/紫渐变按位置循环） */
public class HomeExamAdapter extends RecyclerView.Adapter<HomeExamAdapter.VH> {

    public interface OnClick {
        void onClick(Models.ExamBrief exam);
    }

    private static final int[] GRADIENTS = {
            R.drawable.bg_exam_blue, R.drawable.bg_exam_green,
            R.drawable.bg_exam_orange, R.drawable.bg_exam_purple
    };

    private final List<Models.ExamBrief> data = new ArrayList<>();
    private OnClick onClick;

    public void setOnClick(OnClick onClick) {
        this.onClick = onClick;
    }

    public void submit(List<Models.ExamBrief> list) {
        data.clear();
        if (list != null) {
            data.addAll(list);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemHomeExamBinding binding = ItemHomeExamBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Models.ExamBrief e = data.get(position);
        // 渐变背景按位置循环，避免所有卡片同色
        h.binding.getRoot().setBackgroundResource(GRADIENTS[position % GRADIENTS.length]);
        h.binding.tvExamName.setText(e.name);
        h.binding.tvExamSub.setText(e.subjectCount + " 个科目");
        h.binding.getRoot().setOnClickListener(v -> {
            if (onClick != null) {
                onClick.onClick(e);
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemHomeExamBinding binding;

        VH(ItemHomeExamBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
