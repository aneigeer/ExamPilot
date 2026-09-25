package com.examprep.app.ui.question;

import android.content.Intent;
import android.content.Intent;
import android.os.Bundle;
import android.widget.AdapterView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityQuestionListBinding;
import com.examprep.app.databinding.ItemQuestionBinding;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 题库浏览：科目/题型筛选 → 题目列表 → 详情（含答案解析） */
public class QuestionListActivity extends AppCompatActivity {

    private ActivityQuestionListBinding binding;
    private ApiService api;

    private final List<Models.Subject> subjects = new ArrayList<>();
    private final List<Models.QuestionTypeItem> types = new ArrayList<>();
    private QuestionAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityQuestionListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();
        adapter = new QuestionAdapter();
        binding.rvQuestions.setLayoutManager(new LinearLayoutManager(this));
        binding.rvQuestions.setAdapter(adapter);

        binding.btnBack.setOnClickListener(v -> finish());
        binding.spSubject.setOnItemSelectedListener(new SimpleSelect() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                load();
            }
        });
        binding.spType.setOnItemSelectedListener(new SimpleSelect() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) {
                    load();
                }
            }
        });

        loadSubjects();
        loadTypes();
    }

    private void loadSubjects() {
        api.subjects(null).enqueue(new Callback<Models.ApiResult<List<Models.Subject>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<Models.Subject>>> call,
                                   Response<Models.ApiResult<List<Models.Subject>>> resp) {
                Models.ApiResult<List<Models.Subject>> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    return;
                }
                subjects.clear();
                subjects.addAll(r.data);
                List<String> names = new ArrayList<>();
                names.add("全部科目");
                for (Models.Subject s : subjects) {
                    names.add(s.name);
                }
                binding.spSubject.setAdapter(new ArrayAdapter<>(QuestionListActivity.this,
                        android.R.layout.simple_spinner_dropdown_item, names));
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.Subject>>> call, Throwable t) {
            }
        });
    }

    private void loadTypes() {
        api.questionTypes().enqueue(new Callback<Models.ApiResult<List<Models.QuestionTypeItem>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<Models.QuestionTypeItem>>> call,
                                   Response<Models.ApiResult<List<Models.QuestionTypeItem>>> resp) {
                Models.ApiResult<List<Models.QuestionTypeItem>> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    return;
                }
                types.clear();
                types.addAll(r.data);
                List<String> labels = new ArrayList<>();
                labels.add("全部题型");
                for (Models.QuestionTypeItem t : types) {
                    labels.add(t.label);
                }
                binding.spType.setAdapter(new ArrayAdapter<>(QuestionListActivity.this,
                        android.R.layout.simple_spinner_dropdown_item, labels));
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.QuestionTypeItem>>> call, Throwable t) {
            }
        });
    }

    private void load() {
        Long subjectId = binding.spSubject.getSelectedItemPosition() <= 0
                ? null : subjects.get(binding.spSubject.getSelectedItemPosition() - 1).id;
        String type = binding.spType.getSelectedItemPosition() <= 0
                ? null : types.get(binding.spType.getSelectedItemPosition() - 1).type;
        api.questions(subjectId, type, 1, 50).enqueue(new Callback<Models.ApiResult<Models.Page<Models.Question>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.Page<Models.Question>>> call,
                                   Response<Models.ApiResult<Models.Page<Models.Question>>> resp) {
                Models.ApiResult<Models.Page<Models.Question>> r = resp.body();
                List<Models.Question> list = r != null && r.ok() && r.data != null && r.data.list != null
                        ? r.data.list : List.of();
                adapter.submit(list);
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.Page<Models.Question>>> call, Throwable t) {
                toast("网络错误");
            }
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    abstract static class SimpleSelect implements android.widget.AdapterView.OnItemSelectedListener {
        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }

    private class QuestionAdapter extends RecyclerView.Adapter<QuestionAdapter.VH> {
        private final List<Models.Question> data = new ArrayList<>();

        void submit(List<Models.Question> list) {
            data.clear();
            data.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemQuestionBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Models.Question q = data.get(position);
            h.binding.tvQTitle.setText(q.stem == null ? "" : q.stem);
            h.binding.tvQMeta.setText(String.format("%s · 难度 %d · 分值 %d",
                    q.typeName, q.difficulty, q.score));
            h.binding.getRoot().setOnClickListener(v -> {
                Intent intent = new Intent(QuestionListActivity.this, QuestionDetailActivity.class);
                intent.putExtra("questionId", q.id);
                startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ItemQuestionBinding binding;

            VH(ItemQuestionBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
