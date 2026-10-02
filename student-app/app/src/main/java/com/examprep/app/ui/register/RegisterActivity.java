package com.examprep.app.ui.register;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.data.prefs.TokenStore;
import com.examprep.app.databinding.ActivityRegisterBinding;
import com.examprep.app.ui.main.MainActivity;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 注册页：选学校/学院 + 学号/昵称/密码，注册成功自动登录 */
public class RegisterActivity extends AppCompatActivity {

    /** 兜底默认学校：接口拉取失败时也必须保证「中南林业科技大学」可选 */
    private static final String DEFAULT_SCHOOL = "中南林业科技大学";

    private ActivityRegisterBinding binding;
    private ApiService api;

    private List<Models.School> schools = new ArrayList<>();
    private List<Models.College> colleges = new ArrayList<>();
    /** 当前选中学校在 schools 中的 id；默认 1 = 中南林业科技大学 */
    private Long selectedSchoolId = 1L;
    /** 当前选中学院 id（可为空） */
    private Long selectedCollegeId = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRegisterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();

        // 学校下拉：先放兜底默认项，接口成功后刷新
        showSchools(List.of());
        binding.spSchool.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < schools.size()) {
                selectedSchoolId = schools.get(position).id;
                selectedCollegeId = null;
                loadColleges(selectedSchoolId);
            }
        });
        binding.spCollege.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < colleges.size()) {
                selectedCollegeId = colleges.get(position).id;
            }
        });

        binding.btnRegister.setOnClickListener(v -> doRegister());
        binding.btnBackLogin.setOnClickListener(v -> finish());

        loadSchools();
    }

    // ---------- 数据加载 ----------

    private void loadSchools() {
        api.schools().enqueue(new Callback<Models.ApiResult<List<Models.School>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<Models.School>>> call,
                                   Response<Models.ApiResult<List<Models.School>>> resp) {
                Models.ApiResult<List<Models.School>> r = resp.body();
                if (r != null && r.ok() && r.data != null && !r.data.isEmpty()) {
                    showSchools(r.data);
                }
                // 失败时保留默认兜底项，不阻塞注册
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.School>>> call, Throwable t) {
                // 网络失败：保留默认兜底项
            }
        });
    }

    private void showSchools(List<Models.School> list) {
        List<Models.School> merged = new ArrayList<>(list);
        // 兜底：确保中南林业科技大学始终在列表中且默认选中
        boolean hasDefault = false;
        for (Models.School s : merged) {
            if (DEFAULT_SCHOOL.equals(s.name)) {
                hasDefault = true;
                break;
            }
        }
        if (!hasDefault) {
            Models.School fallback = new Models.School();
            fallback.id = 1L;
            fallback.name = DEFAULT_SCHOOL;
            fallback.code = "CSUFT";
            fallback.sort = 1;
            merged.add(0, fallback);
        }
        schools = merged;

        List<String> names = new ArrayList<>();
        int defaultPos = 0;
        for (int i = 0; i < schools.size(); i++) {
            names.add(schools.get(i).name);
            if (schools.get(i).id != null && schools.get(i).id == 1L) {
                defaultPos = i;
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, names);
        binding.spSchool.setAdapter(adapter);
        binding.spSchool.setText(names.get(defaultPos), false);
        Models.School sel = schools.get(defaultPos);
        selectedSchoolId = sel.id == null ? 1L : sel.id;
        loadColleges(selectedSchoolId);
    }

    private void loadColleges(Long schoolId) {
        api.colleges(schoolId).enqueue(new Callback<Models.ApiResult<List<Models.College>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<Models.College>>> call,
                                   Response<Models.ApiResult<List<Models.College>>> resp) {
                Models.ApiResult<List<Models.College>> r = resp.body();
                List<Models.College> list = (r != null && r.ok() && r.data != null) ? r.data : new ArrayList<>();
                showColleges(list);
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.College>>> call, Throwable t) {
                showColleges(new ArrayList<>());
            }
        });
    }

    private void showColleges(List<Models.College> list) {
        colleges = list;
        selectedCollegeId = null;
        List<String> names = new ArrayList<>();
        names.add("不选择");
        for (Models.College c : colleges) {
            names.add(c.name);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, names);
        binding.spCollege.setAdapter(adapter);
        binding.spCollege.setText(names.get(0), false);
    }

    // ---------- 注册 ----------

    private void doRegister() {
        String no = String.valueOf(binding.etStudentNo.getText()).trim();
        String nick = String.valueOf(binding.etNickname.getText()).trim();
        String pwd = String.valueOf(binding.etPassword.getText());
        String confirm = String.valueOf(binding.etConfirmPassword.getText());
        String grade = String.valueOf(binding.etGrade.getText()).trim();

        if (no.isEmpty() || nick.isEmpty()) {
            toast("请填写学号和昵称");
            return;
        }
        if (pwd.length() < 6) {
            toast("密码至少 6 位");
            return;
        }
        if (!pwd.equals(confirm)) {
            toast("两次输入的密码不一致");
            return;
        }
        if (selectedSchoolId == null) {
            toast("请选择学校");
            return;
        }

        Models.RegisterRequest body = new Models.RegisterRequest();
        body.studentNo = no;
        body.nickname = nick;
        body.password = pwd;
        body.schoolId = selectedSchoolId;
        body.collegeId = selectedCollegeId;
        body.grade = grade.isEmpty() ? null : grade;

        binding.btnRegister.setEnabled(false);
        api.register(body).enqueue(new Callback<Models.ApiResult<Models.LoginData>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.LoginData>> call,
                                   Response<Models.ApiResult<Models.LoginData>> resp) {
                binding.btnRegister.setEnabled(true);
                Models.ApiResult<Models.LoginData> r = resp.body();
                if (r != null && r.ok() && r.data != null && r.data.token != null) {
                    // 与登录成功后的处理完全一致：存 token + 用户信息，进入主页
                    TokenStore.get(RegisterActivity.this).save(r.data.token, r.data.user);
                    toast("注册成功，欢迎，" + (r.data.user == null ? nick : r.data.user.nickname));
                    goMain();
                } else {
                    toast(r == null ? "注册失败" : r.message);
                }
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.LoginData>> call, Throwable t) {
                binding.btnRegister.setEnabled(true);
                toast("网络错误，请确认后端已启动：" + t.getMessage());
            }
        });
    }

    private void goMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
