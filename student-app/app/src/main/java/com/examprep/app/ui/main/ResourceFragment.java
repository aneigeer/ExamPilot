package com.examprep.app.ui.main;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.FragmentResourceBinding;
import com.google.android.material.chip.ChipGroup;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 资料库：搜索 + 标签筛选 + 分页列表 */
public class ResourceFragment extends Fragment {

    private FragmentResourceBinding binding;
    private ApiService api;
    private ResourceAdapter adapter;

    private long page = 1;
    private final long size = 20;
    private String keyword;
    private String tags;
    private boolean loading;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentResourceBinding.inflate(inflater, container, false);
        api = ApiClient.api();
        adapter = new ResourceAdapter();
        binding.rvResources.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.rvResources.setAdapter(adapter);
        adapter.setOnClick(r -> toast("资料详情页开发中（下一轮交付）"));

        binding.refresh.setOnRefreshListener(() -> {
            page = 1;
            load();
        });

        binding.etSearch.setOnEditorActionListener((v, actionId, event) -> {
            keyword = String.valueOf(binding.etSearch.getText()).trim();
            if (keyword.isEmpty()) {
                keyword = null;
            }
            page = 1;
            load();
            return true;
        });

        binding.chipTags.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                tags = null;
            } else {
                tags = String.valueOf(((com.google.android.material.chip.Chip)
                        group.findViewById(checkedIds.get(0))).getText());
            }
            page = 1;
            load();
        });

        load();
        return binding.getRoot();
    }

    private void load() {
        if (loading) {
            return;
        }
        loading = true;
        binding.refresh.setRefreshing(page == 1);
        api.resources(page, size, null, tags, keyword)
                .enqueue(new Callback<Models.ApiResult<Models.Page<Models.Resource>>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Models.Page<Models.Resource>>> call,
                                           Response<Models.ApiResult<Models.Page<Models.Resource>>> resp) {
                        loading = false;
                        binding.refresh.setRefreshing(false);
                        Models.ApiResult<Models.Page<Models.Resource>> r = resp.body();
                        List<Models.Resource> list = r != null && r.ok() && r.data != null && r.data.list != null
                                ? r.data.list : List.of();
                        binding.tvResourceEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                        adapter.submit(list);
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Models.Page<Models.Resource>>> call, Throwable t) {
                        loading = false;
                        binding.refresh.setRefreshing(false);
                        toast("网络错误：" + t.getMessage());
                    }
                });
    }

    private void toast(String msg) {
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
