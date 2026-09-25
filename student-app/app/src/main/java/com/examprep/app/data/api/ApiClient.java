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
    private static Context appContext;

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public static ApiService api() {
        if (api == null) {
            synchronized (ApiClient.class) {
                if (api == null) {
                    HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
                    logging.setLevel(BuildConfig.DEBUG
                            ? HttpLoggingInterceptor.Level.BODY : HttpLoggingInterceptor.Level.BASIC);

                    OkHttpClient client = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .addInterceptor(ApiClient::auth)
                            .addInterceptor(logging)
                            .build();

                    Retrofit retrofit = new Retrofit.Builder()
                            .baseUrl(BuildConfig.BASE_URL)
                            .client(client)
                            .addConverterFactory(GsonConverterFactory.create())
                            .build();
                    api = retrofit.create(ApiService.class);
                }
            }
        }
        return api;
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
