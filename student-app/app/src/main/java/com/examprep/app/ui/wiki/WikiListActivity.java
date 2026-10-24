package com.examprep.app.ui.wiki;

import android.content.Intent;

import com.examprep.app.data.model.Models;
import com.examprep.app.ui.list.SimpleListActivity;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Wiki 主题页列表（匿名可读）
 *
 * <p>Wiki 是 RAG 之外的「沉淀层」：后台把跨文档知识蒸馏成主题页，
 * 问答命中时直接返回页面内容；未命中再降级到 RAG 检索。
 */
public class WikiListActivity extends SimpleListActivity {

    private final List<Models.WikiPage> pages = new ArrayList<>();

    @Override
    protected String title() {
        return "Wiki 知识库";
    }

    @Override
    protected void load() {
        api.wikiPages(1, 100, null, null).enqueue(
                new Callback<Models.ApiResult<Models.Page<Models.WikiPage>>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Models.Page<Models.WikiPage>>> call,
                                           Response<Models.ApiResult<Models.Page<Models.WikiPage>>> resp) {
                        Models.ApiResult<Models.Page<Models.WikiPage>> r = resp.body();
                        pages.clear();
                        if (r != null && r.ok() && r.data != null && r.data.list != null) {
                            pages.addAll(r.data.list);
                        }
                        List<String[]> rows = new ArrayList<>();
                        for (Models.WikiPage page : pages) {
                            String meta = (page.summary == null ? "" : page.summary)
                                    + "\n更新 " + (page.updatedAt == null ? "-" : page.updatedAt)
                                    + " · v" + (page.version == null ? 1 : page.version);
                            rows.add(new String[]{page.title == null ? "未命名主题" : page.title, meta});
                        }
                        render(rows);
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Models.Page<Models.WikiPage>>> call,
                                          Throwable t) {
                        toast("网络错误");
                    }
                });
    }

    @Override
    protected void onRowClick(String[] row) {
        for (Models.WikiPage page : pages) {
            if (page.title != null && page.title.equals(row[0]) && page.id != null) {
                startActivity(new Intent(this, WikiDetailActivity.class)
                        .putExtra("pageId", page.id));
                return;
            }
        }
    }
}
