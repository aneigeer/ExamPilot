package com.examprep.app.ui.question;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityQuestionDetailBinding;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 题目详情：题干 + 选项（标注正确答案）+ 答案 + 解析 */
public class QuestionDetailActivity extends AppCompatActivity {

    private ActivityQuestionDetailBinding binding;
    private ApiService api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityQuestionDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();

        binding.btnBack.setOnClickListener(v -> finish());

        long questionId = getIntent().getLongExtra("questionId", 0);
        api.questionDetail(questionId).enqueue(new Callback<Models.ApiResult<Models.Question>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.Question>> call,
                                   Response<Models.ApiResult<Models.Question>> resp) {
                Models.ApiResult<Models.Question> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    return;
                }
                bind(r.data);
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.Question>> call, Throwable t) {
            }
        });
    }

    private void bind(Models.Question q) {
        binding.tvQTypeName.setText(q.typeName == null ? q.type : q.typeName);
        binding.tvQStem.setText(q.stem);
        binding.tvQStem.append(q.material == null ? "" : "\n\n【材料】" + q.material);

        StringBuilder options = new StringBuilder();
        if (q.options != null) {
            for (Models.Option o : q.options) {
                options.append(o.key).append(". ").append(o.content);
                if (Boolean.TRUE.equals(o.isAnswer)) {
                    options.append("  ✓");
                }
                options.append('\n');
            }
        }
        if (q.subQuestions != null && !q.subQuestions.isEmpty()) {
            options.append("\n【子题】\n");
            for (Models.SubQ sub : q.subQuestions) {
                options.append("· ").append(sub.stem).append('\n');
            }
        }
        binding.tvQOptions.setText(options.length() == 0 ? "（无选项）" : options.toString().trim());
        binding.tvQAnswer.setText("答案：" + (q.answerContent == null || q.answerContent.isEmpty()
                ? "见解析" : q.answerContent));
        binding.tvQAnalysis.setText("解析：" + (q.analysis == null || q.analysis.isEmpty()
                ? "无" : q.analysis));
    }
}
