package com.examprep.app.ui.wiki;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.examprep.app.R;
import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityWikiDetailBinding;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Wiki 主题页详情：摘要 + 要点 + 正文 + 关联页导航 */
public class WikiDetailActivity extends AppCompatActivity {

    private ActivityWikiDetailBinding binding;
    private ApiService api;
    private long pageId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityWikiDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = ApiClient.api();
        pageId = getIntent().getLongExtra("pageId", 0);
        binding.btnBack.setOnClickListener(v -> finish());
        load();
    }

    private void load() {
        api.wikiPage(pageId).enqueue(new Callback<Models.ApiResult<Models.WikiPage>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<Models.WikiPage>> call,
                                   Response<Models.ApiResult<Models.WikiPage>> resp) {
                Models.ApiResult<Models.WikiPage> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    toast(r == null ? "加载失败" : r.message);
                    return;
                }
                bind(r.data);
            }

            @Override
            public void onFailure(Call<Models.ApiResult<Models.WikiPage>> call, Throwable t) {
                toast("网络错误");
            }
        });
    }

    private void bind(Models.WikiPage page) {
        binding.tvTitle.setText(page.title == null ? "未命名主题" : page.title);
        binding.tvMeta.setText(String.format("Wiki 主题页 · v%s · 更新 %s",
                page.version == null ? "1" : page.version,
                page.updatedAt == null ? "-" : page.updatedAt));
        binding.tvSummary.setText(page.summary == null ? "" : page.summary);

        List<String> points = page.keyPoints;
        if (points == null || points.isEmpty()) {
            binding.tvKeyPoints.setVisibility(View.GONE);
        } else {
            binding.tvKeyPoints.setVisibility(View.VISIBLE);
            StringBuilder sb = new StringBuilder();
            for (String point : points) {
                sb.append("• ").append(point).append('\n');
            }
            binding.tvKeyPoints.setText(sb.toString().trim());
        }

        binding.tvContent.setText(plain(page.content));

        binding.layoutRelated.removeAllViews();
        if (page.relatedPages == null || page.relatedPages.isEmpty()) {
            binding.tvRelatedTitle.setVisibility(View.GONE);
            return;
        }
        binding.tvRelatedTitle.setVisibility(View.VISIBLE);
        for (Models.WikiPage related : page.relatedPages) {
            if (related == null || related.id == null) {
                continue;
            }
            TextView item = new TextView(this);
            item.setText("↳ " + (related.title == null ? "关联主题" : related.title));
            item.setTextSize(14);
            item.setPadding(0, 10, 0, 10);
            item.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.primary));
            item.setBackgroundResource(android.R.drawable.list_selector_background);
            item.setOnClickListener(v -> startActivity(new Intent(this, WikiDetailActivity.class)
                    .putExtra("pageId", related.id)));
            binding.layoutRelated.addView(item);
        }
    }

    /** 轻量 Markdown 降级渲染：去掉标题井号与强调符号，保留换行与列表。 */
    private static String plain(String content) {
        if (content == null) {
            return "";
        }
        return content
                .replace("### ", "")
                .replace("## ", "")
                .replace("**", "")
                .replace("`", "");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
