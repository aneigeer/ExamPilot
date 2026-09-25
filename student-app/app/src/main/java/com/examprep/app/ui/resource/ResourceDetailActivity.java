package com.examprep.app.ui.resource;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.data.prefs.TokenStore;
import com.examprep.app.databinding.ActivityResourceDetailBinding;
import com.examprep.app.databinding.ItemCommentBinding;
import com.examprep.app.ui.login.LoginActivity;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 资料详情：信息 + 在线预览（水印图）+ 下载（签名 URL）+ 收藏 + 评论点赞 */
public class ResourceDetailActivity extends AppCompatActivity {

    private ActivityResourceDetailBinding binding;
    private ApiService api;
    private long resourceId;
    private Models.Resource resource;
    private CommentAdapter commentAdapter;
    private PageImgAdapter pageAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityResourceDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();
        resourceId = getIntent().getLongExtra("resourceId", 0);

        commentAdapter = new CommentAdapter();
        binding.rvComments.setLayoutManager(new LinearLayoutManager(this));
        binding.rvComments.setAdapter(commentAdapter);
        pageAdapter = new PageImgAdapter();
        binding.rvPages.setLayoutManager(new LinearLayoutManager(this));
        binding.rvPages.setAdapter(pageAdapter);
        binding.rvPages.setNestedScrollingEnabled(false);

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnPreview.setOnClickListener(v -> loadPreview());
        binding.btnDownload.setOnClickListener(v -> download());
        binding.btnFavorite.setOnClickListener(v -> favorite());
        binding.btnSend.setOnClickListener(v -> addComment());

        loadDetail();
        loadComments();
    }

    private void loadDetail() {
        api.resourceDetail(resourceId).enqueue(new Callback<Models.ApiResult<Models.Resource>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.Resource>> call,
                                   Response<Models.ApiResult<Models.Resource>> resp) {
                Models.ApiResult<Models.Resource> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    toast(r == null ? "加载失败" : r.message);
                    return;
                }
                resource = r.data;
                bind();
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.Resource>> call, Throwable t) {
                toast("网络错误");
            }
        });
    }

    private void bind() {
        binding.tvTitle.setText(resource.title);
        binding.tvMeta.setText(String.format("%s · %s · 上传 %s · 下载 %d · 评分 %.1f",
                resource.typeName, resource.subjectName == null ? "-" : resource.subjectName,
                resource.createdAt == null ? "-" : resource.createdAt,
                resource.downloadCount, resource.ratingAvg));
        binding.tvDesc.setText(resource.description == null || resource.description.isEmpty()
                ? "（无简介）" : resource.description);
        api.favoriteCheck("RESOURCE", resourceId).enqueue(new Callback<Models.ApiResult<ApiService.FavResult>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<ApiService.FavResult>> call,
                                   Response<Models.ApiResult<ApiService.FavResult>> resp) {
                Models.ApiResult<ApiService.FavResult> r = resp.body();
                if (r != null && r.ok() && r.data != null) {
                    binding.btnFavorite.setText(r.data.favorited ? "取消收藏" : "收藏");
                }
            }

            @Override
            public void onFailure(Call<Models.ApiResult<ApiService.FavResult>> call, Throwable t) {
            }
        });
    }

    private void loadPreview() {
        api.resourcePreview(resourceId).enqueue(new Callback<Models.ApiResult<Models.Preview>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.Preview>> call,
                                   Response<Models.ApiResult<Models.Preview>> resp) {
                Models.ApiResult<Models.Preview> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    toast(r == null ? "预览失败" : r.message);
                    return;
                }
                Models.Preview p = r.data;
                pageAdapter.submit(p.pages == null ? List.of() : p.pages);
                binding.tvPreviewTip.setText(String.format("共 %d 页，当前可见 %d 页%s",
                        p.totalPages, p.visiblePages, p.guest ? "（游客仅前 3 页，登录看整本）" : ""));
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.Preview>> call, Throwable t) {
                toast("网络错误");
            }
        });
    }

    private void download() {
        api.resourceDownload(resourceId).enqueue(new Callback<Models.ApiResult<Models.DownloadInfo>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.DownloadInfo>> call,
                                   Response<Models.ApiResult<Models.DownloadInfo>> resp) {
                Models.ApiResult<Models.DownloadInfo> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    toast(r == null ? "获取下载地址失败" : r.message);
                    return;
                }
                String url = com.examprep.app.BuildConfig.BASE_URL.replace("/api/", "")
                        + r.data.url.replaceFirst("^/", "/");
                toast("已获取签名链接（10 分钟有效），浏览器下载中");
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.DownloadInfo>> call, Throwable t) {
                toast("网络错误");
            }
        });
    }

    private void favorite() {
        if (!TokenStore.get(this).hasToken()) {
            goLogin();
            return;
        }
        api.favoriteToggle("RESOURCE", resourceId).enqueue(new Callback<Models.ApiResult<ApiService.FavResult>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<ApiService.FavResult>> call,
                                   Response<Models.ApiResult<ApiService.FavResult>> resp) {
                Models.ApiResult<ApiService.FavResult> r = resp.body();
                if (r != null && r.ok() && r.data != null) {
                    binding.btnFavorite.setText(r.data.favorited ? "取消收藏" : "收藏");
                    toast(r.data.favorited ? "已收藏" : "已取消收藏");
                }
            }

            @Override
            public void onFailure(Call<Models.ApiResult<ApiService.FavResult>> call, Throwable t) {
                toast("网络错误");
            }
        });
    }

    private void loadComments() {
        api.comments("RESOURCE", resourceId).enqueue(new Callback<Models.ApiResult<List<Models.Comment>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<Models.Comment>>> call,
                                   Response<Models.ApiResult<List<Models.Comment>>> resp) {
                Models.ApiResult<List<Models.Comment>> r = resp.body();
                commentAdapter.submit(r != null && r.ok() && r.data != null ? r.data : List.of());
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.Comment>>> call, Throwable t) {
            }
        });
    }

    private void addComment() {
        String content = String.valueOf(binding.etComment.getText()).trim();
        if (content.isEmpty()) {
            toast("先输入评论内容");
            return;
        }
        api.commentAdd("RESOURCE", resourceId, content).enqueue(new Callback<Models.ApiResult<Models.Comment>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.Comment>> call,
                                   Response<Models.ApiResult<Models.Comment>> resp) {
                Models.ApiResult<Models.Comment> r = resp.body();
                if (r != null && r.ok()) {
                    binding.etComment.setText("");
                    loadComments();
                } else {
                    toast(r == null ? "发表失败" : r.message);
                }
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.Comment>> call, Throwable t) {
                toast("网络错误");
            }
        });
    }

    private void goLogin() {
        startActivity(new Intent(this, LoginActivity.class));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 预览页图片 */
    private static class PageImgAdapter extends RecyclerView.Adapter<PageImgAdapter.VH> {
        private final List<Models.PageImg> data = new ArrayList<>();

        void submit(List<Models.PageImg> list) {
            data.clear();
            data.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            android.widget.ImageView iv = new android.widget.ImageView(parent.getContext());
            iv.setAdjustViewBounds(true);
            iv.setPadding(0, 8, 0, 8);
            return new VH(iv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Models.PageImg p = data.get(position);
            String url = com.examprep.app.BuildConfig.BASE_URL.replace("/api/", "") + p.url.replaceFirst("^/", "/");
            Glide.with(h.iv).load(url).into(h.iv);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final android.widget.ImageView iv;

            VH(android.widget.ImageView iv) {
                super(iv);
                this.iv = iv;
            }
        }
    }

    /** 评论列表 */
    private class CommentAdapter extends RecyclerView.Adapter<CommentAdapter.VH> {
        private final List<Models.Comment> data = new ArrayList<>();

        void submit(List<Models.Comment> list) {
            data.clear();
            data.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemCommentBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Models.Comment c = data.get(position);
            h.binding.tvNick.setText(c.nickname == null ? "用户" : c.nickname);
            h.binding.tvContent.setText(c.content);
            h.binding.tvLike.setText(String.format("👍 %d", c.likeCount));
            h.binding.tvLike.setOnClickListener(v -> {
                if (!TokenStore.get(ResourceDetailActivity.this).hasToken()) {
                    goLogin();
                    return;
                }
                api.commentLike(c.id).enqueue(new Callback<Models.ApiResult<ApiService.LikeResult>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<ApiService.LikeResult>> call,
                                           Response<Models.ApiResult<ApiService.LikeResult>> resp) {
                        loadComments();
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<ApiService.LikeResult>> call, Throwable t) {
                    }
                });
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ItemCommentBinding binding;

            VH(ItemCommentBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
