package com.examprep.app.ui.common;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.examprep.app.databinding.ItemQuestionBinding;

import java.util.ArrayList;
import java.util.List;

/** 通用两行文本列表（下载历史 / 收藏 / 通知等简单列表共用） */
public class TwoLineAdapter extends RecyclerView.Adapter<TwoLineAdapter.VH> {

    public interface OnClick {
        void onClick(String[] row);
    }

    private final List<String[]> data = new ArrayList<>();
    private OnClick onClick;

    public TwoLineAdapter(List<String[]> rows) {
        data.addAll(rows);
    }

    public void setOnClick(OnClick onClick) {
        this.onClick = onClick;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemQuestionBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        String[] row = data.get(position);
        h.binding.tvQTitle.setText(row[0]);
        h.binding.tvQMeta.setText(row.length > 1 ? row[1] : "");
        h.binding.getRoot().setOnClickListener(v -> {
            if (onClick != null) {
                onClick.onClick(row);
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemQuestionBinding binding;

        VH(ItemQuestionBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
