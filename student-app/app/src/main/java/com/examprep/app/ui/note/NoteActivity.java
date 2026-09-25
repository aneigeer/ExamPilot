package com.examprep.app.ui.note;

import android.content.Intent;

import com.examprep.app.data.model.Models;
import com.examprep.app.ui.list.SimpleListActivity;
import com.examprep.app.ui.question.QuestionDetailActivity;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 我的笔记（大纲 7.3「笔记」）：点击跳题目详情 */
public class NoteActivity extends SimpleListActivity {

    @Override
    protected String title() {
        return "我的笔记";
    }

    @Override
    protected void load() {
        api.myNotes(null).enqueue(new Callback<Models.ApiResult<List<Models.QuestionNoteItem>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<Models.QuestionNoteItem>>> call,
                                   Response<Models.ApiResult<List<Models.QuestionNoteItem>>> resp) {
                Models.ApiResult<List<Models.QuestionNoteItem>> r = resp.body();
                List<Models.QuestionNoteItem> list = r != null && r.ok() && r.data != null ? r.data : List.of();
                List<String[]> rows = new ArrayList<>();
                for (Models.QuestionNoteItem n : list) {
                    rows.add(new String[]{
                            (n.typeName == null ? "" : "[" + n.typeName + "] ")
                                    + (n.stem == null ? "" : n.stem),
                            n.content == null ? "" : n.content,
                            String.valueOf(n.questionId)});
                }
                render(rows);
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.QuestionNoteItem>>> call, Throwable t) {
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
