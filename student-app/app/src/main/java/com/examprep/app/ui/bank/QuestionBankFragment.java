package com.examprep.app.ui.bank;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.R;
import com.examprep.app.databinding.FragmentQuestionBankBinding;
import com.examprep.app.ui.practice.PracticeConfigActivity;
import com.examprep.app.ui.question.QuestionListActivity;
import com.google.android.material.card.MaterialCardView;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 题库 tab（大纲 7.5「题库首页」）：
 * 考试类别卡片 → 科目列表 → 知识点树 → 按科目/知识点刷题、浏览题目。
 */
public class QuestionBankFragment extends Fragment {

    private FragmentQuestionBankBinding binding;
    private ApiService api;

    private static final int LEVEL_EXAM = 0;
    private static final int LEVEL_SUBJECT = 1;
    private static final int LEVEL_KNOWLEDGE = 2;
    private int level = LEVEL_EXAM;

    private Long currentExamId;
    private Long currentSubjectId;
    private String currentSubjectName;
    private String currentExamName;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentQuestionBankBinding.inflate(inflater, container, false);
        api = ApiClient.api();

        binding.btnBackLevel.setOnClickListener(v -> {
            if (level == LEVEL_KNOWLEDGE) {
                loadSubjects(currentExamId, currentExamName);
            } else if (level == LEVEL_SUBJECT) {
                loadExams();
            }
        });

        // 支持从首页考试类别卡片直达科目列表；无参数时按原逻辑展示类别卡片
        Bundle args = getArguments();
        if (args != null && args.containsKey("examId")) {
            loadSubjects(args.getLong("examId"), args.getString("examName", ""));
        } else {
            loadExams();
        }
        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null; // 释放视图绑定，避免内存泄漏与操作已销毁视图
    }

    private void setLevel(int level) {
        this.level = level;
        binding.llExamCards.setVisibility(level == LEVEL_EXAM ? View.VISIBLE : View.GONE);
        binding.llSubjects.setVisibility(level == LEVEL_SUBJECT ? View.VISIBLE : View.GONE);
        binding.llKnowledge.setVisibility(level == LEVEL_KNOWLEDGE ? View.VISIBLE : View.GONE);
        binding.btnBackLevel.setVisibility(level == LEVEL_EXAM ? View.INVISIBLE : View.VISIBLE);
        binding.tvBankTitle.setText(level == LEVEL_EXAM ? "题库" :
                level == LEVEL_SUBJECT ? "选择科目" : currentSubjectName);
    }

    /** 态 1：考试类别卡片 */
    private void loadExams() {
        setLevel(LEVEL_EXAM);
        binding.llExamCards.removeAllViews();
        api.exams().enqueue(new Callback<Models.ApiResult<List<Models.ExamBrief>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<Models.ExamBrief>>> call,
                                   Response<Models.ApiResult<List<Models.ExamBrief>>> resp) {
                if (binding == null || !isAdded()) return; // 异步响应到达时页面可能已销毁
                Models.ApiResult<List<Models.ExamBrief>> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    return;
                }
                for (Models.ExamBrief e : r.data) {
                    MaterialCardView card = (MaterialCardView) LayoutInflater.from(getContext())
                            .inflate(R.layout.item_exam_card, binding.llExamCards, false);
                    TextView tv = card.findViewById(R.id.tvExamName);
                    TextView sub = card.findViewById(R.id.tvExamSub);
                    tv.setText(e.name);
                    sub.setText(e.subjectCount + " 个科目");
                    card.setOnClickListener(v -> loadSubjects(e.id, e.name));
                    binding.llExamCards.addView(card);
                }
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.ExamBrief>>> call, Throwable t) {
                if (binding == null || !isAdded()) return;
                toast("网络错误：" + t.getMessage());
            }
        });
    }

    /** 态 2：科目列表 */
    private void loadSubjects(long examId, String examName) {
        currentExamId = examId;
        currentExamName = examName;
        setLevel(LEVEL_SUBJECT);
        binding.tvBankTitle.setText(examName);
        binding.llSubjects.removeAllViews();
        api.subjects(examId).enqueue(new Callback<Models.ApiResult<List<Models.Subject>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<Models.Subject>>> call,
                                   Response<Models.ApiResult<List<Models.Subject>>> resp) {
                if (binding == null || !isAdded()) return; // 异步响应到达时页面可能已销毁
                Models.ApiResult<List<Models.Subject>> r = resp.body();
                List<Models.Subject> list = r != null && r.ok() && r.data != null ? r.data : java.util.Collections.emptyList();
                if (list.isEmpty()) {
                    addHint(binding.llSubjects, "该类别暂无科目");
                    return;
                }
                for (Models.Subject s : list) {
                    MaterialCardView card = (MaterialCardView) LayoutInflater.from(getContext())
                            .inflate(R.layout.item_exam_card, binding.llSubjects, false);
                    TextView tv = card.findViewById(R.id.tvExamName);
                    TextView sub = card.findViewById(R.id.tvExamSub);
                    tv.setText(s.name);
                    sub.setText(s.questionCount + " 题 · " + s.resourceCount + " 份资料 · 点按进入");
                    card.setOnClickListener(v -> {
                        currentSubjectId = s.id;
                        currentSubjectName = s.name;
                        loadKnowledgeTree();
                    });
                    binding.llSubjects.addView(card);
                }
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.Subject>>> call, Throwable t) {
                if (binding == null || !isAdded()) return;
                toast("网络错误");
            }
        });
    }

    /** 态 3：知识点树 */
    private void loadKnowledgeTree() {
        setLevel(LEVEL_KNOWLEDGE);
        binding.llKnowledgeList.removeAllViews();
        api.knowledgeTree(currentSubjectId).enqueue(new Callback<Models.ApiResult<List<Models.KnowledgeNode>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<Models.KnowledgeNode>>> call,
                                   Response<Models.ApiResult<List<Models.KnowledgeNode>>> resp) {
                if (binding == null || !isAdded()) return; // 异步响应到达时页面可能已销毁
                Models.ApiResult<List<Models.KnowledgeNode>> r = resp.body();
                List<Models.KnowledgeNode> roots = r != null && r.ok() && r.data != null ? r.data : java.util.Collections.emptyList();
                if (roots.isEmpty()) {
                    addHint(binding.llKnowledgeList, "该科目暂无知识点，可直接按科目刷题");
                }
                renderNodes(binding.llKnowledgeList, roots, 0);
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.KnowledgeNode>>> call, Throwable t) {
                if (binding == null || !isAdded()) return;
                toast("网络错误");
            }
        });
    }

    private void renderNodes(LinearLayout parent, List<Models.KnowledgeNode> nodes, int depth) {
        for (Models.KnowledgeNode node : nodes) {
            TextView tv = new TextView(getContext());
            // A-03：String.repeat 是 Java 11 API（Android API 30+），minSdk 26 下手写缩进
            StringBuilder indent = new StringBuilder();
            for (int i = 0; i < depth; i++) {
                indent.append('　');
            }
            tv.setText(indent.toString() + (depth > 0 ? "└ " : "") + node.name);
            tv.setTextSize(14);
            tv.setTextColor(0xFF1B1F27);
            tv.setPadding(dp(8), dp(10), dp(8), dp(10));
            tv.setBackground(getResources().getDrawable(android.R.drawable.list_selector_background));
            tv.setOnClickListener(v -> practiceByKnowledge(node.id, node.name));
            parent.addView(tv);
            if (node.children != null && !node.children.isEmpty()) {
                renderNodes(parent, node.children, depth + 1);
            }
        }
    }

    /** 按科目刷题：直接开随机练习 */
    private void practiceBySubject() {
        if (!requireLogin()) {
            return;
        }
        goPractice(currentSubjectId, null);
    }

    /** 按知识点刷题 */
    private void practiceByKnowledge(Long knowledgeId, String name) {
        if (!requireLogin()) {
            return;
        }
        goPractice(currentSubjectId, knowledgeId);
    }

    private void goPractice(Long subjectId, Long knowledgeId) {
        Intent intent = new Intent(getContext(), PracticeConfigActivity.class);
        intent.putExtra("subjectId", subjectId);
        intent.putExtra("knowledgeId", knowledgeId);
        startActivity(intent);
    }

    private void browseQuestions() {
        Intent intent = new Intent(getContext(), QuestionListActivity.class);
        intent.putExtra("subjectId", currentSubjectId);
        startActivity(intent);
    }

    private boolean requireLogin() {
        if (!com.examprep.app.data.prefs.TokenStore.get(requireContext()).hasToken()) {
            toast("游客不可刷题，请先登录");
            startActivity(new Intent(getContext(), com.examprep.app.ui.login.LoginActivity.class));
            return false;
        }
        return true;
    }

    private void addHint(LinearLayout parent, String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(24), 0, dp(24));
        tv.setTextColor(0xFF7A8190);
        parent.addView(tv);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        if (binding == null || !isAdded()) return; // 页面已离开时 getContext() 为 null，忽略以防空指针闪退
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
