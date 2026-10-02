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
}
