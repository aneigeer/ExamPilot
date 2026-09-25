package com.examprep.app.ui.mock;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityMockBinding;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 模拟考试（P1）：随机组卷 → 计时 → 覆盖作答 → 交卷自动判分 → 成绩报告 */
public class MockExamActivity extends AppCompatActivity {

    private ActivityMockBinding binding;
    private ApiService api;

    private final List<Models.Subject> subjects = new ArrayList<>();
    private final List<Models.Question> questions = new ArrayList<>();
    private final Map<Long, String> selections = new LinkedHashMap<>();

    private long recordId;
    private int index;
    private CountDownTimer timer;
    private boolean submitting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMockBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();

        binding.btnBack.setOnClickListener(v -> {
            if (binding.llMockExam.getVisibility() == View.VISIBLE) {
                new AlertDialog.Builder(this)
                        .setMessage("考试进行中，退出将不保存。确定退出？")
                        .setPositiveButton("退出", (d, w) -> finish())
                        .setNegativeButton("继续作答", null)
                        .show();
            } else {
                finish();
            }
        });
        binding.btnMockStart.setOnClickListener(v -> start());
        binding.btnMockCard.setOnClickListener(v -> {
            if (!questions.isEmpty()) {
                showCardSheet();
            }
        });
        binding.btnMockSubmit.setOnClickListener(v -> confirmSubmit());
        binding.btnMockPrev.setOnClickListener(v -> {
            if (index > 0) {
                saveCurrentInput();
                index--;
                renderQuestion();
            }
        });
        binding.btnMockNext.setOnClickListener(v -> {
            saveCurrentInput();
            if (index < questions.size() - 1) {
                index++;
                renderQuestion();
            } else {
                confirmSubmit();
            }
        });

        loadSubjects();
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
                subjects.addAll(r.data);
                List<String> names = new ArrayList<>();
                for (Models.Subject s : subjects) {
                    names.add(s.name);
                }
                binding.spMockSubject.setAdapter(new ArrayAdapter<>(MockExamActivity.this,
                        android.R.layout.simple_spinner_dropdown_item, names));
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.Subject>>> call, Throwable t) {
                toast("科目加载失败");
            }
        });
    }

    private void start() {
        if (subjects.isEmpty()) {
            toast("科目还没加载好");
            return;
        }
        int count, duration;
        try {
            count = Integer.parseInt(String.valueOf(binding.etMockCount.getText()).trim());
            duration = Integer.parseInt(String.valueOf(binding.etMockDuration.getText()).trim());
        } catch (NumberFormatException e) {
            toast("题数/时长要填数字");
            return;
        }
        Long subjectId = subjects.get(binding.spMockSubject.getSelectedItemPosition()).id;

        binding.btnMockStart.setEnabled(false);
        api.mockStart(new ApiService.MockStartBody(subjectId, count, duration))
                .enqueue(new Callback<Models.ApiResult<Models.MockPaper>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Models.MockPaper>> call,
                                           Response<Models.ApiResult<Models.MockPaper>> resp) {
                        binding.btnMockStart.setEnabled(true);
                        Models.ApiResult<Models.MockPaper> r = resp.body();
                        if (r == null || !r.ok() || r.data == null) {
                            toast(r == null ? "开考失败" : r.message);
                            return;
                        }
                        recordId = r.data.recordId;
                        questions.clear();
                        questions.addAll(r.data.questions == null ? List.of() : r.data.questions);
                        enterExam(r.data.remainingSeconds);
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Models.MockPaper>> call, Throwable t) {
                        binding.btnMockStart.setEnabled(true);
                        toast("网络错误：" + t.getMessage());
                    }
                });
    }

    private void enterExam(long remainingSeconds) {
        binding.llMockConfig.setVisibility(View.GONE);
        binding.llMockExam.setVisibility(View.VISIBLE);
        binding.btnMockSubmit.setVisibility(View.VISIBLE);
        binding.tvTimer.setVisibility(View.VISIBLE);
        index = 0;
        timer = new CountDownTimer(remainingSeconds * 1000L, 1000) {
            @Override
            public void onTick(long ms) {
                long s = ms / 1000;
                binding.tvTimer.setText(String.format("剩余 %02d:%02d", s / 60, s % 60));
            }

            @Override
            public void onFinish() {
                binding.tvTimer.setText("时间到");
                toast("考试时间到，自动交卷");
                submit();
            }
        }.start();
        renderQuestion();
    }

    private void renderQuestion() {
        Models.Question q = questions.get(index);
        binding.tvMockProgress.setText(String.format("第 %d / %d 题", index + 1, questions.size()));
        binding.tvMockStem.setText(q.typeName + "：" + q.stem);

        LinearLayout area = binding.llMockOptions;
        area.removeAllViews();
        String saved = selections.get(q.id);
        if (q.options != null) {
            boolean multi = "MULTI_CHOICE".equals(q.type);
            for (Models.Option o : q.options) {
                MaterialButton btn = new MaterialButton(this);
                btn.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                ((LinearLayout.LayoutParams) btn.getLayoutParams()).topMargin = dp(6);
                btn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                btn.setPadding(dp(14), 0, dp(14), 0);
                String label = o.key + ". " + o.content;
                boolean selected = multi
                        ? saved != null && saved.contains(o.key)
                        : o.key.equals(saved);
                btn.setText(selected ? "● " + label : label);
                btn.setOnClickListener(v -> {
                    if (multi) {
                        String cur = selections.get(q.id);
                        List<String> keys = new ArrayList<>();
                        if (cur != null) {
                            for (String k : cur.split(",")) {
                                if (!k.equals(o.key)) {
                                    keys.add(k);
                                }
                            }
                        }
                        if (!selected) {
                            keys.add(o.key);
                            keys.sort(String::compareTo);
                        }
                        String joined = String.join(",", keys);
                        if (keys.isEmpty()) {
                            selections.remove(q.id);
                        } else {
                            selections.put(q.id, joined);
                        }
                        renderQuestion();
                    } else {
                        selections.put(q.id, o.key);
                        renderQuestion();
                    }
                });
                area.addView(btn);
            }
        } else {
            EditText et = new EditText(this);
            et.setHint("输入答案");
            et.setText(saved == null ? "" : saved);
            et.setBackground(getDrawable(android.R.drawable.editbox_background));
            et.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    selections.put(q.id, String.valueOf(et.getText()).trim());
                }
            });
            area.addView(et);
        }
    }

    private void saveCurrentInput() {
        if (index >= questions.size()) {
            return;
        }
        Models.Question q = questions.get(index);
        for (int i = 0; i < binding.llMockOptions.getChildCount(); i++) {
            View child = binding.llMockOptions.getChildAt(i);
            if (child instanceof EditText) {
                String text = String.valueOf(((EditText) child).getText()).trim();
                if (!text.isEmpty()) {
                    selections.put(q.id, text);
                }
            }
        }
    }

    /** 答题卡：题号网格，已答高亮，点击跳题（大纲 7.5 答题卡） */
    private void showCardSheet() {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(5);
        int pad = dp(20);
        grid.setPadding(pad, pad, pad, pad);

        for (int i = 0; i < questions.size(); i++) {
            Models.Question q = questions.get(i);
            boolean answered = selections.containsKey(q.id);
            MaterialButton b = new MaterialButton(this);
            b.setText(String.valueOf(i + 1));
            b.setWidth(dp(52));
            b.setHeight(dp(52));
            b.setBackgroundColor(answered ? 0xFF2E7D32 : 0xFFB0BEC5);
            int idx = i;
            b.setOnClickListener(v -> {
                index = idx;
                renderQuestion();
                sheet.dismiss();
            });
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            b.setLayoutParams(lp);
            grid.addView(b);
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView tip = new TextView(this);
        tip.setText("绿色=已答　灰色=未答　点题号跳转　已答 " + selections.size() + "/" + questions.size());
        tip.setPadding(pad, dp(12), pad, 0);
        box.addView(tip);
        box.addView(grid);
        sheet.setContentView(box);
        sheet.show();
    }

    private void confirmSubmit() {
        saveCurrentInput();
        new AlertDialog.Builder(this)
                .setTitle("交卷")
                .setMessage(String.format("已答 %d / %d 题，确定交卷吗？交卷后自动判分。",
                        selections.size(), questions.size()))
                .setPositiveButton("交卷", (d, w) -> submit())
                .setNegativeButton("继续作答", null)
                .show();
    }

    private void submit() {
        if (submitting) {
            return;
        }
        submitting = true;
        if (timer != null) {
            timer.cancel();
        }
        api.mockSubmit(recordId).enqueue(new Callback<Models.ApiResult<Models.MockReport>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.MockReport>> call,
                                   Response<Models.ApiResult<Models.MockReport>> resp) {
                Models.ApiResult<Models.MockReport> r = resp.body();
                Intent intent = new Intent(MockExamActivity.this, MockReportActivity.class);
                if (r != null && r.ok() && r.data != null) {
                    intent.putExtra("report", r.data);
                }
                startActivity(intent);
                finish();
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.MockReport>> call, Throwable t) {
                submitting = false;
                toast("交卷失败：" + t.getMessage());
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
