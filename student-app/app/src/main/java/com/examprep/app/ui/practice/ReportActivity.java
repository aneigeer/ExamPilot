package com.examprep.app.ui.practice;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityReportBinding;

/** 练习报告：正确率 + 逐题明细 */
public class ReportActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.examprep.app.databinding.ActivityReportBinding binding =
                com.examprep.app.databinding.ActivityReportBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        Models.Report report = (Models.Report) getIntent().getSerializableExtra("report");
        if (report != null) {
            binding.tvScore.setText(String.format("%.1f%%", report.accuracy));
            binding.tvCorrect.setText(String.valueOf(report.correctCount));
            binding.tvTotal.setText(String.valueOf(report.totalCount));
            binding.tvDuration.setText(report.durationSeconds + "s");
            binding.rvDetails.setLayoutManager(new LinearLayoutManager(this));
            binding.rvDetails.setAdapter(new ReportAdapter(report.items));
        }
        binding.btnBack.setOnClickListener(v -> finish());
    }
}
