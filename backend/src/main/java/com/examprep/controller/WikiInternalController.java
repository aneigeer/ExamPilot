package com.examprep.controller;

import com.examprep.common.BizException;
import com.examprep.common.Result;
import com.examprep.common.ResultCode;
import com.examprep.dto.wiki.WikiHitVO;
import com.examprep.dto.wiki.WikiRevisionRequest;
import com.examprep.service.wiki.WikiQueryService;
import com.examprep.service.wiki.WikiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wiki 内部接口（Java ↔ Python，走独立安全链 + X-Internal-Token）
 *
 * <p>三个用途：
 * <ol>
 *   <li>蒸馏输入导出（Python 不去直连 MySQL）；</li>
 *   <li>蒸馏草稿回传（LLM 结果只进修订表，等人工审核）；</li>
 *   <li>Agent 的 {@code search_wiki} / {@code get_wiki_page} 工具。</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class WikiInternalController {

    private final WikiService wikiService;
    private final WikiQueryService wikiQueryService;

    /** 蒸馏输入：按知识点聚类后的原始块 + 已发布页面目录 */
    @GetMapping("/wiki/input")
    public Result<Map<String, Object>> input(
            @RequestParam(defaultValue = "ALL") String scope,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(name = "maxChunks", required = false) Integer maxChunks) {
        return Result.success(wikiService.distillInput(scope, subjectId, limit, maxChunks));
    }

    /** 蒸馏草稿回传（PENDING，人工审核后才发布） */
    @PostMapping("/wiki/revisions")
    public Result<Map<String, Object>> revision(@RequestBody WikiRevisionRequest request) {
        return Result.success(wikiService.createRevision(request));
    }

    // ==================================================================
    // Agent 工具
    // ==================================================================

    @PostMapping("/agent/tools/wiki-search")
    public Result<Map<String, Object>> search(@RequestBody Map<String, Object> body) {
        String question = body.get("question") == null ? "" : String.valueOf(body.get("question")).trim();
        if (!StringUtils.hasText(question)) {
            throw new BizException(ResultCode.PARAM_ERROR, "question 不能为空");
        }
        Long subjectId = longOrNull(body.get("subjectId"));
        int topK = clamp(intOr(body.get("topK"), 3), 1, 10);
        List<WikiHitVO> hits = new ArrayList<>();
        for (WikiQueryService.WikiHit hit : wikiQueryService.search(question, subjectId, topK)) {
            hits.add(wikiQueryService.toVO(hit));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("engine", "WIKI");
        data.put("hits", hits);
        return Result.success(data);
    }

    @GetMapping("/agent/tools/wiki-page/{id}")
    public Result<Object> page(@PathVariable Long id) {
        return Result.success(wikiService.detail(id));
    }

    private static Long longOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOr(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
