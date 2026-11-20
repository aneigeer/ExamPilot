package com.examprep.app.ui.list;

import com.examprep.app.data.model.Models;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class NotificationsActivity extends SimpleListActivity {

    @Override
    protected String title() {
        return "通知中心";
    }

    @Override
    protected void load() {
        api.notifications(1, 100).enqueue(new Callback<Models.ApiResult<Models.Page<Models.Notification>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.Page<Models.Notification>>> call,
                                   Response<Models.ApiResult<Models.Page<Models.Notification>>> resp) {
                Models.ApiResult<Models.Page<Models.Notification>> r = resp.body();
                List<Models.Notification> list = r != null && r.ok() && r.data != null && r.data.list != null
                        ? r.data.list : java.util.Collections.emptyList();
                List<String[]> rows = new ArrayList<>();
                for (Models.Notification n : list) {
                    String head = (n.readAt == null ? "🔴 " : "") + n.title;
                    rows.add(new String[]{head,
                            n.content == null ? "" : n.content + "\n" + n.createdAt});
                }
                render(rows);
                if (!list.isEmpty()) {
                    markAllRead();
                }
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.Page<Models.Notification>>> call, Throwable t) {
                toast("网络错误");
            }
        });
    }

    private void markAllRead() {
        api.notificationsReadAll().enqueue(new Callback<Models.ApiResult<Object>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Object>> call, Response<Models.ApiResult<Object>> resp) {
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Object>> call, Throwable t) {
            }
        });
    }
}
