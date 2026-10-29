package com.examprep.app.ui.interview;

import com.examprep.app.data.model.Models;

/**
 * 面试对话的一条消息（RecyclerView 数据单元）
 *
 * <p>用 4 种 type 区分气泡形态：AI 提问 / 我的作答 / 评分卡片 / 整场报告。
 * 之所以不把评分直接拼进「AI 提问」气泡，是因为评分卡片要显示命中/遗漏要点与
 * 参考答案，混排后视障用户读屏会失去结构（TalkBack 只能一句句念，无法跳段），
 * 独立卡片可以被朗读为一整个语义块。
 */
public class InterviewMessage {

    /** AI 提问气泡 */
    public static final int TYPE_QUESTION = 0;
    /** 用户作答气泡 */
    public static final int TYPE_MY_ANSWER = 1;
    /** 评分卡片 */
    public static final int TYPE_SCORE = 2;
    /** 整场报告卡片 */
    public static final int TYPE_REPORT = 3;

    public final int type;

    /** 提问 / 作答正文 */
    public String text;

    /** 元信息（题号） */
    public String meta;

    /** 出处（标题 / 文件名 / 页码），可空 */
    public String source;

    /** 追问（可能为空） */
    public String followUp;

    /** 评分数据（TYPE_SCORE 使用） */
    public Models.InterviewAnswerResp score;

    /** 报告数据（TYPE_REPORT 使用） */
    public Models.InterviewReport report;

    /** 本场期望题数（用于识别「面试官提前收尾、实评题数少于期望」） */
    public Integer expectedTotal;

    private InterviewMessage(int type) {
        this.type = type;
    }

    /** AI 提问 */
    public static InterviewMessage question(String text, String meta, String source,
                                            String followUp) {
        InterviewMessage m = new InterviewMessage(TYPE_QUESTION);
        m.text = text;
        m.meta = meta;
        m.source = source;
        m.followUp = followUp;
        return m;
    }

    /** 我的作答 */
    public static InterviewMessage myAnswer(String answered) {
        InterviewMessage m = new InterviewMessage(TYPE_MY_ANSWER);
        m.text = answered;
        return m;
    }

    /** 评分卡片 */
    public static InterviewMessage score(Models.InterviewAnswerResp resp, int questionNo, int total) {
        InterviewMessage m = new InterviewMessage(TYPE_SCORE);
        m.score = resp;
        m.meta = "第 " + questionNo + " / " + total + " 题";
        return m;
    }

    /** 整场报告 */
    public static InterviewMessage report(Models.InterviewReport report, Integer expectedTotal) {
        InterviewMessage m = new InterviewMessage(TYPE_REPORT);
        m.report = report;
        m.expectedTotal = expectedTotal;
        return m;
    }
}
