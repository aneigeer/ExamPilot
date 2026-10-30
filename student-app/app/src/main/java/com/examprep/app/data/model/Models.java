package com.examprep.app.data.model;

import java.util.List;

/** 后端统一响应与全部数据模型（Gson 按 public 字段反序列化） */
public class Models {

    public static class ApiResult<T> {
        public int code;
        public String message;
        public T data;
        public String traceId;

        public boolean ok() {
            return code == 200;
        }
    }

    public static class LoginData {
        public String token;
        public User user;
    }

    // ---------- 注册 ----------

    public static class School {
        public Long id;
        public String name;
        public String code;
        public Integer sort;
    }

    /** 注册请求体（与后端 RegisterRequest 对齐） */
    public static class RegisterRequest {
        public String studentNo;
        public String nickname;
        public String password;
        public Long schoolId;
        public Long collegeId;
        public String grade;
    }

    public static class User {
        public Long id;
        public String studentNo;
        public String nickname;
        public String realName;
        public String role;
    }

    public static class Page<T> {
        public long page;
        public long size;
        public long total;
        public List<T> list;
    }

    // ---------- 首页聚合 ----------

    public static class Home {
        public List<Announcement> announcements;
        public List<ExamBrief> exams;
        public List<Subject> hotSubjects;
        public List<Resource> latestResources;
        public List<Resource> hotResources;
        public Stats stats;
    }

    public static class Stats {
        public long subjectCount;
        public long questionCount;
        public long resourceCount;
        public long userCount;
    }

    public static class Announcement {
        public Long id;
        public String title;
        public String content;
        public String publishAt;
    }

    public static class ExamBrief {
        public Long id;
        public String code;
        public String name;
        public String icon;
        public Long subjectCount;
    }

    public static class Subject {
        public Long id;
        public String name;
        public String description;
        public Long examCategoryId;
        public long resourceCount;
        public long questionCount;
    }

    // ---------- 课程库 ----------

    public static class College {
        public Long id;
        public String name;
    }

    public static class Semester {
        public Long id;
        public String name;
    }

    public static class Course {
        public Long id;
        public String name;
        public String teacherName;
        public String semesterName;
        public String collegeName;
        public String className;
        public double credit;
    }

    // ---------- 资料库 ----------

    public static class Resource {
        public Long id;
        public String title;
        public String typeName;
        public String examCategoryName;
        public String subjectName;
        public String uploaderName;
        public String description;
        public String tags;
        public String auditStatus;
        public int downloadCount;
        public double ratingAvg;
        public int ratingCount;
        public int likeCount;
        public String createdAt;
    }

    public static class Preview {
        public Long resourceId;
        public String title;
        public int totalPages;
        public int visiblePages;
        public boolean guest;
        public String tip;
        public List<PageImg> pages;
    }

    public static class PageImg {
        public int pageNo;
        public String url;
    }

    public static class DownloadInfo {
        public String fileName;
        public String url;
        public long expiresIn;
    }

    public static class Comment {
        public Long id;
        public Long userId;
        public String nickname;
        public String content;
        public int likeCount;
        public boolean liked;
        public boolean mine;
        public String createdAt;
    }

    // ---------- 题库与刷题 ----------

    public static class Question {
        public Long id;
        public String type;
        public String typeName;
        public String stem;
        public String material;
        public int difficulty;
        public int score;
        public String analysis;
        public String tags;
        public List<Option> options;
        public String answerContent;
        public List<SubQ> subQuestions;
        public String audioUrl;
    }

    public static class Option {
        public String key;
        public String content;
        public Boolean isAnswer;
    }

    public static class SubQ {
        public Long id;
        public String type;
        public String typeName;
        public String stem;
        public int score;
        public List<Option> options;
    }

    public static class QuestionTypeItem {
        public String type;
        public String label;
        public boolean objective;
    }

    public static class Practice {
        public Long practiceId;
        public int totalCount;
        public int answeredCount;
        public int correctCount;
        public String status;
        public Question question;
    }

    public static class AnswerResult {
        public Long questionId;
        public Boolean correct;
        public String correctAnswer;
        public String analysis;
        public boolean needSelfEval;
        public Object referenceAnswer;
        public Object gradingPoints;
    }

    /** 主观题 AI 判分结果（与服务端 AiGradingService.GradeResult 字段一一对应） */
    public static class GradeResult {
        /** 判分是否真的执行了；false 时应回退到自评 */
        public boolean available;
        /** EXCELLENT / GOOD / PARTIAL / POOR / INVALID / UNAVAILABLE */
        public String level;
        /** 参考得分率 0~1，不是精确分数 */
        public double scoreRate;
        public java.util.List<String> hitPoints;
        public java.util.List<String> missedPoints;
        public String comment;
        public String generator;
        public String reason;
    }

    /** 今日学习计划 */
    public static class StudyPlan {
        public String date;
        public Integer dailyTarget;
        public Long subjectId;
        public Integer plannedCount;
        public Integer doneCount;
        public Integer completionPercent;
        public java.util.List<StudyPlanItem> items;
        public java.util.List<String> notes;
    }

    /** 学习计划中的一条任务 */
    public static class StudyPlanItem {
        public String type;
        public Long knowledgeId;
        public String knowledgeName;
        public Integer targetCount;
        public Integer doneCount;
        public Boolean completed;
        public String reason;
        public Integer estimatedMinutes;
        public String practiceMode;
    }

    public static class Report implements java.io.Serializable {
        public Long practiceId;
        public String status;
        public int totalCount;
        public int answeredCount;
        public int correctCount;
        public double accuracy;
        public long durationSeconds;
        public List<ReportItem> items;
    }

    public static class ReportItem implements java.io.Serializable {
        public Long questionId;
        public String type;
        public String typeName;
        public String stem;
        public String userAnswer;
        public Boolean correct;
        public String selfEval;
        public String correctAnswer;
        public String analysis;
        public double scoreGot;
    }

    // ---------- 我的 ----------

    public static class MyStats {
        public long practiceCount;
        public long totalAnswered;
        public long totalCorrect;
        public double accuracy;
        public long wrongBookCount;
        public long favoriteResourceCount;
        public long favoriteQuestionCount;
        public long downloadCount;
        public long uploadCount;
        public long unreadNoticeCount;
    }

    public static class Mastery {
        public String subjectName;
        public List<MasteryItem> items;
    }

    public static class MasteryItem {
        public Long knowledgeId;
        public String knowledgeName;
        public long answered;
        public long correct;
        public double masteryRate;
    }

    public static class WrongBookItem {
        public Long questionId;
        public String typeName;
        public String stem;
        public int wrongCount;
        public String lastWrongAt;
        public Question question;
    }

    // ---------- 错题间隔重复复习（SRS） ----------

    public static class ReviewItem {
        public Long questionId;
        public String stem;
        public String type;
        public Long subjectId;
        public int wrongCount;
        public int reviewStage;
        public int totalStages;
        public String lastWrongAt;
        public String nextReviewAt;
    }

    public static class ReviewResult {
        public Long questionId;
        public int reviewStage;
        public String nextReviewAt;
        public Boolean mastered;
        public String message;
    }

    // ---------- 模拟考试（P1） ----------

    public static class MockPaper implements java.io.Serializable {
        public Long recordId;
        public Long paperId;
        public String paperName;
        public Integer durationMinutes;
        public String deadline;
        public long remainingSeconds;
        public Integer totalScore;
        public String status;
        public int answeredCount;
        public List<Question> questions;
    }

    public static class MockReport implements java.io.Serializable {
        public Long recordId;
        public String status;
        public int totalScore;
        public double gotScore;
        public double scoreRate;
        public int correctCount;
        public int answeredCount;
        public int totalCount;
        public String submittedAt;
        public List<MockDetail> details;
    }

    public static class MockDetail implements java.io.Serializable {
        public Long questionId;
        public String typeName;
        public String stem;
        public String userAnswer;
        public Boolean correct;
        public String correctAnswer;
        public double scoreGot;
    }

    public static class Notification {
        public Long id;
        public String title;
        public String content;
        public String readAt;
        public String createdAt;
    }

    // ---------- 审计补齐（题库 tab / 笔记 / 收藏题 / 上传 / 搜索） ----------

    public static class KnowledgeNode {
        public Long id;
        public String name;
        public Integer level;
        public List<KnowledgeNode> children;
    }

    public static class FavoriteQuestion {
        public Long id;
        public Long questionId;
        public Question question;
    }

    public static class Appeal {
        public Long id;
        public Long userId;
        public String targetType;
        public Long targetId;
        public String reason;
        public String status;
        public String handleNote;
        public String createdAt;
    }

    public static class QuestionNoteItem {
        public Long id;
        public Long questionId;
        public String typeName;
        public String stem;
        public String content;
        public String createdAt;
        public String updatedAt;
    }

    // ---------- LLM Wiki 主题页 ----------

    public static class WikiPage {
        public Long id;
        public String slug;
        public String title;
        public Long subjectId;
        public Long knowledgeId;
        public String summary;
        public String content;
        public List<String> keyPoints;
        public List<Long> sourceChunkIds;
        public Integer version;
        public Double qualityScore;
        public String updatedAt;
        public List<WikiPage> relatedPages;
    }

    public static class WikiGraph {
        public List<WikiNode> nodes;
        public List<WikiEdge> edges;
    }

    public static class WikiNode {
        public Long id;
        public String slug;
        public String title;
        public Long subjectId;
        public Long knowledgeId;
        public double qualityScore;
        public int degree;
    }

    public static class WikiEdge {
        public Long source;
        public Long target;
        public String relation;
        public double weight;
    }

    // ---------- AI 面试（与 Python AI 服务契约严格一致：snake_case）----------

    /**
     * 开始面试请求体
     *
     * <p>字段名用 {@code @SerializedName} 固定成 snake_case：
     * Java 三层链路（Android → Spring → Python）共用同一套字段名，
     * 任何一层自作主张转驼峰都会让另一层拿到 null（如 {@code subject_id} 丢失后
     * 面试官会跨科目抽题，用户看到的是「问的完全不是我要的科目」）。
     */
    public static class InterviewStartBody {
        public String topic;
        public Integer total;
        @com.google.gson.annotations.SerializedName("subject_id")
        public Long subjectId;

        public InterviewStartBody(String topic, Integer total, Long subjectId) {
            this.topic = topic;
            this.total = total;
            this.subjectId = subjectId;
        }
    }

    /** 开始面试响应：第一题 + 出处 */
    public static class InterviewStartResp {
        @com.google.gson.annotations.SerializedName("session_id")
        public String sessionId;
        public String topic;
        @com.google.gson.annotations.SerializedName("question_no")
        public Integer questionNo;
        public Integer total;
        public String question;
        public InterviewSource source;
    }

    /** 题目的语料出处（标题 / 文件名 / 页码）；可能整体为 null */
    public static class InterviewSource {
        public String title;
        @com.google.gson.annotations.SerializedName("source_file")
        public String sourceFile;
        @com.google.gson.annotations.SerializedName("page_no")
        public Integer pageNo;
    }

    /** 作答请求体 */
    public static class InterviewAnswerBody {
        @com.google.gson.annotations.SerializedName("session_id")
        public String sessionId;
        public String answer;

        public InterviewAnswerBody(String sessionId, String answer) {
            this.sessionId = sessionId;
            this.answer = answer;
        }
    }

    /**
     * 作答响应：未结束时给评分 + 追问 + 下一题；结束（finished=true）时 {@link #report} 非空
     */
    public static class InterviewAnswerResp {
        @com.google.gson.annotations.SerializedName("session_id")
        public String sessionId;
        @com.google.gson.annotations.SerializedName("question_no")
        public Integer questionNo;
        public Integer total;
        /** 本题得分（0-100） */
        public Double score;
        /** 一句话评语 */
        public String verdict;
        @com.google.gson.annotations.SerializedName("hit_points")
        public java.util.List<String> hitPoints;
        @com.google.gson.annotations.SerializedName("miss_points")
        public java.util.List<String> missPoints;
        /** 参考答案 */
        public String reference;
        /** 面试官追问，可能为空串或 null */
        @com.google.gson.annotations.SerializedName("follow_up")
        public String followUp;
        @com.google.gson.annotations.SerializedName("next_question")
        public String nextQuestion;
        /**
         * 本次返回的是「同题追问」而非「下一题」
         *
         * <p>追问轮次里 {@code question_no} <b>不前进</b>，{@code next_question}
         * 就是追问内容本身。App 若按 {@code question_no + 1} 推题号，
         * 追问会被错标成下一题（后续题号整体错位）。
         * 该字段为 Python 侧新增的可选字段，缺省视为 false。
         */
        @com.google.gson.annotations.SerializedName("is_follow_up")
        public Boolean isFollowUp;
        public boolean finished;
        /** 整场报告，仅 finished=true 时非空 */
        public InterviewReport report;
    }

    /** 整场面试报告 */
    public static class InterviewReport {
        @com.google.gson.annotations.SerializedName("total_score")
        public Double totalScore;
        /** 等级文案（如 优秀 / 良好 / 待提升） */
        public String level;
        @com.google.gson.annotations.SerializedName("per_question")
        public java.util.List<InterviewPerQuestion> perQuestion;
        public java.util.List<String> strengths;
        public java.util.List<String> weaknesses;
        public java.util.List<String> suggestions;
        /** 评分是否由降级模型给出（仍依据语料，非编造） */
        public boolean degraded;
    }

    /** 报告中的逐题评语 */
    public static class InterviewPerQuestion {
        public String question;
        public Double score;
        public String comment;
    }

    // ---------- AI 面试历史（复盘）----------

    /** 历史列表项 */
    public static class InterviewHistoryItem {
        public Long id;
        public String topic;
        public Integer totalQuestions;
        /** 实评题数（含追问轮），与计划题数可能不同 */
        public Integer answeredCount;
        public Double totalScore;
        public String level;
        public Integer finished;
        public String startedAt;
        public String finishedAt;
    }

    /** 面试详情（会话 + 逐题记录） */
    public static class InterviewSessionDetail {
        public Long id;
        public String topic;
        public Integer totalQuestions;
        public Integer answeredCount;
        public Double totalScore;
        public String level;
        public Integer finished;
        public String startedAt;
        public String finishedAt;
        public java.util.List<InterviewQaItem> qaList;
    }

    /** 单题作答记录 */
    public static class InterviewQaItem {
        public Long id;
        public Integer questionNo;
        public String question;
        public String answer;
        public Double score;
        public String verdict;
        public java.util.List<String> hitPoints;
        public java.util.List<String> missPoints;
        public String reference;
        public Integer isFollowUp;
        public String createdAt;
    }

    // ---------- AI 问答（/api/ai/ask）----------

    /** 提问请求体（与后端 AskRequest 对齐） */
    public static class AskBody {
        public String question;
        public Long subjectId;
        public Long examCategoryId;

        public AskBody(String question) {
            this.question = question;
        }
    }

    /** 回答来源（可点击跳转题目/资料/Wiki） */
    public static class AskSource {
        public Long chunkId;
        public String title;
        public String snippet;
        public String refType;
        public Long refId;
        public String refPath;
        public Integer offset;
    }

    /** 问答结果（与后端 AskVO 字段一一对应，camelCase） */
    public static class AskResult {
        public String question;
        public String answer;
        /** dashscope / zhipu / ollama / extractive / none */
        public String generator;
        /** RAG / WIKI */
        public String engine;
        public Boolean fallbackFromWiki;
        public Long wikiPageId;
        public String wikiPageSlug;
        public String wikiTitle;
        /** 是否降级（本地抽取/熔断） */
        public Boolean degraded;
        /** 是否拒答（资料里没有相关语料） */
        public Boolean refused;
        public Long costMillis;
        public Long retrievalMillis;
        public String retrievalMode;
        public Integer recallCount;
        public java.util.List<AskSource> sources;
        public java.util.List<String> contexts;
    }

    /** AI 能力探测（进入页面前决定入口可用性/降级提示） */
    public static class AiCapability {
        public Boolean available;
        public String generator;
        public Boolean degraded;
        public String degradedReason;
        public Boolean circuitOpen;
        public Boolean vectorEnabled;
        public Boolean vectorIndexReady;
        public Long chunkCount;
        public String aiServiceStatus;
    }
}
