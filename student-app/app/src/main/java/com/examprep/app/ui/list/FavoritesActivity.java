package com.examprep.app.ui.list;

import com.examprep.app.data.model.Models;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class FavoritesActivity extends SimpleListActivity {

    @Override
    protected String title() {
        return "我的收藏";
    }

    @Override
    protected void load() {
        api.favoriteResources(1, 100).enqueue(new Callback<Models.ApiResult<Models.Page<Models.Resource>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.Page<Models.Resource>>> call,
                                   Response<Models.ApiResult<Models.Page<Models.Resource>>> resp) {
                Models.ApiResult<Models.Page<Models.Resource>> r = resp.body();
                List<Models.Resource> list = r != null && r.ok() && r.data != null && r.data.list != null
                        ? r.data.list : java.util.Collections.emptyList();
                List<String[]> rows = new ArrayList<>();
                for (Models.Resource res : list) {
                    rows.add(new String[]{res.title,
                            res.typeName + " · 下载 " + res.downloadCount});
                }
                render(rows);
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.Page<Models.Resource>>> call, Throwable t) {
                toast("网络错误");
            }
        });
    }
}
