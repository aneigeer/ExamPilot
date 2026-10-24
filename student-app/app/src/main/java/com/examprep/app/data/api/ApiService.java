package com.examprep.app.data.api;

import com.examprep.app.data.model.Models;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;

import okhttp3.MultipartBody;

/** 后端接口定义（与后端 151 个接口按需对应声明） */
public interface ApiService {

    // ---------- 认证 ----------

    @POST("auth/login")
    Call<Models.ApiResult<Models.LoginData>> login(@Body LoginBody body);

    public static class LoginBody {
        public final String studentNo;
        public final String password;

        public LoginBody(String studentNo, String password) {
            this.studentNo = studentNo;
            this.password = password;
        }
    }

    /** 自助注册：成功后直接返回令牌（自动登录），结构与 login 一致 */
    @POST("auth/register")
    Call<Models.ApiResult<Models.LoginData>> register(@Body Models.RegisterRequest body);

    @GET("schools")
    Call<Models.ApiResult<List<Models.School>>> schools();

    @POST("auth/logout")
    Call<Models.ApiResult<Object>> logout();

    // ---------- 首页 ----------

    @GET("home")
    Call<Models.ApiResult<Models.Home>> home();

    // ---------- 课程库 ----------

    @GET("colleges")
    Call<Models.ApiResult<List<Models.College>>> colleges(@Query("schoolId") Long schoolId);

    @GET("semesters")
    Call<Models.ApiResult<List<Models.Semester>>> semesters();

    @GET("courses")
    Call<Models.ApiResult<List<Models.Course>>> courses(
            @Query("collegeId") Long collegeId,
            @Query("semesterId") Long semesterId);

    // ---------- 考试体系 ----------

    @GET("subjects")
    Call<Models.ApiResult<List<Models.Subject>>> subjects(@Query("examCategoryId") Long examCategoryId);

    // ---------- 题库 ----------

    @GET("questions/types")
    Call<Models.ApiResult<List<Models.QuestionTypeItem>>> questionTypes();

    @GET("questions")
    Call<Models.ApiResult<Models.Page<Models.Question>>> questions(
            @Query("subjectId") Long subjectId,
            @Query("type") String type,
            @Query("page") long page,
            @Query("size") long size);

    @GET("questions/{id}")
    Call<Models.ApiResult<Models.Question>> questionDetail(@Path("id") long id);

    // ---------- 刷题 ----------

    @POST("practice/start")
    Call<Models.ApiResult<Models.Practice>> practiceStart(@Body PracticeStartBody body);

    public static class PracticeStartBody {
        public final Long subjectId;
        public final String mode;
        public final String questionType;
        public final Integer difficulty;
        public final String objectiveFilter;
        public final int count;
        /** 限定知识点范围（学习计划的任务按知识点排，跳过来必须带上） */
        public final Long knowledgeId;

        public PracticeStartBody(Long subjectId, String mode, String questionType,
                                 Integer difficulty, String objectiveFilter, int count) {
            this(subjectId, mode, questionType, difficulty, objectiveFilter, count, null);
        }

        public PracticeStartBody(Long subjectId, String mode, String questionType,
                                 Integer difficulty, String objectiveFilter, int count,
                                 Long knowledgeId) {
            this.subjectId = subjectId;
            this.mode = mode;
            this.questionType = questionType;
            this.difficulty = difficulty;
            this.objectiveFilter = objectiveFilter;
            this.count = count;
            this.knowledgeId = knowledgeId;
        }
    }

    @GET("practice/{id}/next")
    Call<Models.ApiResult<NextQuestion>> practiceNext(@Path("id") long practiceId);

    public static class NextQuestion {
        public Long practiceId;
        public int totalCount;
        public int answeredCount;
        public int correctCount;
        public boolean finished;
        public Models.Question question;
    }

    @POST("practice/{id}/answer")
    Call<Models.ApiResult<Models.AnswerResult>> practiceAnswer(
            @Path("id") long practiceId, @Body AnswerBody body);

    public static class AnswerBody {
        public final Long questionId;
        public final String userAnswer;

        public AnswerBody(Long questionId, String userAnswer) {
            this.questionId = questionId;
            this.userAnswer = userAnswer;
        }
    }

    @POST("practice/{id}/self-eval")
    Call<Models.ApiResult<Object>> practiceSelfEval(
            @Path("id") long practiceId, @Body SelfEvalBody body);

    public static class SelfEvalBody {
        public final Long questionId;
        public final String selfEval;

        public SelfEvalBody(Long questionId, String selfEval) {
            this.questionId = questionId;
            this.selfEval = selfEval;
        }
    }

    @POST("practice/{id}/finish")
    Call<Models.ApiResult<Models.Report>> practiceFinish(@Path("id") long practiceId);

    @GET("practice/{id}/report")
    Call<Models.ApiResult<Models.Report>> practiceReport(@Path("id") long practiceId);

    // ---------- 错题本 ----------

    @GET("wrong-book")
    Call<Models.ApiResult<Models.Page<Models.WrongBookItem>>> wrongBook(
            @Query("page") long page, @Query("size") long size);

    @DELETE("wrong-book/{questionId}")
    Call<Models.ApiResult<Object>> wrongBookRemove(@Path("questionId") long questionId);

    // ---------- 错题间隔重复复习（SRS） ----------

    /** 待复习错题：已到期且未掌握，按到期时间升序 */
    @GET("wrong-book/review")
    Call<Models.ApiResult<List<Models.ReviewItem>>> reviewDue(@Query("limit") int limit);

    /** 提交一次复习结果（用户自评对错），返回新的复习阶段与提示语 */
    @POST("wrong-book/review/{questionId}")
    Call<Models.ApiResult<Models.ReviewResult>> submitReview(
            @Path("questionId") long questionId, @Query("correct") boolean correct);

    // ---------- 模拟考试（P1） ----------

    @POST("exams/mock/start")
    Call<Models.ApiResult<Models.MockPaper>> mockStart(@Body MockStartBody body);

    public static class MockStartBody {
        public final Long subjectId;
        public final int count;
        public final int durationMinutes;

        public MockStartBody(Long subjectId, int count, int durationMinutes) {
            this.subjectId = subjectId;
            this.count = count;
            this.durationMinutes = durationMinutes;
        }
    }

    @GET("exams/mock/{id}")
    Call<Models.ApiResult<Models.MockPaper>> mockPaper(@Path("id") long recordId);

    @POST("exams/mock/{id}/answer")
    Call<Models.ApiResult<Integer>> mockAnswer(
            @Path("id") long recordId, @Body AnswerBody body);

    @POST("exams/mock/{id}/submit")
    Call<Models.ApiResult<Models.MockReport>> mockSubmit(@Path("id") long recordId);

    @GET("exams/mock/{id}/report")
    Call<Models.ApiResult<Models.MockReport>> mockReport(@Path("id") long recordId);

    // ---------- 收藏 / 通知列表 ----------

    @GET("favorites/resources")
    Call<Models.ApiResult<Models.Page<Models.Resource>>> favoriteResources(
            @Query("page") long page, @Query("size") long size);

    @GET("notifications")
    Call<Models.ApiResult<Models.Page<Models.Notification>>> notifications(
            @Query("page") long page, @Query("size") long size);

    @PUT("notifications/read-all")
    Call<Models.ApiResult<Object>> notificationsReadAll();

    // ---------- 审计补齐（题库 tab / 上传 / 举报 / 申诉 / 收藏题 / 账号 / 搜索） ----------

    @GET("exams")
    Call<Models.ApiResult<List<Models.ExamBrief>>> exams();

    @GET("subjects/{id}/knowledge-tree")
    Call<Models.ApiResult<List<Models.KnowledgeNode>>> knowledgeTree(@Path("id") long subjectId);

    @POST("resources/{id}/report")
    Call<Models.ApiResult<Object>> reportResource(
            @Path("id") long id, @Query("reason") String reason, @Query("description") String description);

    @GET("appeals/mine")
    Call<Models.ApiResult<List<Models.Appeal>>> myAppeals();

    @POST("appeals")
    Call<Models.ApiResult<Models.Appeal>> createAppeal(
            @Query("resourceId") long resourceId, @Query("reason") String reason);

    @GET("favorites/questions")
    Call<Models.ApiResult<Models.Page<Models.FavoriteQuestion>>> favoriteQuestions(
            @Query("page") long page, @Query("size") long size);

    @GET("resources/mine")
    Call<Models.ApiResult<Models.Page<Models.Resource>>> myUploads(
            @Query("page") long page, @Query("size") long size);

    @PUT("auth/password")
    Call<Models.ApiResult<Object>> changePassword(@Body ChangePasswordBody body);

    public static class ChangePasswordBody {
        public final String oldPassword;
        public final String newPassword;

        public ChangePasswordBody(String oldPassword, String newPassword) {
            this.oldPassword = oldPassword;
            this.newPassword = newPassword;
        }
    }

    @GET("search/resources")
    Call<Models.ApiResult<Models.Page<Models.Resource>>> searchResources(
            @Query("keyword") String keyword, @Query("page") long page, @Query("size") long size);

    @GET("notes")
    Call<Models.ApiResult<List<Models.QuestionNoteItem>>> myNotes(
            @Query("questionId") Long questionId);

    @Multipart
    @POST("files/image")
    Call<Models.ApiResult<FileUploadData>> uploadImage(@retrofit2.http.Part MultipartBody.Part file);

    public static class FileUploadData {
        public Long fileId;
        public String fileName;
        public long size;
    }

    public static class ResourceSubmitBody {
        public String title;
        public Long examCategoryId;
        public Long subjectId;
        public String type;
        public String description;
        public String tags;
        public Long fileId;
    }

    @POST("resources")
    Call<Models.ApiResult<Models.Resource>> createResource(@Body ResourceSubmitBody body);

    // ---------- 资料 ----------

    @GET("resources")
    Call<Models.ApiResult<Models.Page<Models.Resource>>> resources(
            @Query("page") long page,
            @Query("size") long size,
            @Query("type") String type,
            @Query("tags") String tags,
            @Query("keyword") String keyword);

    @GET("resources/{id}")
    Call<Models.ApiResult<Models.Resource>> resourceDetail(@Path("id") long id);

    @GET("resources/{id}/preview")
    Call<Models.ApiResult<Models.Preview>> resourcePreview(@Path("id") long id);

    @POST("resources/{id}/download")
    Call<Models.ApiResult<Models.DownloadInfo>> resourceDownload(@Path("id") long id);

    @GET("resources/types")
    Call<Models.ApiResult<List<ResourceType>>> resourceTypes();

    public static class ResourceType {
        public String type;
        public String label;
    }

    // ---------- 收藏 / 评论 / 点赞 / 评分 ----------

    @POST("favorites")
    Call<Models.ApiResult<FavResult>> favoriteToggle(
            @Query("targetType") String targetType, @Query("targetId") long targetId);

    public static class FavResult {
        public boolean favorited;
    }

    @GET("favorites/check")
    Call<Models.ApiResult<FavResult>> favoriteCheck(
            @Query("targetType") String targetType, @Query("targetId") long targetId);

    @GET("follows")
    Call<Models.ApiResult<List<java.util.Map<String, Object>>>> follows(@Query("targetType") String targetType);

    @POST("follows")
    Call<Models.ApiResult<java.util.Map<String, Object>>> followsToggle(
            @Query("targetType") String targetType, @Query("targetId") long targetId);

    @GET("comments")
    Call<Models.ApiResult<List<Models.Comment>>> comments(
            @Query("targetType") String targetType, @Query("targetId") long targetId);

    @POST("comments")
    Call<Models.ApiResult<Models.Comment>> commentAdd(
            @Query("targetType") String targetType,
            @Query("targetId") long targetId,
            @Query("content") String content);

    @POST("comments/{id}/like")
    Call<Models.ApiResult<LikeResult>> commentLike(@Path("id") long id);

    public static class LikeResult {
        public boolean liked;
    }

    // ---------- 我的 ----------

    @GET("me/stats")
    Call<Models.ApiResult<Models.MyStats>> myStats();

    @GET("me/mastery")
    Call<Models.ApiResult<Models.Mastery>> myMastery(@Query("subjectId") Long subjectId);

    @GET("me/downloads")
    Call<Models.ApiResult<Models.Page<Models.Resource>>> myDownloads(
            @Query("page") long page, @Query("size") long size);

    @GET("notifications/unread-count")
    Call<Models.ApiResult<Long>> unreadCount();

    // ---------- 主观题 AI 判分 ----------

    /** 对照参考答案判分；服务端不可用时返回 available=false，客户端回退自评 */
    @POST("questions/{id}/grade")
    Call<Models.ApiResult<Models.GradeResult>> gradeQuestion(
            @Path("id") Long questionId, @Body GradeBody body);

    public static class GradeBody {
        public final String answer;

        public GradeBody(String answer) {
            this.answer = answer;
        }
    }

    // ---------- 学习计划 ----------

    @GET("me/study-plan")
    Call<Models.ApiResult<Models.StudyPlan>> studyPlan();

    @PUT("me/study-plan/target")
    Call<Models.ApiResult<Models.StudyPlan>> setStudyTarget(@Body StudyTargetBody body);

    public static class StudyTargetBody {
        public final Integer dailyTarget;
        public final Long subjectId;

        public StudyTargetBody(Integer dailyTarget, Long subjectId) {
            this.dailyTarget = dailyTarget;
            this.subjectId = subjectId;
        }
    }

    // ---------- LLM Wiki 主题页 ----------

    /** 已发布的 Wiki 主题页列表（公开内容，游客可读） */
    @GET("wiki/pages")
    Call<Models.ApiResult<Models.Page<Models.WikiPage>>> wikiPages(
            @Query("page") long page,
            @Query("size") long size,
            @Query("subjectId") Long subjectId,
            @Query("keyword") String keyword);

    /** Wiki 主题页详情（含正文与关联页） */
    @GET("wiki/pages/{id}")
    Call<Models.ApiResult<Models.WikiPage>> wikiPage(@Path("id") long id);

    /** Wiki 导航图（节点 + 有向边） */
    @GET("wiki/graph")
    Call<Models.ApiResult<Models.WikiGraph>> wikiGraph(@Query("subjectId") Long subjectId);

    // ---------- 账号生命周期（合规：数据导出 / 注销） ----------

    /** 导出个人数据：返回任意结构 JSON，交给客户端落盘/分享 */
    @GET("me/export")
    Call<Models.ApiResult<com.google.gson.JsonElement>> exportData();

    @POST("me/deactivate")
    Call<Models.ApiResult<Object>> deactivate(@Body DeactivateBody body);

    public static class DeactivateBody {
        public final String password;
        public final String confirmText;

        public DeactivateBody(String password, String confirmText) {
            this.password = password;
            this.confirmText = confirmText;
        }
    }
}
