package com.examprep.app.ui.interview;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.data.prefs.TokenStore;
import com.examprep.app.databinding.ActivityInterviewBinding;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * AI 面试页：开局设置 → 逐题问答（评分 + 追问）→ 整场报告
 *
 * <p><b>交互主线</b>：
 * <pre>
 *   开始面试 → 出题（第 1 题）
 *     作答 → 评分卡（分数 / 评语 / 命中 / 遗漏 / 参考答案）
 *          → 未结束：追问 + 下一题
 *          → 结束：整场报告（总分 / 等级 / 逐题 / 优势 / 不足 / 建议）
 * </pre>
 *
 * <p><b>为什么整页只有一个会话态</b>：面试是「一条会话走到黑」的强顺序流程，
 * 中途离开再回来即视为放弃（Python 侧会话过期返回 404）。因此不做本地缓存续面，
 * 拿到 404 时明确提示「会话已过期，请重新开始」，并把界面复位到开局状态。
 *
 * <p><b>无障碍</b>：控件均带 contentDescription；所有字号用 sp（跟随系统字体缩放）；
 * 新消息插入后主动 {@code announceForAccessibility}，读屏用户不必手滑找新内容。
 */
public class InterviewActivity extends AppCompatActivity {

    /** 网络请求进行中（防重复提交 / 防误触） */
    private boolean busy = false;

    /** 当前会话 ID，null 表示尚未开始 */
    private String sessionId;

    /** 本场总题数 */
    private int total = 3;

    /** 当前题号（从 1 开始） */
    private int currentNo;

    private ActivityInterviewBinding binding;
    private ApiService api;
    private InterviewAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityInterviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 面试是强个人数据（记名的能力评估），游客必须先登录
        if (!TokenStore.get(this).hasToken()) {
            toast("请先登录后再参加模拟面试");
            startActivity(new android.content.Intent(this,
                    com.examprep.app.ui.login.LoginActivity.class));
            finish();
            return;
        }

        // ★ 必须用长超时实例：单次作答要跑「检索 + 大模型评分」，默认 30s 会被掐断
        api = ApiClient.apiLong();

        adapter = new InterviewAdapter();
        adapter.setOnAgain(this::resetToSetup);
        binding.rvChat.setLayoutManager(new LinearLayoutManager(this));
        binding.rvChat.setAdapter(adapter);

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnStart.setOnClickListener(v -> startInterview());
        binding.btnSend.setOnClickListener(v -> sendAnswer());
        binding.btnRestart.setOnClickListener(v -> resetToSetup());
        // 历史入口：面试进行中也允许看（复盘上一次的失分点，再回来接着面）
        binding.btnHistory.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, InterviewHistoryActivity.class)));

        resetToSetup();
    }

    // ==================================================================
    // 开局
    // ==================================================================

    /** 复位到「未开始」状态：清空对话、显示设置卡、禁用作答栏 */
    private void resetToSetup() {
        if (busy) {
            return;
        }
        sessionId = null;
        currentNo = 0;
        adapter.clear();
        binding.cardSetup.setVisibility(View.VISIBLE);
        binding.btnRestart.setVisibility(View.GONE);
        binding.tvEmpty.setVisibility(View.VISIBLE);
        binding.etAnswer.setEnabled(false);
        binding.btnSend.setEnabled(false);
        binding.etAnswer.setText("");
    }

    private void startInterview() {
        if (busy) {
            return;
        }
        String topic = binding.etTopic.getText() == null
                ? "" : binding.etTopic.getText().toString().trim();
        total = readTotal();

        hideKeyboard();
        setBusy(true, "面试官正在出题…");
        api.interviewStart(new Models.InterviewStartBody(topic, total, null))
                .enqueue(new Callback<Models.ApiResult<Models.InterviewStartResp>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Models.InterviewStartResp>> call,
                                           Response<Models.ApiResult<Models.InterviewStartResp>> resp) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        setBusy(false, null);
                        Models.ApiResult<Models.InterviewStartResp> r = resp.body();
                        if (r == null || !r.ok() || r.data == null) {
                            // 非 2xx 时 Retrofit 的 body 为 null，真正的错误文案在 errorBody 里
                            toast(errorMessage(resp, "出题失败，请重试"));
                            return;
                        }
                        Models.InterviewStartResp data = r.data;
                        sessionId = data.sessionId;
                        total = data.total == null ? total : data.total;
                        currentNo = data.questionNo == null ? 1 : data.questionNo;

                        binding.cardSetup.setVisibility(View.GONE);
                        binding.tvEmpty.setVisibility(View.GONE);
                        binding.btnRestart.setVisibility(View.VISIBLE);
                        binding.etAnswer.setEnabled(true);
                        binding.btnSend.setEnabled(true);

                        appendQuestion(data.question, data.source, null, false);
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Models.InterviewStartResp>> call,
                                          Throwable t) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        setBusy(false, null);
                        toast("网络错误：" + safeMessage(t));
                    }
                });
    }

    private int readTotal() {
        if (binding.rbFive.isChecked()) {
            return 5;
        }
        if (binding.rbEight.isChecked()) {
            return 8;
        }
        return 3;
    }

    // ==================================================================
    // 作答
    // ==================================================================

    private void sendAnswer() {
        if (busy || TextUtils.isEmpty(sessionId)) {
            return;
        }
        String answer = binding.etAnswer.getText() == null
                ? "" : binding.etAnswer.getText().toString().trim();
        if (answer.isEmpty()) {
            toast("请先输入你的作答");
            return;
        }
        final String submitted = answer;
        binding.etAnswer.setText("");
        hideKeyboard();

        // 先把用户作答上屏，再等评分——否则一次 20 秒的评分期间界面毫无反馈
        append(InterviewMessage.myAnswer(submitted));

        setBusy(true, "面试官正在评分…");
        api.interviewAnswer(new Models.InterviewAnswerBody(sessionId, submitted))
                .enqueue(new Callback<Models.ApiResult<Models.InterviewAnswerResp>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Models.InterviewAnswerResp>> call,
                                           Response<Models.ApiResult<Models.InterviewAnswerResp>> resp) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        setBusy(false, null);
                        Models.ApiResult<Models.InterviewAnswerResp> r = resp.body();
                        if (r == null || !r.ok() || r.data == null) {
                            String msg = errorMessage(resp, "评分失败，请重试");
                            toast(msg);
                            // 会话过期：无法继续作答，复位到开局状态，避免用户反复提交同一份答案
                            if (resp.code() == 404) {
                                resetToSetup();
                            } else {
                                // 其它错误保留作答内容，方便用户改完重试
                                binding.etAnswer.setText(submitted);
                            }
                            return;
                        }
                        Models.InterviewAnswerResp data = r.data;
                        int no = data.questionNo == null ? currentNo : data.questionNo;
                        total = data.total == null ? total : data.total;
                        append(InterviewMessage.score(data, no, total));

                        if (data.finished) {
                            episodeFinished(data);
                        } else if (Boolean.TRUE.equals(data.isFollowUp)) {
                            // ★ 追问轮：题号不前进（question_no 仍是当前题），
                            //   内容就是 next_question 本身，不能再按「下一题」推号，
                            //   否则后续题号整体错位（用户看到「第 2/3 题」其实是第 1 题的追问）
                            appendQuestion(data.nextQuestion, null, null, true);
                        } else {
                            currentNo = no + 1;
                            appendQuestion(data.nextQuestion, null, data.followUp, false);
                        }
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Models.InterviewAnswerResp>> call,
                                          Throwable t) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        setBusy(false, null);
                        toast("网络错误：" + safeMessage(t));
                        binding.etAnswer.setText(submitted);
                    }
                });
    }

    /** 整场结束：上报告卡、关闭作答栏、引导再面一场 */
    private void episodeFinished(Models.InterviewAnswerResp data) {
        sessionId = null;
        binding.etAnswer.setEnabled(false);
        binding.btnSend.setEnabled(false);
        if (data.report != null) {
            append(InterviewMessage.report(data.report, total));
        } else {
            // 契约里 finished=true 必带 report；缺失时不能让用户以为「白面了」
            toast("面试已结束，但报告生成失败，请重新开始一场");
        }
        binding.rvChat.post(() -> binding.rvChat.announceForAccessibility("面试结束，报告已生成"));
    }

    // ==================================================================
    // 视图工具
    // ==================================================================

    /**
     * 追加一条提问（带出处 / 追问），并滚动到底部
     *
     * @param question     题面（或追问内容）
     * @param source       出处，首次出题时可能非空
     * @param followUp     面试官追问（仅「下一题」轮次附带上一题的追问，可为空）
     * @param followUpRound true = 本条是**同题追问**，题号不前进
     */
    private void appendQuestion(String question, Models.InterviewSource source,
                                String followUp, boolean followUpRound) {
        String text = TextUtils.isEmpty(question) ? "（面试官未返回下一题）" : question;
        String meta = "问题 " + currentNo + " / " + total + (followUpRound ? " · 追问" : "");
        append(InterviewMessage.question(text, meta, sourceLine(source), followUp));
    }

    /** 出处文案：标题（文件名 · 第 N 页）；全空返回 null */
    private String sourceLine(Models.InterviewSource source) {
        if (source == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(source.title)) {
            sb.append(source.title);
        }
        if (!TextUtils.isEmpty(source.sourceFile)) {
            if (sb.length() > 0) {
                sb.append("（");
            }
            sb.append(source.sourceFile);
            if (source.pageNo != null && source.pageNo > 0) {
                sb.append(" · 第 ").append(source.pageNo).append(" 页");
            }
            if (sb.indexOf("（") >= 0) {
                sb.append("）");
            }
        }
        return sb.length() == 0 ? null : "出处：" + sb;
    }

    /** 追加消息并滚动到底部 + 读屏播报 */
    private void append(InterviewMessage message) {
        adapter.append(message);
        int last = adapter.getItemCount() - 1;
        binding.rvChat.post(() -> {
            binding.rvChat.scrollToPosition(last);
            String spoken = message.text == null ? "" : message.text;
            if (!TextUtils.isEmpty(spoken)) {
                binding.rvChat.announceForAccessibility(spoken);
            }
        });
    }

    /**
     * 统一忙闲态：请求期间遮罩不可误触，按钮禁用（防重复提交扣两次分）
     *
     * @param busy    是否请求中
     * @param message 遮罩文案；null 表示不显示遮罩
     */
    private void setBusy(boolean busy, String message) {
        this.busy = busy;
        if (busy) {
            binding.tvProgress.setText(message == null ? "处理中…" : message);
            binding.progressOverlay.setVisibility(View.VISIBLE);
        } else {
            binding.progressOverlay.setVisibility(View.GONE);
        }
        binding.btnStart.setEnabled(!busy);
        binding.btnSend.setEnabled(!busy && sessionId != null);
        binding.btnRestart.setEnabled(!busy);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    /**
     * 从错误响应体里取后端给的友好文案
     *
     * <p>后端所有错误都是 {@code {code,message}}；503/404 的 message 是
     * 「面试服务暂不可用，请稍后再试」「面试会话已过期，请重新开始」，
     * 直接显示给用户比「HTTP 503」有用得多。
     */
    private String errorMessage(Response<?> resp, String fallback) {
        if (resp == null) {
            return fallback;
        }
        okhttp3.ResponseBody body = resp.errorBody();
        if (body == null) {
            return fallback;
        }
        try {
            String raw = body.string();
            Models.ApiResult<?> parsed = new com.google.gson.Gson()
                    .fromJson(raw, Models.ApiResult.class);
            if (parsed != null && !TextUtils.isEmpty(parsed.message)) {
                return parsed.message;
            }
        } catch (Exception ignored) {
            // 响应体不是 JSON（如网关返回 HTML）：退回兜底文案
        }
        return fallback;
    }

    private String safeMessage(Throwable t) {
        String msg = t == null ? null : t.getMessage();
        return TextUtils.isEmpty(msg) ? "请检查网络后重试" : msg;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
