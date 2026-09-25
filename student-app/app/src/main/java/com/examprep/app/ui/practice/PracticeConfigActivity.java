package com.examprep.app.ui.practice;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityPracticeConfigBinding;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 刷题配置：科目 / 题型 / 模式 / 范围 / 难度 / 题数 */
public class PracticeConfigActivity extends AppCompatActivity {

    private ActivityPracticeConfigBinding binding;
    private ApiService api;

    private final List<Models.Subject> subjects = new ArrayList<>();
    private final List<Models.QuestionTypeItem> types = new ArrayList<>();

    private static final String[] MODES = {"SEQUENCE", "RANDOM", "WRONG_BOOK"};
    private static final String[] MODE_LABELS = {"顺序练习", "随机练习", "错题重做"};
    private static final String[] SCOPES = {"MIXED", "ONLY_OBJECTIVE", "ONLY_SUBJECTIVE"};
    private static final String[] SCOPE_LABELS = {"混合（客观+主观）", "只刷客观题", "只刷主观题"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPracticeConfigBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();

        binding.spMode.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, MODE_LABELS));
        binding.spScope.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, SCOPE_LABELS));
        binding.spDifficulty.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"不限难度", "难度 1", "难度 2", "难度 3", "难度 4", "难度 5"}));

        loadSubjects();
        loadTypes();

        binding.btnGo.setOnClickListener(v -> start());
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
                binding.spSubject.setAdapter(new ArrayAdapter<>(PracticeConfigActivity.this,
                        android.R.layout.simple_spinner_dropdown_item, names));
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.Subject>>> call, Throwable t) {
                toast("科目加载失败");
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
                binding.spType.setAdapter(new ArrayAdapter<>(PracticeConfigActivity.this,
                        android.R.layout.simple_spinner_dropdown_item, labels));
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.QuestionTypeItem>>> call, Throwable t) {
                toast("题型加载失败");
            }
        });
    }

    private void start() {
        Long subjectId = binding.spSubject.getSelectedItemPosition() <= 0
                ? null : subjects.get(binding.spSubject.getSelectedItemPosition() - 1).id;
        String questionType = binding.spType.getSelectedItemPosition() <= 0
                ? null : types.get(binding.spType.getSelectedItemPosition() - 1).type;
        String mode = MODES[binding.spMode.getSelectedItemPosition()];
        String scope = SCOPES[binding.spScope.getSelectedItemPosition()];
        int difficulty = binding.spDifficulty.getSelectedItemPosition();

        int count;
        try {
            count = Integer.parseInt(String.valueOf(binding.etCount.getText()).trim());
        } catch (NumberFormatException e) {
            count = 10;
        }
        if (count < 1 || count > 100) {
            toast("题数要在 1-100 之间");
            return;
        }

        binding.btnGo.setEnabled(false);
        api.practiceStart(new ApiService.PracticeStartBody(
                        subjectId, mode, questionType, difficulty == 0 ? null : difficulty, scope, count))
                .enqueue(new Callback<Models.ApiResult<Models.Practice>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Models.Practice>> call,
                                           Response<Models.ApiResult<Models.Practice>> resp) {
                        binding.btnGo.setEnabled(true);
                        Models.ApiResult<Models.Practice> r = resp.body();
                        if (r != null && r.ok() && r.data != null) {
                            Intent intent = new Intent(PracticeConfigActivity.this, PracticeActivity.class);
                            intent.putExtra("practiceId", r.data.practiceId);
                            startActivity(intent);
                            finish();
                        } else {
                            toast(r == null ? "开始失败" : r.message);
                        }
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Models.Practice>> call, Throwable t) {
                        binding.btnGo.setEnabled(true);
                        toast("网络错误：" + t.getMessage());
                    }
                });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
