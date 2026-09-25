package com.examprep.app.ui.main;

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

import com.examprep.app.data.api.ApiClient;
import com.examprep.app.data.api.ApiService;
import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.FragmentCourseBinding;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 课程库：学院 + 学期筛选 → 课程列表 */
public class CourseFragment extends Fragment {

    private FragmentCourseBinding binding;
    private ApiService api;

    private final List<Models.College> colleges = new ArrayList<>();
    private final List<Models.Semester> semesters = new ArrayList<>();
    private Long collegeId;
    private Long semesterId;
    private boolean collegeLoaded;
    private boolean semesterLoaded;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentCourseBinding.inflate(inflater, container, false);
        api = ApiClient.api();
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

        loadColleges();
        loadSemesters();
        return binding.getRoot();
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
                ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
                        android.R.layout.simple_spinner_dropdown_item, names);
                binding.spCollege.setAdapter(adapter);
                collegeLoaded = true;
                maybeLoad();
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
                ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
                        android.R.layout.simple_spinner_dropdown_item, names);
                binding.spSemester.setAdapter(adapter);
                semesterLoaded = true;
                maybeLoad();
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.Semester>>> call, Throwable t) {
                toast("学期加载失败");
            }
        });
    }

    private void maybeLoad() {
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
                binding.rvCourses.setAdapter(new CourseAdapter(list));
            }

            @Override
            public void onFailure(Call<Models.ApiResult<List<Models.Course>>> call, Throwable t) {
                toast("课程加载失败");
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

    private static class CourseAdapter extends RecyclerView.Adapter<CourseAdapter.VH> {
        private final List<Models.Course> data;

        CourseAdapter(List<Models.Course> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            com.examprep.app.databinding.ItemCourseBinding binding =
                    com.examprep.app.databinding.ItemCourseBinding.inflate(
                            LayoutInflater.from(parent.getContext()), parent, false);
            return new VH(binding);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Models.Course c = data.get(position);
            h.binding.tvName.setText(c.name);
            StringBuilder meta = new StringBuilder();
            if (c.teacherName != null) {
                meta.append(c.teacherName).append(" · ");
            }
            if (c.semesterName != null) {
                meta.append(c.semesterName).append(" · ");
            }
            meta.append("学分 ").append(c.credit);
            h.binding.tvCourseMeta.setText(meta.toString());
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final com.examprep.app.databinding.ItemCourseBinding binding;

            VH(com.examprep.app.databinding.ItemCourseBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
