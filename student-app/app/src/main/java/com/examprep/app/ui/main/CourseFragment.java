package com.examprep.app.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.examprep.app.R;
import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.FragmentCourseBinding;
import com.examprep.app.ui.common.TwoLineAdapter;
import com.examprep.app.ui.resource.ResourceDetailActivity;
import com.examprep.app.ui.upload.UploadActivity;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 课程库 tab（大纲 7.5）：课程视图（学院/学期筛选）+ 资料视图（搜索/标签/分页，上拉加载）。
 * 课程按「学院/学年/学期」找课；资料核心动作是浏览与下载。
 */
public class CourseFragment extends Fragment {

    private FragmentCourseBinding binding;
    private ApiService api;

    // 课程
    private final List<Models.College> colleges = new ArrayList<>();
    private final List<Models.Semester> semesters = new ArrayList<>();
    private Long collegeId;
    private Long semesterId;
    private boolean collegeLoaded;
    private boolean semesterLoaded;

    // 资料
    private TwoLineAdapter resourceAdapter;
    private final List<String[]> resourceRows = new ArrayList<>();
    private long resPage = 1;
    private final long resSize = 20;
    private long resTotal;
    private String keyword;
    private String tags;
    private boolean resLoading;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentCourseBinding.inflate(inflater, container, false);
        api = ApiClient.api();

        // 视图切换
        binding.toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            boolean course = checkedId == R.id.btnTabCourse;
            binding.llCourseView.setVisibility(course ? View.VISIBLE : View.GONE);
            binding.llResourceView.setVisibility(course ? View.GONE : View.VISIBLE);
        });

        initCourseView();
        initResourceView();
        loadColleges();
        loadSemesters();
        loadResources();
        return binding.getRoot();
    }

    // ==================== 课程视图 ====================

    private void initCourseView() {
        binding.rvCourses.setLayoutManager(new LinearLayoutManager(getContext()));

        binding.spCollege.setOnItemSelectedListener(new SimpleSelect() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                collegeId = position == 0 ? null : colleges.get(position - 1).id;
                if (collegeLoaded && semesterLoaded) {
                    loadCourses();
                }
            }
        });
        binding.spSemester.setOnItemSelectedListener(new SimpleSelect() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                semesterId = position == 0 ? null : semesters.get(position - 1).id;
                if (collegeLoaded && semesterLoaded) {
                    loadCourses();
                }
            }
        });
    }

    private void loadColleges() {
        api.colleges().enqueue(new Callback<Models.ApiResult<List<Models.College>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<Models.College>>> call,
                                   Response<Models.ApiResult<List<Models.College>>> resp) {
                Models.ApiResult<List<Models.College>> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    return;
                }
                colleges.clear();
                colleges.addAll(r.data);
                List<String> names = new ArrayList<>();
                names.add("全部学院");
                for (Models.College c : colleges) {
                    names.add(c.name);
                }
                binding.spCollege.setAdapter(new ArrayAdapter<>(getContext(),
                        android.R.layout.simple_spinner_dropdown_item, names));
                collegeLoaded = true;
                maybeLoadCourses();
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.College>>> call, Throwable t) {
                toast("学院加载失败");
            }
        });
    }

    private void loadSemesters() {
        api.semesters().enqueue(new Callback<Models.ApiResult<List<Models.Semester>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<Models.Semester>>> call,
                                   Response<Models.ApiResult<List<Models.Semester>>> resp) {
                Models.ApiResult<List<Models.Semester>> r = resp.body();
                if (r == null || !r.ok() || r.data == null) {
                    return;
                }
                semesters.clear();
                semesters.addAll(r.data);
                List<String> names = new ArrayList<>();
                names.add("全部学期");
                for (Models.Semester s : semesters) {
                    names.add(s.name);
                }
                binding.spSemester.setAdapter(new ArrayAdapter<>(getContext(),
                        android.R.layout.simple_spinner_dropdown_item, names));
                semesterLoaded = true;
                maybeLoadCourses();
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.Semester>>> call, Throwable t) {
                toast("学期加载失败");
            }
        });
    }

    private void maybeLoadCourses() {
        if (collegeLoaded && semesterLoaded) {
            loadCourses();
        }
    }

    private void loadCourses() {
        api.courses(collegeId, semesterId).enqueue(new Callback<Models.ApiResult<List<Models.Course>>>() {
            @Override
            public void onResponse(Call<Models.ApiResult<List<Models.Course>>> call,
                                   Response<Models.ApiResult<List<Models.Course>>> resp) {
                Models.ApiResult<List<Models.Course>> r = resp.body();
                List<Models.Course> list = r != null && r.ok() && r.data != null ? r.data : List.of();
                binding.tvCourseEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);

                List<String[]> rows = new ArrayList<>();
                for (Models.Course c : list) {
                    StringBuilder meta = new StringBuilder();
                    if (c.teacherName != null) {
                        meta.append(c.teacherName).append(" · ");
                    }
                    if (c.semesterName != null) {
                        meta.append(c.semesterName).append(" · ");
                    }
                    meta.append("学分 ").append(c.credit);
                    rows.add(new String[]{c.name, meta.toString()});
                }
                binding.rvCourses.setAdapter(new TwoLineAdapter(rows));
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.Course>>> call, Throwable t) {
                toast("课程加载失败");
            }
        });
    }

    // ==================== 资料视图 ====================

    private void initResourceView() {
        resourceAdapter = new TwoLineAdapter(resourceRows);
        binding.rvResources.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.rvResources.setAdapter(resourceAdapter);
        resourceAdapter.setOnClick(row -> {
            Intent intent = new Intent(getContext(), ResourceDetailActivity.class);
            intent.putExtra("resourceId", Long.parseLong(row[2]));
            startActivity(intent);
        });

        binding.refresh.setOnRefreshListener(() -> {
            resPage = 1;
            loadResources();
        });
        binding.etSearch.setOnEditorActionListener((v, actionId, event) -> {
            keyword = String.valueOf(binding.etSearch.getText()).trim();
            if (keyword.isEmpty()) {
                keyword = null;
            }
            resPage = 1;
            loadResources();
            return true;
        });
        binding.chipTags.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                tags = null;
            } else {
                tags = String.valueOf(((com.google.android.material.chip.Chip) group.findViewById(checkedIds.get(0))).getText());
            }
            resPage = 1;
            loadResources();
        });

        // 上拉加载（大纲 7.6）
        binding.rvResources.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || resLoading) {
                    return;
                }
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) {
                    return;
                }
                int last = lm.findLastCompletelyVisibleItemPosition();
                if (last >= resourceAdapter.getItemCount() - 2
                        && resourceRows.size() < resTotal) {
                    resPage++;
                    loadResources();
                }
            }
        });
    }

    private void loadResources() {
        if (resLoading) {
            return;
        }
        resLoading = true;
        binding.refresh.setRefreshing(resPage == 1);
        api.resources(resPage, resSize, null, tags, keyword)
                .enqueue(new Callback<Models.ApiResult<Models.Page<Models.Resource>>>() {
                    @Override
                    public void onResponse(Call<Models.ApiResult<Models.Page<Models.Resource>>> call,
                                           Response<Models.ApiResult<Models.Page<Models.Resource>>> resp) {
                        resLoading = false;
                        binding.refresh.setRefreshing(false);
                        Models.ApiResult<Models.Page<Models.Resource>> r = resp.body();
                        List<Models.Resource> list = r != null && r.ok() && r.data != null && r.data.list != null
                                ? r.data.list : List.of();
                        resTotal = r != null && r.ok() && r.data != null ? r.data.total : 0;

                        if (resPage == 1) {
                            resourceRows.clear();
                        }
                        for (Models.Resource res : list) {
                            resourceRows.add(new String[]{res.title,
                                    res.typeName + " · " + (res.subjectName == null ? "-" : res.subjectName)
                                            + " · 下载 " + res.downloadCount,
                                    String.valueOf(res.id)});
                        }
                        binding.tvResourceEmpty.setVisibility(resourceRows.isEmpty() ? View.VISIBLE : View.GONE);
                        binding.tvLoadMore.setVisibility(
                                resourceRows.size() < resTotal ? View.VISIBLE : View.GONE);
                        resourceAdapter = new TwoLineAdapter(resourceRows);
                        resourceAdapter.setOnClick(row -> {
                            Intent intent = new Intent(getContext(), ResourceDetailActivity.class);
                            intent.putExtra("resourceId", Long.parseLong(row[2]));
                            startActivity(intent);
                        });
                        binding.rvResources.setAdapter(resourceAdapter);
                    }

                    @Override
                    public void onFailure(Call<Models.ApiResult<Models.Page<Models.Resource>>> call, Throwable t) {
                        resLoading = false;
                        binding.refresh.setRefreshing(false);
                        toast("网络错误：" + t.getMessage());
                    }
                });
    }

    private void toast(String msg) {
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }

    abstract static class SimpleSelect implements AdapterView.OnItemSelectedListener {
        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }
}
