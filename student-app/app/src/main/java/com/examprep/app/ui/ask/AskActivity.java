package com.examprep.app.ui.ask;

import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.text.style.SuperscriptSpan;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.examprep.app.R;
import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.data.prefs.TokenStore;
import com.examprep.app.databinding.ActivityAskBinding;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * AI 问答页（RAG：站内题库/资料检索 + 生成，强制溯源）
 *
 * <p><b>这个页面之前的缺口</b>：后端 {@code /api/ai/ask} 早就实现了，但 App 里没有任何调用它的地方，
 * 用户手册写的「首页 AI 助手」实际是空的。本页把这条链路接上。
 *
 * <p><b>三条必须显式呈现的语义</b>（否则用户会被误导）：
 * <ol>
 *   <li>{@code refused=true} → 明确写「资料里没有找到相关内容」，<b>不显示空回答</b>；
 *       这是反幻觉设计的对外承诺，藏着不说等于假装答了；</li>
 *   <li>{@code degraded=true} → 顶部淡色提示「本地抽取，可能不如模型完整」；</li>
 *   <li>{@code sources} → 逐条渲染成来源卡片（标题 + 摘要 + 类型），能跳就跳、跳不了展开全文。
 *       没有溯源的 AI 回答在这个产品里不该出现。</li>
 * </ol>
 *
 * <p><b>引用角标</b>：正文里的 {@code [1][2]} 用 {@link SuperscriptSpan} 排成上标，
 * 与后端「每句话标注来源编号」的提示词约束对应；不做 Markdown 渲染（避免引入解析器与 XSS 面）。
 */
public class AskActivity extends AppCompatActivity {

    /** 引用角标：[1] / [12] */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,2})]");

    private ActivityAskBinding binding;
    private ApiService api;
    private AskAdapter adapter;
    private boolean busy = false;

    /** 能力探测结果：AI 不可用时禁用发送（但仍允许看历史对话） */
    private boolean aiAvailable = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAskBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        // ★ 必须用长超时实例：实测站内问答最慢一次 costMillis=43896（44s，本地 7B 生成），
        // 默认 30s 读超时会把它掐断，用户看到的是"提问失败"，而服务其实只是慢。
        api = ApiClient.apiLong();

        // /ai/ask 需要登录（后端 authenticated）；未登录直接引导登录，避免发出必然 401 的请求
        if (!TokenStore.get(this).hasToken()) {
            toast("请先登录后使用 AI 问答");
            startActivity(new Intent(this, com.examprep.app.ui.login.LoginActivity.class));
            finish();
            return;
        }

        adapter = new AskAdapter(this::onSourceClick);
        binding.rvChat.setLayoutManager(new LinearLayoutManager(this));
        binding.rvChat.setAdapter(adapter);

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnSend.setOnClickListener(v -> send());
        binding.etQuestion.setOnEditorActionListener((v, actionId, event) -> {
            send();
            return true;
        });

        loadCapability();
    }

    // ==================================================================
    // 能力探测：决定入口可用性与降级提示
    // ==================================================================

    private void loadCapability() {
        api.aiCapability().enqueue(new Callback<Models.ApiResult<Models.AiCapability>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.AiCapability>> call,
                                   Response<Models.ApiResult<Models.AiCapability>> resp) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Models.ApiResult<Models.AiCapability> r = resp.body();
                Models.AiCapability cap = r == null ? null : r.data;
                if (cap == null) {
                    // 探测失败不阻断提问：让用户试一次，真正的错误在 ask 时会给出
                    return;
                }
                aiAvailable = !Boolean.FALSE.equals(cap.available);
                if (!aiAvailable) {
                    showBanner("AI 暂不可用：" + (TextUtils.isEmpty(cap.degradedReason)
                            ? "服务未就绪" : cap.degradedReason));
                    binding.btnSend.setEnabled(false);
                } else if (Boolean.TRUE.equals(cap.degraded) || !TextUtils.isEmpty(cap.degradedReason)) {
                    showBanner("当前为降级模式：" + (TextUtils.isEmpty(cap.degradedReason)
                            ? "部分能力不可用" : cap.degradedReason));
                }
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.AiCapability>> call, Throwable t) {
                // 能力探测失败：不打扰用户，正常流程会给出真实错误
            }
        });
    }

    private void showBanner(String text) {
        binding.tvBanner.setText(text);
        binding.tvBanner.setVisibility(View.VISIBLE);
    }

    // ==================================================================
    // 提问
    // ==================================================================

    private void send() {
        if (busy) {
            return;
        }
        String question = binding.etQuestion.getText() == null
                ? "" : binding.etQuestion.getText().toString().trim();
        if (question.isEmpty()) {
            toast("请输入你的问题");
            return;
        }
        if (!aiAvailable) {
            toast("AI 暂不可用，请稍后再试");
            return;
        }
        final String submitted = question;
        binding.etQuestion.setText("");
        hideKeyboard();
        adapter.append(AskAdapter.item(AskAdapter.TYPE_QUESTION, submitted, null));

        setBusy(true, "正在检索资料…");
        api.aiAsk(new Models.AskBody(submitted))
                .enqueue(new Callback<Models.ApiResult<Models.AskResult>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Models.AskResult>> call,
                                           Response<Models.ApiResult<Models.AskResult>> resp) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        setBusy(false, null);
                        Models.ApiResult<Models.AskResult> r = resp.body();
                        if (r == null || !r.ok() || r.data == null) {
                            String msg = resp.code() == 401 ? "登录已过期，请重新登录"
                                    : resp.code() == 429 ? "提问太频繁，请稍后再试"
                                    : "提问失败，请重试";
                            toast(msg);
                            // 失败保留原问题，用户改一改就能再发
                            binding.etQuestion.setText(submitted);
                            return;
                        }
                        render(r.data);
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Models.AskResult>> call, Throwable t) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        setBusy(false, null);
                        toast("网络错误：" + (t.getMessage() == null ? "请重试" : t.getMessage()));
                        binding.etQuestion.setText(submitted);
                    }
                });
    }

    private void render(Models.AskResult result) {
        boolean refused = Boolean.TRUE.equals(result.refused);
        // 降级提示：只在真的降级时显示，且用顶部横幅而不是混进回答正文（正文是事实，横幅是状态）
        if (Boolean.TRUE.equals(result.degraded)) {
            showBanner("当前为本地抽取回答，可能不如模型完整");
        }
        adapter.append(AskAdapter.item(AskAdapter.TYPE_ANSWER, result.answer, result));

        String spoken = refused
                ? "资料里没有找到相关内容"
                : (result.answer == null ? "" : result.answer);
        int sourceCount = result.sources == null ? 0 : result.sources.size();
        if (sourceCount > 0) {
            spoken += "，共 " + sourceCount + " 条来源";
        }
        binding.rvChat.announceForAccessibility(spoken);
    }

    /** 来源点击：能跳就跳（题目 / 资料 / Wiki），跳不了就地展开摘要 */
    private void onSourceClick(Models.AskSource source, TextView snippetView) {
        if (source == null) {
            return;
        }
        Intent intent = null;
        if (source.refId != null && source.refId > 0) {
            String type = source.refType == null ? "" : source.refType.toUpperCase();
            switch (type) {
                case "QUESTION" -> intent = new Intent(this,
                        com.examprep.app.ui.question.QuestionDetailActivity.class)
                        .putExtra("questionId", source.refId);
                case "RESOURCE", "COURSEWARE" -> intent = new Intent(this,
                        com.examprep.app.ui.resource.ResourceDetailActivity.class)
                        .putExtra("resourceId", source.refId);
                case "WIKI" -> intent = new Intent(this,
                        com.examprep.app.ui.wiki.WikiDetailActivity.class)
                        .putExtra("pageId", source.refId);
                default -> { /* 未知类型：走展开 */ }
            }
        }
        if (intent != null) {
            try {
                startActivity(intent);
                return;
            } catch (Exception e) {
                // 跳转失败不吞掉用户意图：退化为展开摘要
            }
        }
        // 跳不了 → 展开全文（点一次展开，再点收起）
        if (snippetView != null) {
            boolean expanded = snippetView.getMaxLines() != Integer.MAX_VALUE;
            snippetView.setMaxLines(expanded ? Integer.MAX_VALUE : 2);
            snippetView.setEllipsize(expanded ? null : TextUtils.TruncateAt.END);
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 把 [1][2] 渲染成上标
     *
     * <p>只调整字号与基线，不改文字本身：读屏仍会念出「[1]」，
     * 这样视障用户也能知道哪句话有来源。
     */
    static CharSequence withCitations(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        SpannableString span = new SpannableString(text);
        Matcher matcher = CITATION.matcher(text);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            span.setSpan(new SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new RelativeSizeSpan(0.75f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }

    private void setBusy(boolean busy, String message) {
        this.busy = busy;
        if (busy) {
            binding.tvProgress.setText(message == null ? "处理中…" : message);
            binding.progressOverlay.setVisibility(View.VISIBLE);
        } else {
            binding.progressOverlay.setVisibility(View.GONE);
        }
        binding.btnSend.setEnabled(!busy && aiAvailable);
        binding.tvEmpty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
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

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
