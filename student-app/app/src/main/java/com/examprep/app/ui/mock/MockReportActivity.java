package com.examprep.app.ui.mock;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.examprep.app.data.model.Models;
import com.examprep.app.databinding.ActivityReportBinding;
import com.examprep.app.ui.practice.ReportAdapter;

import java.util.ArrayList;
import java.util.List;

/** 模拟考试成绩报告 */
public class MockReportActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityReportBinding binding = ActivityReportBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        Models.MockReport report = (Models.MockReport) getIntent().getSerializableExtra("report");
        if (report != null) {
            binding.tvScore.setText(String.format("%.1f 分 / %d 分（%.1f%%）",
                    report.gotScore, report.totalScore, report.scoreRate));
            binding.tvCorrect.setText(String.valueOf(report.correctCount));
            binding.tvTotal.setText(String.valueOf(report.totalCount));
            binding.tvDuration.setText("AUTO");
            List<Models.ReportItem> items = new ArrayList<>();
            if (report.details != null) {
                for (Models.MockDetail d : report.details) {
                    Models.ReportItem item = new Models.ReportItem();
                    item.typeName = d.typeName;
                    item.stem = d.stem;
                    item.userAnswer = d.userAnswer;
                    item.correct = d.correct;
                    item.correctAnswer = d.correctAnswer;
                    item.scoreGot = d.scoreGot;
                    items.add(item);
                }
            }
            binding.rvDetails.setLayoutManager(new LinearLayoutManager(this));
            binding.rvDetails.setAdapter(new ReportAdapter(items));
        }
        binding.btnBack.setOnClickListener(v -> finish());
    }
}
