package com.examprep.app.ui.upload;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityUploadBinding;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 上传资料（App 端仅图片，大纲 0.3 / 7.6）：
 * 相册多选 → Bitmap 采样压缩（长边 ≤1280、JPEG 80）→ 逐张上传 → 每张生成一份图片资料提交审核。
 * 文档类文件按大纲走管理端 Web 上传。
 */
public class UploadActivity extends AppCompatActivity {

    private static final int MAX_DIM = 1280;

    private ActivityUploadBinding binding;
    private ApiService api;

    private final List<Uri> picked = new ArrayList<>();
    private final List<Models.Subject> subjects = new ArrayList<>();
    private final List<ApiService.ResourceType> types = new ArrayList<>();

    private final ActivityResultLauncher<String> picker =
            registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), uris -> {
                picked.clear();
                if (uris != null) {
                    picked.addAll(uris);
                }
                binding.tvPicked.setText(picked.isEmpty() ? "未选择图片"
                        : "已选 " + picked.size() + " 张图片");
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityUploadBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnPick.setOnClickListener(v -> picker.launch("image/*"));
        loadSubjects();
        loadTypes();
        binding.btnSubmit.setOnClickListener(v -> submit());
    }

    private void loadSubjects() {
        api.subjects(null).enqueue(new Callback<Models.ApiResult<List<Models.Subject>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<Models.Subject>>> call,
                                   Response<Models.ApiResult<List<Models.Subject>>> resp) {
                Models.ApiResult<List<Models.Subject>> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    return;
                }
                subjects.addAll(r.data);
                List<String> names = new ArrayList<>();
                for (Models.Subject s : subjects) {
                    names.add(s.name);
                }
                binding.spSubject.setAdapter(new ArrayAdapter<>(UploadActivity.this,
                        android.R.layout.simple_spinner_dropdown_item, names));
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.Subject>>> call, Throwable t) {
            }
        });
    }

    private void loadTypes() {
        api.resourceTypes().enqueue(new Callback<Models.ApiResult<List<ApiService.ResourceType>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<ApiService.ResourceType>>> call,
                                   Response<Models.ApiResult<List<ApiService.ResourceType>>> resp) {
                Models.ApiResult<List<ApiService.ResourceType>> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    return;
                }
                types.addAll(r.data);
                List<String> labels = new ArrayList<>();
                for (ApiService.ResourceType t : types) {
                    labels.add(t.label);
                }
                binding.spType.setAdapter(new ArrayAdapter<>(UploadActivity.this,
                        android.R.layout.simple_spinner_dropdown_item, labels));
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<ApiService.ResourceType>>> call, Throwable t) {
            }
        });
    }

    private void submit() {
        String title = String.valueOf(binding.etTitle.getText()).trim();
        if (title.isEmpty()) {
            toast("请填写资料标题");
            return;
        }
        if (picked.isEmpty()) {
            toast("请先选择图片");
            return;
        }
        if (subjects.isEmpty() || types.isEmpty()) {
            toast("数据还没加载好");
            return;
        }
        binding.btnSubmit.setEnabled(false);
        toast("开始上传 " + picked.size() + " 张图片…");
        uploadOne(title, 0);
    }

    /** 递归：压缩并上传第 i 张 → 全部完成后逐张提交资料 */
    private void uploadOne(String title, final int i) {
        if (i >= picked.size()) {
            toast("全部上传完成，等待审核");
            finish();
            return;
        }
        try {
            File compressed = compress(picked.get(i), i);
            RequestBody requestFile = RequestBody.create(compressed, MediaType.parse("image/jpeg"));
            MultipartBody.Part part = MultipartBody.Part.createFormData("file",
                    compressed.getName(), requestFile);

            api.uploadImage(part).enqueue(new Callback<Models.ApiResult<ApiService.FileUploadData>>() {
                @Override
                public void onResponse(Call<Models.ApiResult<ApiService.FileUploadData>> call,
                                       Response<Models.ApiResult<ApiService.FileUploadData>> resp) {
                    Models.ApiResult<ApiService.FileUploadData> r = resp.body();
                    if (r == null || !r.ok() || r.data == null) {
                        binding.btnSubmit.setEnabled(true);
                        toast("第 " + (i + 1) + " 张上传失败：" + (r == null ? "" : r.message));
                        return;
                    }
                    // 每张图独立成一份图片资料（后端资料-文件为一对一）
                    ApiService.ResourceSubmitBody body = new ApiService.ResourceSubmitBody();
                    body.title = picked.size() > 1 ? title + "（" + (i + 1) + "/" + picked.size() + "）" : title;
                    body.examCategoryId = 1L;
                    body.subjectId = subjects.get(binding.spSubject.getSelectedItemPosition()).id;
                    body.type = types.get(binding.spType.getSelectedItemPosition()).type;
                    body.description = String.valueOf(binding.etDesc.getText()).trim();
                    body.tags = String.valueOf(binding.etTags.getText()).trim();
                    body.fileId = r.data.fileId;

                    api.createResource(body).enqueue(new Callback<Models.ApiResult<Models.Resource>>() {
                        @Override
                        public void onResponse(Call<Models.ApiResult<Models.Resource>> call,
                                               Response<Models.ApiResult<Models.Resource>> resp2) {
                            uploadOne(title, i + 1);
                        }

                        @Override
                        public void onFailure(Call<Models.ApiResult<Models.Resource>> call, Throwable t) {
                            binding.btnSubmit.setEnabled(true);
                            toast("资料提交失败：" + t.getMessage());
                        }
                    });
                }

                @Override
                public void onFailure(Call<Models.ApiResult<ApiService.FileUploadData>> call, Throwable t) {
                    binding.btnSubmit.setEnabled(true);
                    toast("图片上传失败：" + t.getMessage());
                }
            });
        } catch (Exception e) {
            binding.btnSubmit.setEnabled(true);
            toast("读取图片失败：" + e.getMessage());
        }
    }

    /** Bitmap 采样压缩：长边 ≤1280px，JPEG 质量 80（大纲 7.6） */
    private File compress(Uri uri, int index) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while (bounds.outWidth / sample > MAX_DIM || bounds.outHeight / sample > MAX_DIM) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, opts);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bos);
        File file = new File(getCacheDir(), "upload_" + System.currentTimeMillis() + "_" + index + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bos.toByteArray());
        }
        return file;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
