package com.examprep.app.ui.login;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.data.prefs.TokenStore;
import com.examprep.app.databinding.ActivityLoginBinding;
import com.examprep.app.ui.main.MainActivity;
import com.examprep.app.ui.register.RegisterActivity;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 登录页：隐私同意 → 学号 + 密码；支持游客浏览 */
public class LoginActivity extends AppCompatActivity {

    /** 应用级偏好（与登录态分开存，避免清登录态时误清隐私同意记录） */
    private static final String PREF_APP = "app";
    private static final String KEY_PRIVACY_ACCEPTED = "privacy_accepted";

    private static final String PRIVACY_TEXT =
            "为提供备考资料与题库服务，本应用会收集并使用以下信息：\n\n"
                    + "· 账号信息：学号、昵称（用于登录与身份识别）\n"
                    + "· 学习数据：练习记录、错题、收藏与笔记（用于学情画像与推荐）\n"
                    + "· 上传内容：你主动提交的资料文件（用于共享与审核）\n"
                    + "· 设备与日志：用于故障排查与服务安全\n\n"
                    + "我们不会向第三方出售你的个人信息。你可以随时在“我的”页面查看与修改资料，"
                    + "或停止使用本应用。\n\n"
                    + "点击“同意并继续”即表示你已阅读并同意《用户协议》与《隐私政策》。";

    private ActivityLoginBinding binding;
    private ApiService api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 上架合规要求：首次启动必须先取得隐私政策同意
        if (!privacyAccepted()) {
            showPrivacyDialog();
            return;
        }
        init();
    }

    private void init() {
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
        binding.tvGoRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
        binding.tvServerConfig.setOnClickListener(v -> showServerConfigDialog());
    }

    /** 运行时配置后端基地址（便于真机/不同局域网环境切换，无需重编 APK） */
    private void showServerConfigDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(ApiClient.getBaseUrl(this));
        input.setSelection(input.getText().length());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new AlertDialog.Builder(this)
                .setTitle("服务器地址设置")
                .setMessage("输入后端 API 基地址（须以 http:// 或 https:// 开头）")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String url = String.valueOf(input.getText()).trim();
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        toast("地址须以 http:// 或 https:// 开头");
                        return;
                    }
                    ApiClient.setBaseUrl(this, url);
                    api = ApiClient.api();
                    toast("已切换服务器：" + ApiClient.getBaseUrl(this));
                })
                .setNeutralButton("恢复默认", (dialog, which) -> {
                    ApiClient.setBaseUrl(this, null);
                    api = ApiClient.api();
                    toast("已恢复默认地址：" + ApiClient.getBaseUrl(this));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------
    // 隐私政策同意
    // ------------------------------------------------------------------

    private SharedPreferences appPrefs() {
        return getSharedPreferences(PREF_APP, MODE_PRIVATE);
    }

    private boolean privacyAccepted() {
        return appPrefs().getBoolean(KEY_PRIVACY_ACCEPTED, false);
    }

    /** 不可取消的隐私政策弹窗：同意才继续，不同意直接退出应用 */
    private void showPrivacyDialog() {
        new AlertDialog.Builder(this)
                .setTitle("隐私政策与用户协议")
                .setMessage(PRIVACY_TEXT)
                .setCancelable(false)
                .setPositiveButton("同意并继续", (dialog, which) -> {
                    appPrefs().edit().putBoolean(KEY_PRIVACY_ACCEPTED, true).apply();
                    init();
                })
                .setNegativeButton("不同意", (dialog, which) -> finish())
                .show();
    }

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

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
                // F2b：后端登录失败返回 401/400，Retrofit 在非 2xx 时 body() 为 null、
                // 错误体在 errorBody()，这里先取具体原因再走成功分支，避免只提示「登录失败」。
                if (!resp.isSuccessful()) {
                    toast(parseErrorMessage(resp));
                    return;
                }
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

    /**
     * F2b：解析非 2xx 响应体的 {code,message}。
     * 后端改造后登录失败返回 401（业务码仍为 1001 等），Retrofit 的 body() 为 null，
     * 只能从 errorBody() 取；errorBody 只能读一次，解析失败时降级为带状态码的通用文案。
     */
    private String parseErrorMessage(Response<?> resp) {
        try {
            if (resp.errorBody() != null) {
                String raw = resp.errorBody().string();
                Models.ApiResult<?> err = new com.google.gson.Gson()
                        .fromJson(raw, Models.ApiResult.class);
                if (err != null && err.message != null && !err.message.trim().isEmpty()) {
                    return err.message;
                }
            }
        } catch (Exception ignored) {
            // 错误体为空或非 JSON，走下面兜底
        }
        return "登录失败（HTTP " + resp.code() + "）";
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
