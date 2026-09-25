package com.examprep.app.ui.search;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivitySearchBinding;
import com.examprep.app.ui.common.TwoLineAdapter;
import com.examprep.app.ui.resource.ResourceDetailActivity;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 搜索页（大纲 7.3「搜索页」）：资料标题搜索 */
public class SearchActivity extends AppCompatActivity {

    private ActivitySearchBinding binding;
    private ApiService api;
    private TwoLineAdapter adapter;
    private List<Models.Resource> found = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySearchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();
        binding.rvResult.setLayoutManager(new LinearLayoutManager(this));
        binding.btnBack.setOnClickListener(v -> finish());

        binding.etKeyword.setOnEditorActionListener((v, actionId, event) -> {
            String kw = String.valueOf(binding.etKeyword.getText()).trim();
            if (!kw.isEmpty()) {
                search(kw);
            }
            return true;
        });

        adapter = new TwoLineAdapter(new ArrayList<>());
        adapter.setOnClick(row -> {
            for (Models.Resource r : found) {
                if (String.valueOf(r.id).equals(row[2])) {
                    Intent intent = new Intent(this, ResourceDetailActivity.class);
                    intent.putExtra("resourceId", r.id);
                    startActivity(intent);
                    return;
                }
            }
        });
        binding.rvResult.setAdapter(adapter);
    }

    private void search(String kw) {
        api.searchResources(kw, 1, 50).enqueue(new Callback<Models.ApiResult<Models.Page<Models.Resource>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.Page<Models.Resource>>> call,
                                   Response<Models.ApiResult<Models.Page<Models.Resource>>> resp) {
                Models.ApiResult<Models.Page<Models.Resource>> r = resp.body();
                found = r != null && r.ok() && r.data != null && r.data.list != null ? r.data.list : List.of();
                List<String[]> rows = new ArrayList<>();
                for (Models.Resource res : found) {
                    rows.add(new String[]{res.title,
                            res.typeName + " · " + res.subjectName + " · 下载 " + res.downloadCount,
                            String.valueOf(res.id)});
                }
                binding.tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                if (rows.isEmpty()) {
                    binding.tvEmpty.setText("没有找到相关资料");
                }
                adapter = new TwoLineAdapter(rows);
                adapter.setOnClick(row -> {
                    Intent intent = new Intent(SearchActivity.this, ResourceDetailActivity.class);
                    intent.putExtra("resourceId", Long.parseLong(row[2]));
                    startActivity(intent);
                });
                binding.rvResult.setAdapter(adapter);
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.Page<Models.Resource>>> call, Throwable t) {
                binding.tvEmpty.setVisibility(View.VISIBLE);
                binding.tvEmpty.setText("网络错误");
            }
        });
    }
}
