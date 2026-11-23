package com.examprep.app.ui.account;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.data.prefs.TokenStore;
import com.examprep.app.databinding.ActivityAccountBinding;
import com.examprep.app.ui.login.LoginActivity;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 账号设置：修改密码 + 数据导出 + 账号注销（大纲 7.3「账号设置」/ 上架合规） */
public class AccountActivity extends AppCompatActivity {

    private ActivityAccountBinding binding;
    private ApiService api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAccountBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnChange.setOnClickListener(v -> change());
        binding.btnExport.setOnClickListener(v -> exportData());
        binding.btnDeactivate.setOnClickListener(v -> confirmDeactivate());
    }

    private void change() {
        String oldPwd = String.valueOf(binding.etOld.getText());
        String newPwd = String.valueOf(binding.etNew.getText());
        String confirm = String.valueOf(binding.etConfirm.getText());
        if (oldPwd.isEmpty() || newPwd.isEmpty()) {
            toast("请填写完整");
            return;
        }
        if (newPwd.length() < 8) {
            toast("新密码至少 8 位");
            return;
        }
        if (!newPwd.equals(confirm)) {
            toast("两次输入的新密码不一致");
            return;
        }
        binding.btnChange.setEnabled(false);
        api.changePassword(new ApiService.ChangePasswordBody(oldPwd, newPwd))
                .enqueue(new Callback<Models.ApiResult<Object>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Object>> call,
                                           Response<Models.ApiResult<Object>> resp) {
                        binding.btnChange.setEnabled(true);
                        // F2b：旧密码错误现在返回 400，Retrofit 非 2xx 时 body() 为 null，
                        // 具体原因在 errorBody()，否则只会提示「修改失败」。
                        if (!resp.isSuccessful()) {
                            toast(parseErrorMessage(resp, "修改失败"));
                            return;
                        }
                        Models.ApiResult<Object> r = resp.body();
                        if (r != null && r.ok()) {
                            toast("密码修改成功");
                            finish();
                        } else {
                            toast(r == null ? "修改失败" : r.message);
                        }
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Object>> call, Throwable t) {
                        binding.btnChange.setEnabled(true);
                        toast("网络错误：" + t.getMessage());
                    }
                });
    }

    /** 导出个人数据：拿到 JSON 后落盘为文件，再唤起系统「保存/分享」 */
    private void exportData() {
        binding.btnExport.setEnabled(false);
        api.exportData().enqueue(new Callback<Models.ApiResult<com.google.gson.JsonElement>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<com.google.gson.JsonElement>> call,
                                   Response<Models.ApiResult<com.google.gson.JsonElement>> resp) {
                binding.btnExport.setEnabled(true);
                Models.ApiResult<com.google.gson.JsonElement> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    toast(r == null ? "导出失败" : r.message);
                    return;
                }
                try {
                    File dir = new File(getExternalFilesDir(null), "export");
                    if (!dir.exists() && !dir.mkdirs()) {
                        toast("导出失败：无法创建目录");
                        return;
                    }
                    File file = new File(dir,
                            "examprep-export-" + System.currentTimeMillis() + ".json");
                    String json = new GsonBuilder().setPrettyPrinting().create().toJson(r.data);
                    try (FileOutputStream os = new FileOutputStream(file)) {
                        os.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                    shareFile(file);
                } catch (Exception e) {
                    toast("导出失败：" + e.getMessage());
                }
            }

            @Override
            public void onFailure(Call<Models.ApiResult<com.google.gson.JsonElement>> call,
                                  Throwable t) {
                binding.btnExport.setEnabled(true);
                toast("网络错误：" + t.getMessage());
            }
        });
    }

    private void shareFile(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/json");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, "ExamPilot · 个人数据导出");
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "保存或分享导出文件"));
    }

    /** 注销是高风险不可逆操作：二次确认 + 输入当前密码 */
    private void confirmDeactivate() {
        final EditText input = new EditText(this);
        input.setHint("请输入当前密码");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle("注销账号")
                .setMessage("注销后个人身份信息即刻匿名化、不可恢复，且无法再用该账号登录。确定继续？")
                .setView(input)
                .setPositiveButton("确认注销", (d, w) -> {
                    String pwd = String.valueOf(input.getText()).trim();
                    if (pwd.isEmpty()) {
                        toast("请输入密码");
                        return;
                    }
                    doDeactivate(pwd);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doDeactivate(String password) {
        binding.btnDeactivate.setEnabled(false);
        api.deactivate(new ApiService.DeactivateBody(password, "注销账号"))
                .enqueue(new Callback<Models.ApiResult<Object>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Object>> call,
                                           Response<Models.ApiResult<Object>> resp) {
                        binding.btnDeactivate.setEnabled(true);
                        // F2b：注销时原密码错误返回 400（OLD_PASSWORD_WRONG），body() 为 null，
                        // 具体原因在 errorBody()，否则只会提示「注销失败」。
                        if (!resp.isSuccessful()) {
                            toast(parseErrorMessage(resp, "注销失败"));
                            return;
                        }
                        Models.ApiResult<Object> r = resp.body();
                        if (r != null && r.ok()) {
                            TokenStore.get(AccountActivity.this).clear();
                            toast("账号已注销");
                            Intent intent = new Intent(AccountActivity.this, LoginActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            startActivity(intent);
                            finish();
                        } else {
                            toast(r == null ? "注销失败" : r.message);
                        }
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Object>> call, Throwable t) {
                        binding.btnDeactivate.setEnabled(true);
                        toast("网络错误：" + t.getMessage());
                    }
                });
    }

    /**
     * F2b：解析非 2xx 响应体的 {code,message}，取出后端返回的具体失败原因。
     * Retrofit 的 body() 在非 2xx 时为 null，错误体只能从 errorBody() 读且只能读一次；
     * 解析失败时降级为「默认文案（HTTP 状态码）」。
     */
    private String parseErrorMessage(Response<?> resp, String fallback) {
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
        return fallback + "（HTTP " + resp.code() + "）";
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
