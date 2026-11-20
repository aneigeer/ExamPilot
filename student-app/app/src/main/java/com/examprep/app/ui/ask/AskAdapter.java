package com.examprep.app.ui.ask;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.examprep.app.R;
import com.examprep.app.data.model.Models;

import java.util.ArrayList;
import java.util.List;

/**
 * 问答对话适配器：用户气泡 / AI 回答气泡（含来源卡片）
 *
 * <p>回答气泡做三件事：
 * <ol>
 *   <li>正文上标渲染 {@code [1][2]} 引用角标；</li>
 *   <li>拒答时不出空回答，改显示「资料里没有找到相关内容」——</li>
 *   <li>把 {@code sources} 逐条渲染成可点卡片（点了由 Activity 决定跳转或展开）。</li>
 * </ol>
 *
 * <p>无障碍：整条回答设置 contentDescription（回答 + 来源条数），读屏一次念完，
 * 而不是让用户在一堆 TextView 之间逐条滑动。
 */
public class AskAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    static final int TYPE_QUESTION = 0;
    static final int TYPE_ANSWER = 1;

    /** 来源点击回调：跳转移交 Activity，跳不了则展开摘要 */
    interface OnSourceClick {
        void onClick(Models.AskSource source, TextView snippetView);
    }

    /** 一条对话 */
    static class Item {
        final int type;
        final String text;
        final Models.AskResult result;

        private Item(int type, String text, Models.AskResult result) {
            this.type = type;
            this.text = text;
            this.result = result;
        }
    }

    static Item item(int type, String text, Models.AskResult result) {
        return new Item(type, text, result);
    }

    private final List<Item> items = new ArrayList<>();
    private final OnSourceClick onSourceClick;

    AskAdapter(OnSourceClick onSourceClick) {
        this.onSourceClick = onSourceClick;
    }

    int append(Item item) {
        items.add(item);
        int index = items.size() - 1;
        notifyItemInserted(index);
        return index;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_QUESTION) {
            return new QuestionHolder(inflater.inflate(R.layout.item_ask_question, parent, false));
        }
        return new AnswerHolder(inflater.inflate(R.layout.item_ask_answer, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Item item = items.get(position);
        if (holder instanceof QuestionHolder) {
            ((QuestionHolder) holder).bind(item.text);
        } else if (holder instanceof AnswerHolder) {
            ((AnswerHolder) holder).bind(item.result, onSourceClick);
        }
    }

    // ------------------------------------------------------------------

    static class QuestionHolder extends RecyclerView.ViewHolder {
        private final TextView tvQuestion;

        QuestionHolder(@NonNull View itemView) {
            super(itemView);
            tvQuestion = itemView.findViewById(R.id.tvQuestion);
        }

        void bind(String question) {
            tvQuestion.setText(question == null ? "" : question);
            itemView.setContentDescription("我的提问：" + (question == null ? "" : question));
        }
    }

    static class AnswerHolder extends RecyclerView.ViewHolder {
        private final TextView tvAnswer;
        private final TextView tvEmptyAnswer;
        private final TextView tvSourceTitle;
        private final LinearLayout sourceContainer;
        private final TextView tvMeta;

        AnswerHolder(@NonNull View itemView) {
            super(itemView);
            tvAnswer = itemView.findViewById(R.id.tvAnswer);
            tvEmptyAnswer = itemView.findViewById(R.id.tvEmptyAnswer);
            tvSourceTitle = itemView.findViewById(R.id.tvSourceTitle);
            sourceContainer = itemView.findViewById(R.id.sourceContainer);
            tvMeta = itemView.findViewById(R.id.tvMeta);
        }

        void bind(Models.AskResult result, OnSourceClick click) {
            boolean refused = result != null && Boolean.TRUE.equals(result.refused);
            String answer = result == null || result.answer == null ? "" : result.answer;

            // 拒答时不留空回答：明确告知「资料里没有」，而不是让用户看到一片空白
            if (refused || TextUtils.isEmpty(answer)) {
                tvAnswer.setVisibility(View.GONE);
                tvEmptyAnswer.setVisibility(View.VISIBLE);
            } else {
                tvAnswer.setVisibility(View.VISIBLE);
                tvEmptyAnswer.setVisibility(View.GONE);
                tvAnswer.setText(AskActivity.withCitations(answer));
            }

            sourceContainer.removeAllViews();
            List<Models.AskSource> sources = result == null || result.sources == null
                    ? java.util.Collections.emptyList() : result.sources;
            if (sources.isEmpty()) {
                tvSourceTitle.setVisibility(View.GONE);
            } else {
                tvSourceTitle.setVisibility(View.VISIBLE);
                tvSourceTitle.setText("来源（" + sources.size() + "）");
                LayoutInflater inflater = LayoutInflater.from(itemView.getContext());
                for (Models.AskSource source : sources) {
                    View view = inflater.inflate(R.layout.item_ask_source, sourceContainer, false);
                    TextView title = view.findViewById(R.id.tvSourceTitle);
                    TextView snippet = view.findViewById(R.id.tvSourceSnippet);
                    String tag = sourceTag(source.refType);
                    title.setText(tag + (TextUtils.isEmpty(source.title) ? "未命名来源" : source.title));
                    String text = TextUtils.isEmpty(source.snippet) ? "（无摘要）" : source.snippet;
                    snippet.setText(text);
                    view.setContentDescription(tag + title.getText() + "，点击查看");
                    view.setOnClickListener(v -> {
                        if (click != null) {
                            click.onClick(source, snippet);
                        }
                    });
                    sourceContainer.addView(view);
                }
            }

            String meta = buildMeta(result);
            tvMeta.setText(meta);
            tvMeta.setVisibility(TextUtils.isEmpty(meta) ? View.GONE : View.VISIBLE);

            String spoken = refused ? "资料里没有找到相关内容" : answer
                    + (sources.isEmpty() ? "" : "，共 " + sources.size() + " 条来源");
            itemView.setContentDescription("AI 回答：" + spoken);
        }

        /** 类型标签：让用户一眼知道来源是题目、资料还是 Wiki */
        private static String sourceTag(String refType) {
            if (refType == null) {
                return "[资料] ";
            }
            return switch (refType.toUpperCase()) {
                case "QUESTION" -> "[题目] ";
                case "COURSEWARE" -> "[课件] ";
                case "WIKI" -> "[Wiki] ";
                case "KNOWLEDGE" -> "[知识点] ";
                default -> "[资料] ";
            };
        }

        /** 元信息：生成器 / 检索模式 / 耗时——让「这是模型答的还是抽取的」可见 */
        private static String buildMeta(Models.AskResult result) {
            if (result == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (!TextUtils.isEmpty(result.generator)) {
                sb.append("生成器：").append(result.generator);
            }
            if (Boolean.TRUE.equals(result.degraded)) {
                sb.append(sb.length() > 0 ? " · " : "").append("已降级");
            }
            if (!TextUtils.isEmpty(result.retrievalMode)) {
                sb.append(sb.length() > 0 ? " · " : "").append(result.retrievalMode);
            }
            if (result.recallCount != null && result.recallCount > 0) {
                sb.append(" · 召回 ").append(result.recallCount).append(" 块");
            }
            if (result.costMillis != null) {
                sb.append(" · ").append(result.costMillis).append("ms");
            }
            return sb.toString();
        }
    }
}
