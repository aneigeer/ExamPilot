package com.examprep.app.ui.list;

import android.os.Bundle;
import android.widget.Toast;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.examprep.app.R;
import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityListBinding;
import com.examprep.app.ui.common.TwoLineAdapter;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 简单列表页基类：下载历史 / 我的收藏 / 通知中心 */
public abstract class SimpleListActivity extends AppCompatActivity {

    protected ActivityListBinding binding;
    protected ApiService api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();

        binding.tvListTitle.setText(title());
        binding.btnBack.setOnClickListener(v -> finish());
        binding.rvList.setLayoutManager(new LinearLayoutManager(this));

        load();
    }

    protected void render(List<String[]> rows) {
        binding.tvListEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        binding.rvList.setAdapter(new TwoLineAdapter(rows));
    }

    protected void toast(String msg) {
        android.widget.Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    protected abstract String title();

    protected abstract void load();
}
