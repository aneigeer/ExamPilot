package com.examprep.app.ui.login;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.data.prefs.TokenStore;
import com.examprep.app.databinding.ActivityLoginBinding;
import com.examprep.app.ui.main.MainActivity;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 登录页：学号 + 密码；支持游客浏览 */
public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;
    private ApiService api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TokenStore store = TokenStore.get(this);
        if (store.hasToken()) {
            goMain();
            return;
        }
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();

        binding.btnLogin.setOnClickListener(v -> doLogin());
        binding.btnGuest.setOnClickListener(v -> goMain());
    }

    private void doLogin() {
        String no = String.valueOf(binding.etStudentNo.getText()).trim();
        String pwd = String.valueOf(binding.etPassword.getText());
        if (no.isEmpty() || pwd.isEmpty()) {
            toast("请输入学号和密码");
            return;
        }
        binding.btnLogin.setEnabled(false);
        api.login(new ApiService.LoginBody(no, pwd)).enqueue(new Callback<Models.ApiResult<Models.LoginData>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.LoginData>> call,
                                   Response<Models.ApiResult<Models.LoginData>> resp) {
                binding.btnLogin.setEnabled(true);
                Models.ApiResult<Models.LoginData> r = resp.body();
                if (r != null && r.ok() && r.data != null) {
                    TokenStore.get(LoginActivity.this).save(r.data.token, r.data.user);
                    toast("欢迎，" + (r.data.user == null ? no : r.data.user.nickname));
                    goMain();
                } else {
                    toast(r == null ? "登录失败" : r.message);
                }
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.LoginData>> call, Throwable t) {
                binding.btnLogin.setEnabled(true);
                toast("网络错误，请确认后端已启动：" + t.getMessage());
            }
        });
    }

    private void goMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
