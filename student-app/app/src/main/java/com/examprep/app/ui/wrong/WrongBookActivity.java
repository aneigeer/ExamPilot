package com.examprep.app.ui.wrong;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityWrongBookBinding;
import com.examprep.app.databinding.ItemQuestionBinding;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 错题本：列表 + 长按移出 + 点击重做（跳转刷题配置的错题重做模式） */
public class WrongBookActivity extends AppCompatActivity {

    private ActivityWrongBookBinding binding;
    private ApiService api;
    private WrongAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityWrongBookBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();
        adapter = new WrongAdapter();
        binding.rvWrong.setLayoutManager(new LinearLayoutManager(this));
        binding.rvWrong.setAdapter(adapter);
        binding.btnBack.setOnClickListener(v -> finish());
        load();
    }

    private void load() {
        api.wrongBook(1, 100).enqueue(new Callback<Models.ApiResult<Models.Page<Models.WrongBookItem>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.Page<Models.WrongBookItem>>> call,
                                   Response<Models.ApiResult<Models.Page<Models.WrongBookItem>>> resp) {
                Models.ApiResult<Models.Page<Models.WrongBookItem>> r = resp.body();
                List<Models.WrongBookItem> list = r != null && r.ok() && r.data != null && r.data.list != null
                        ? r.data.list : List.of();
                binding.tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.submit(list);
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.Page<Models.WrongBookItem>>> call, Throwable t) {
                toast("网络错误");
            }
        });
    }

    private void remove(Models.WrongBookItem item) {
        new AlertDialog.Builder(this)
                .setTitle("移出错题本")
                .setMessage("已掌握这道题？移出后不再出现在错题本。")
                .setPositiveButton("移出", (d, w) ->
                        api.wrongBookRemove(item.questionId).enqueue(new Callback<Models.ApiResult<Object>>() {
                            @Override
                            public void onResponse(Call<Models.ApiResult<Object>> call,
                                                   Response<Models.ApiResult<Object>> resp) {
                                toast("已移出");
                                load();
                            }

                            @Override
                            public void onFailure(Call<Models.ApiResult<Object>> call, Throwable t) {
                                toast("操作失败");
                            }
                        }))
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private class WrongAdapter extends RecyclerView.Adapter<WrongAdapter.VH> {
        private final List<Models.WrongBookItem> data = new ArrayList<>();

        void submit(List<Models.WrongBookItem> list) {
            data.clear();
            data.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemQuestionBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Models.WrongBookItem item = data.get(position);
            String stem = item.stem == null ? "" : item.stem;
            h.binding.tvQTitle.setText(stem.length() > 60 ? stem.substring(0, 60) + "…" : stem);
            h.binding.tvQMeta.setText(String.format("%s · 错了 %d 次 · 点击查看，长按移出",
                    item.typeName, item.wrongCount));
            h.binding.getRoot().setOnClickListener(v -> {
                if (item.question != null) {
                    Intent intent = new Intent(WrongBookActivity.this,
                            com.examprep.app.ui.question.QuestionDetailActivity.class);
                    intent.putExtra("questionId", item.questionId);
                    startActivity(intent);
                }
            });
            h.binding.getRoot().setOnLongClickListener(v -> {
                remove(item);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ItemQuestionBinding binding;

            VH(ItemQuestionBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
