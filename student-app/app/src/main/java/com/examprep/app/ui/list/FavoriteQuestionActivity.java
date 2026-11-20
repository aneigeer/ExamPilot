package com.examprep.app.ui.list;

import android.content.Intent;

import com.examprep.app.data.model.Models;
import com.examprep.app.ui.question.QuestionDetailActivity;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 收藏题（大纲 7.3「收藏题」） */
public class FavoriteQuestionActivity extends SimpleListActivity {

    @Override
    protected String title() {
        return "收藏题";
    }

    @Override
    protected void load() {
        api.favoriteQuestions(1, 100).enqueue(new Callback<Models.ApiResult<Models.Page<Models.FavoriteQuestion>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.Page<Models.FavoriteQuestion>>> call,
                                   Response<Models.ApiResult<Models.Page<Models.FavoriteQuestion>>> resp) {
                Models.ApiResult<Models.Page<Models.FavoriteQuestion>> r = resp.body();
                List<Models.FavoriteQuestion> list = r != null && r.ok() && r.data != null && r.data.list != null
                        ? r.data.list : java.util.Collections.emptyList();
                List<String[]> rows = new ArrayList<>();
                for (Models.FavoriteQuestion fq : list) {
                    String stem = fq.question != null && fq.question.stem != null ? fq.question.stem : "题目 #" + fq.questionId;
                    rows.add(new String[]{stem.length() > 50 ? stem.substring(0, 50) + "…" : stem,
                            "点击查看详情", String.valueOf(fq.questionId)});
                }
                render(rows);
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.Page<Models.FavoriteQuestion>>> call, Throwable t) {
                toast("网络错误");
            }
        });
    }

    @Override
    protected void onRowClick(String[] row) {
        Intent i = new Intent(this, QuestionDetailActivity.class);
        i.putExtra("questionId", Long.parseLong(row[2]));
        startActivity(i);
    }
}
