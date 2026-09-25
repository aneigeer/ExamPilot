package com.examprep.app.data.api;

import com.examprep.app.data.model.Models;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

/** 后端接口定义（与后端 132 个接口一一对应，按需声明） */
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

    @POST("auth/logout")
    Call<Models.ApiResult<Object>> logout();

    // ---------- 首页 ----------

    @GET("home")
    Call<Models.ApiResult<Models.Home>> home();

    // ---------- 课程库 ----------

    @GET("colleges")
    Call<Models.ApiResult<List<Models.College>>> colleges();

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

        public PracticeStartBody(Long subjectId, String mode, String questionType,
                                 Integer difficulty, String objectiveFilter, int count) {
            this.subjectId = subjectId;
            this.mode = mode;
            this.questionType = questionType;
            this.difficulty = difficulty;
            this.objectiveFilter = objectiveFilter;
            this.count = count;
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
}
