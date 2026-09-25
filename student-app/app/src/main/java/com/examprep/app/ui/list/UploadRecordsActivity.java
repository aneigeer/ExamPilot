package com.examprep.app.ui.list;

import android.content.Intent;

import com.examprep.app.data.model.Models;
import com.examprep.app.ui.resource.ResourceDetailActivity;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 上传记录（大纲 7.3「上传记录」）：我的上传 + 审核状态 */
public class UploadRecordsActivity extends SimpleListActivity {

    @Override
    protected String title() {
        return "上传记录";
    }

    @Override
    protected void load() {
        api.myUploads(1, 100).enqueue(new Callback<Models.ApiResult<Models.Page<Models.Resource>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.Page<Models.Resource>>> call,
                                   Response<Models.ApiResult<Models.Page<Models.Resource>>> resp) {
                Models.ApiResult<Models.Page<Models.Resource>> r = resp.body();
                List<Models.Resource> list = r != null && r.ok() && r.data != null && r.data.list != null
                        ? r.data.list : List.of();
                List<String[]> rows = new ArrayList<>();
                for (Models.Resource res : list) {
                    rows.add(new String[]{res.title,
                            res.auditStatus + " · " + res.typeName,
                            String.valueOf(res.id)});
                }
                render(rows);
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.Page<Models.Resource>>> call, Throwable t) {
                toast("网络错误");
            }
        });
    }

    @Override
    protected void onRowClick(String[] row) {
        Intent i = new Intent(this, ResourceDetailActivity.class);
        i.putExtra("resourceId", Long.parseLong(row[2]));
        startActivity(i);
    }
}
