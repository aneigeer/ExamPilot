package com.examprep.app.ui.account;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityAccountBinding;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 账号设置：修改密码（大纲 7.3「账号设置」） */
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
    }

    private void change() {
        String oldPwd = String.valueOf(binding.etOld.getText());
        String newPwd = String.valueOf(binding.etNew.getText());
        String confirm = String.valueOf(binding.etConfirm.getText());
        if (oldPwd.isEmpty() || newPwd.isEmpty()) {
            toast("请填写完整");
            return;
        }
        if (newPwd.length() < 6) {
            toast("新密码至少 6 位");
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

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
