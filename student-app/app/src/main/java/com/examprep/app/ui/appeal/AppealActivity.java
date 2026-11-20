package com.examprep.app.ui.appeal;

import com.examprep.app.data.model.Models;
import com.examprep.app.ui.list.SimpleListActivity;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 我的申诉（大纲 7.4 P1「申诉页」）：发起入口在资料详情（被下架的资料） */
public class AppealActivity extends SimpleListActivity {

    @Override
    protected String title() {
        return "我的申诉";
    }

    @Override
    protected void load() {
        api.myAppeals().enqueue(new Callback<Models.ApiResult<List<Models.Appeal>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<Models.Appeal>>> call,
                                   Response<Models.ApiResult<List<Models.Appeal>>> resp) {
                Models.ApiResult<List<Models.Appeal>> r = resp.body();
                List<Models.Appeal> list = r != null && r.ok() && r.data != null ? r.data : java.util.Collections.emptyList();
                List<String[]> rows = new ArrayList<>();
                for (Models.Appeal a : list) {
                    String status;
                    if ("PENDING".equals(a.status)) {
                        status = "待处理";
                    } else if ("APPROVED".equals(a.status)) {
                        status = "已通过（资料已恢复）";
                    } else {
                        status = "已驳回（维持下架）";
                    }
                    rows.add(new String[]{"[" + status + "] 资料 #" + a.targetId,
                            a.reason + (a.handleNote == null || a.handleNote.isEmpty()
                                    ? "" : "\n处理说明：" + a.handleNote)});
                }
                render(rows);
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.Appeal>>> call, Throwable t) {
                toast("网络错误");
            }
        });
    }
}
