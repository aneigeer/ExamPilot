package com.examprep.app.ui.follow;

import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.examprep.app.R;
import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityListBinding;
import com.examprep.app.ui.common.TwoLineAdapter;
import com.examprep.app.ui.list.SimpleListActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 我的关注（大纲 7.4 P1「关注页」）：科目 + 课程，点击取消关注 */
public class FollowActivity extends SimpleListActivity {

    private final List<String[]> rows = new ArrayList<>();
    private final List<Object[]> targets = new ArrayList<>();

    @Override
    protected String title() {
        return "我的关注";
    }

    @Override
    protected void load() {
        rows.clear();
        targets.clear();
        api.follows("SUBJECT").enqueue(new Callback<Models.ApiResult<List<Map<String, Object>>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<Map<String, Object>>>> call,
                                   Response<Models.ApiResult<List<Map<String, Object>>>> resp) {
                addSection("科目", body(resp));
                api.follows("COURSE").enqueue(new Callback<Models.ApiResult<List<Map<String, Object>>>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<List<Map<String, Object>>>> call,
                                           Response<Models.ApiResult<List<Map<String, Object>>>> resp2) {
                        addSection("课程", body(resp2));
                        render(rows);
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<List<Map<String, Object>>>> call, Throwable t) {
                        toast("网络错误");
                    }
                });
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Map<String, Object>>>> call, Throwable t) {
                toast("网络错误");
            }
        });
    }

    private List<Map<String, Object>> body(Response<Models.ApiResult<List<Map<String, Object>>>> resp) {
        Models.ApiResult<List<Map<String, Object>>> r = resp.body();
        return r != null && r.ok() && r.data != null ? r.data : List.of();
    }

    private void addSection(String type, List<Map<String, Object>> list) {
        for (Map<String, Object> m : list) {
            String name = String.valueOf(m.get("name"));
            String tid = String.valueOf(m.get("targetId"));
            rows.add(new String[]{"[" + type + "] " + name, "点击取消关注"});
            targets.add(new Object[]{type, Long.parseLong(tid)});
        }
    }

    @Override
    protected void onRowClick(String[] row) {
        int idx = rows.indexOf(row);
        if (idx < 0 || idx >= targets.size()) {
            return;
        }
        Object[] t = targets.get(idx);
        api.followsToggle((String) t[0], (Long) t[1])
                .enqueue(new Callback<Models.ApiResult<Map<String, Object>>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Map<String, Object>>> call,
                                           Response<Models.ApiResult<Map<String, Object>>> resp) {
                        Toast.makeText(FollowActivity.this, "已取消关注", Toast.LENGTH_SHORT).show();
                        load();
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Map<String, Object>>> call, Throwable t) {
                        toast("操作失败");
                    }
                });
    }
}
