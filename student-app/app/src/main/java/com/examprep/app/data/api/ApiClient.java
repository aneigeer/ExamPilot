package com.examprep.app.data.api;

import android.content.Context;
import android.content.Intent;

import com.examprep.app.BuildConfig;
import com.examprep.app.data.prefs.TokenStore;
import com.examprep.app.ui.login.LoginActivity;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/** Retrofit 单例：自动带 token，401 时清除登录态并跳登录页 */
public class ApiClient {

    private static volatile ApiService api;
    private static volatile ApiService longApi;
    private static Context appContext;

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public static ApiService api() {
        if (api == null) {
            synchronized (ApiClient.class) {
                if (api == null) {
                    api = build(30);
                }
            }
        }
        return api;
    }

    /**
     * 长耗时接口专用实例（读超时 90 秒）
     *
     * <p><b>为什么必须单独一个实例</b>：AI 面试单次作答要「检索 + 大模型评分」，
     * 本地 7B 模型实测 15–25 秒，云端也可能到 30 秒以上；默认 30 秒读超时会让
     * 每次作答都被掐断，用户看到的是「面试服务暂不可用」——而服务其实只是慢。
     *
     * <p>不把全局超时调大：其余接口失败时应快速反馈，不能让用户干等 90 秒。
     */
    public static ApiService apiLong() {
        if (longApi == null) {
            synchronized (ApiClient.class) {
                if (longApi == null) {
                    longApi = build(90);
                }
            }
        }
        return longApi;
    }

    /** 构建 Retrofit 实例（共用鉴权/日志拦截器，仅读超时不同） */
    private static ApiService build(int readTimeoutSeconds) {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(BuildConfig.DEBUG
                ? HttpLoggingInterceptor.Level.BODY : HttpLoggingInterceptor.Level.BASIC);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .addInterceptor(ApiClient::auth)
                .addInterceptor(logging)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BuildConfig.BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        return retrofit.create(ApiService.class);
    }

    private static Response auth(Interceptor.Chain chain) throws IOException {
        Request.Builder builder = chain.request().newBuilder();
        String token = appContext == null ? null : TokenStore.get(appContext).token();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        Response response = chain.proceed(builder.build());
        if (response.code() == 401 && appContext != null && TokenStore.get(appContext).hasToken()) {
            // 登录过期：清态 + 回登录页
            TokenStore.get(appContext).clear();
            Intent intent = new Intent(appContext, LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            appContext.startActivity(intent);
        }
        return response;
    }
}
