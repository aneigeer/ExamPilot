package com.examprep.service.datahub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.entity.DataQualityIssue;
import com.examprep.mapper.DataHubMapper;
import com.examprep.mapper.DataQualityIssueMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 数据质量巡检
 *
 * <p>6 类检查（与 {@code data_quality_issue.issue_type} 一一对应）：
 * <ol>
 *   <li>{@code DUPLICATE_STEM} 同科目 + 同类别下题干完全重复</li>
 *   <li>{@code MISSING_ANSWER} APPROVED 客观题缺答案 / 缺选项</li>
 *   <li>{@code NO_CHUNK} APPROVED 存活题没有 ACTIVE 向量块</li>
 *   <li>{@code ORPHAN_VECTOR} ACTIVE 向量块指向已删除 / 不存在的来源</li>
 *   <li>{@code OCR_BACKLOG} question_source 中 OCR_QUEUED / FAILED 积压</li>
 *   <li>{@code EMPTY_SUBJECT} 启用但没有任何存活题目的科目</li>
 * </ol>
 *
 * <p><b>幂等语义</b>：每类问题都有确定性 {@code issue_key}。
 * 重复巡检只更新已有行；本次未再出现的历史 OPEN 项自动置 RESOLVED。
 * 因此「连续点两次巡检」不会让问题数翻倍。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQualityService {

    /** 巡检覆盖的问题类型（自动销号只作用于这些类型） */
    public static final List<String> ISSUE_TYPES = List.of(
            "DUPLICATE_STEM", "MISSING_ANSWER", "NO_CHUNK",
            "ORPHAN_VECTOR", "OCR_BACKLOG", "EMPTY_SUBJECT");

    private static final int SCAN_LIMIT = 2000;

    private final DataQualityIssueMapper issueMapper;
    private final DataHubMapper dataHubMapper;

    /**
     * 执行一次全量巡检。
     *
     * <p>同一事务内完成「新增 / 更新 / 销号」，避免中途失败留下半套结论。
     */
    @Transactional(rollbackFor = Exception.class)
    public ScanResult scan() {
        List<Draft> drafts = new ArrayList<>();
        drafts.addAll(duplicateStemDrafts());
        drafts.addAll(missingAnswerDrafts());
        drafts.addAll(noChunkDrafts());
        drafts.addAll(orphanVectorDrafts());
        drafts.addAll(ocrBacklogDrafts());
        drafts.addAll(emptySubjectDrafts());

        Set<String> activeKeys = drafts.stream().map(Draft::key).collect(Collectors.toCollection(LinkedHashSet::new));
        List<DataQualityIssue> existing = issueMapper.selectList(Wrappers.<DataQualityIssue>lambdaQuery()
                .in(DataQualityIssue::getIssueType, ISSUE_TYPES));
        Map<String, DataQualityIssue> byKey = existing.stream()
                .filter(i -> i.getIssueKey() != null)
                .collect(Collectors.toMap(DataQualityIssue::getIssueKey, Function.identity(), (a, b) -> a));

        LocalDateTime now = LocalDateTime.now();
        int created = 0;
        int updated = 0;
        int reopened = 0;
        for (Draft draft : drafts) {
            DataQualityIssue row = byKey.get(draft.key());
            if (row == null) {
                issueMapper.insert(toEntity(draft, now));
                created++;
                continue;
            }
            boolean changed = false;
            if (!"OPEN".equals(row.getStatus())) {
                row.setStatus("OPEN");
                row.setResolvedAt(null);
                row.setDetectedAt(now);
                reopened++;
                changed = true;
            }
            if (!draft.detail().equals(row.getDetail()) || !draft.severity().equals(row.getSeverity())) {
                row.setDetail(draft.detail());
                row.setSeverity(draft.severity());
                changed = true;
            }
            if (changed) {
                issueMapper.updateById(row);
                updated++;
            }
        }

        // 自动销号：曾经 OPEN、本次未再命中的问题视为已修复
        int resolved = 0;
        for (DataQualityIssue row : existing) {
            if ("OPEN".equals(row.getStatus()) && !activeKeys.contains(row.getIssueKey())) {
                row.setStatus("RESOLVED");
                row.setResolvedAt(now);
                issueMapper.updateById(row);
                resolved++;
            }
        }

        Map<String, Integer> byType = new LinkedHashMap<>();
        for (String type : ISSUE_TYPES) {
            byType.put(type, 0);
        }
        for (Draft draft : drafts) {
            byType.merge(draft.type(), 1, Integer::sum);
        }

        log.info("数据质量巡检完成：活跃 {} 条（新增 {} / 更新 {} / 重新打开 {} / 销号 {}）",
                drafts.size(), created, updated, reopened, resolved);
        return new ScanResult(drafts.size(), created, updated, reopened, resolved, byType);
    }

    // ==================================================================
    // 各类检查 → Draft
    // ==================================================================

    private List<Draft> duplicateStemDrafts() {
        return dataHubMapper.duplicateStemQuestions(SCAN_LIMIT).stream().map(row -> {
            long questionId = num(row, "questionId");
            long keeperId = num(row, "keeperId");
            int dupCount = (int) num(row, "dupCount");
            String stem = text(row, "stemSample");
            return new Draft("DUPLICATE_STEM:question:" + questionId, "DUPLICATE_STEM", "WARN",
                    "question", questionId,
                    "题干与 #" + keeperId + " 完全重复（该组共 " + dupCount + " 题）：" + abbreviate(stem, 80));
        }).toList();
    }

    private List<Draft> missingAnswerDrafts() {
        return dataHubMapper.missingAnswerQuestions(SCAN_LIMIT).stream().map(row -> {
            long questionId = num(row, "questionId");
            String type = text(row, "type");
            return new Draft("MISSING_ANSWER:question:" + questionId, "MISSING_ANSWER", "CRITICAL",
                    "question", questionId,
                    "APPROVED " + type + " 缺少答案或选项，学生作答后无法判分");
        }).toList();
    }

    private List<Draft> noChunkDrafts() {
        return dataHubMapper.noChunkQuestions(SCAN_LIMIT).stream().map(row -> {
            long questionId = num(row, "questionId");
            return new Draft("NO_CHUNK:question:" + questionId, "NO_CHUNK", "WARN",
                    "question", questionId,
                    "APPROVED 存活题未建立 ACTIVE 向量块，AI 检索将召回不到该题");
        }).toList();
    }

    private List<Draft> orphanVectorDrafts() {
        return dataHubMapper.orphanVectorStats().stream().map(row -> {
            String sourceType = text(row, "sourceType");
            long count = num(row, "chunkCount");
            long sample = num(row, "sampleId");
            return new Draft("ORPHAN_VECTOR:ai_chunk:" + sourceType, "ORPHAN_VECTOR", "CRITICAL",
                    "ai_chunk", 0,
                    sourceType + " 类孤儿向量块 " + count + " 条，来源已删除或不存在（示例 source_id=" + sample + "）");
        }).toList();
    }

    private List<Draft> ocrBacklogDrafts() {
        return dataHubMapper.ocrBacklogStats().stream().map(row -> {
            String status = text(row, "parseStatus");
            long count = num(row, "sourceCount");
            String severity = "FAILED".equals(status) ? "CRITICAL" : "WARN";
            return new Draft("OCR_BACKLOG:question_source:" + status, "OCR_BACKLOG", severity,
                    "question_source", 0,
                    status + " 积压 " + count + " 个来源文件，需人工补录或重跑 OCR");
        }).toList();
    }

    private List<Draft> emptySubjectDrafts() {
        return dataHubMapper.emptySubjects(SCAN_LIMIT).stream().map(row -> {
            long subjectId = num(row, "subjectId");
            String name = text(row, "subjectName");
            String examCode = text(row, "examCode");
            return new Draft("EMPTY_SUBJECT:subject:" + subjectId, "EMPTY_SUBJECT", "WARN",
                    "subject", subjectId,
                    "科目「" + name + "」（" + (examCode.isBlank() ? "未归类" : examCode) + "）没有任何存活题目");
        }).toList();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private DataQualityIssue toEntity(Draft draft, LocalDateTime now) {
        DataQualityIssue issue = new DataQualityIssue();
        issue.setIssueKey(draft.key());
        issue.setIssueType(draft.type());
        issue.setSeverity(draft.severity());
        issue.setTargetTable(draft.targetTable());
        issue.setTargetId(draft.targetId());
        issue.setDetail(abbreviate(draft.detail(), 1000));
        issue.setStatus("OPEN");
        issue.setDetectedAt(now);
        return issue;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static long num(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static String text(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private record Draft(String key, String type, String severity,
                         String targetTable, long targetId, String detail) {
    }

    /**
     * 巡检结果
     *
     * @param active   本次命中并保持 OPEN 的问题数
     * @param created  新增行数
     * @param updated  更新行数（含重新打开）
     * @param reopened 已销号后再次出现、被重新打开的行数
     * @param resolved 本次未再出现、被自动销号的行数
     * @param byType   按问题类型统计的活跃数
     */
    public record ScanResult(int active, int created, int updated, int reopened, int resolved,
                             Map<String, Integer> byType) {
    }
}
