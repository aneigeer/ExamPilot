package com.examprep.app.ui.main;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ItemResourceBinding;

import java.util.ArrayList;
import java.util.List;

/** 通用资料卡片列表（首页热门 / 资料库 / 下载历史共用） */
public class ResourceAdapter extends RecyclerView.Adapter<ResourceAdapter.VH> {

    public interface OnClick {
        void onClick(Models.Resource resource);
    }

    private final List<Models.Resource> data = new ArrayList<>();
    private OnClick onClick;

    public void setOnClick(OnClick onClick) {
        this.onClick = onClick;
    }

    public void submit(List<Models.Resource> list) {
        data.clear();
        if (list != null) {
            data.addAll(list);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemResourceBinding binding = ItemResourceBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Models.Resource r = data.get(position);
        h.binding.tvTitle.setText(r.title);
        h.binding.tvType.setText(r.typeName == null ? "资料" : r.typeName);
        StringBuilder sub = new StringBuilder();
        if (r.subjectName != null) {
            sub.append(r.subjectName);
        }
        if (r.uploaderName != null) {
            sub.append(sub.length() == 0 ? "" : " · ").append(r.uploaderName);
        }
        h.binding.tvSubject.setText(sub.length() == 0 ? "-" : sub.toString());
        h.binding.tvMeta.setText(String.format("下载 %d · 评分 %.1f", r.downloadCount, r.ratingAvg));
        h.binding.getRoot().setOnClickListener(v -> {
            if (onClick != null) {
                onClick.onClick(r);
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemResourceBinding binding;

        VH(ItemResourceBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
