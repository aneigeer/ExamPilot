package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.QuestionNoteVO;
import com.examprep.security.SecurityUtils;
import com.examprep.service.NoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 题目笔记（P0-B）
 */
@Tag(name = "17. 题目笔记", description = "刷题时对单题做笔记，一人一题一篇")
@RestController
@RequestMapping("/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;

    @Operation(summary = "我的笔记列表", description = "**访问级别：AUTH**。可按题目过滤，按更新时间倒序。")
    @GetMapping
    public Result<List<QuestionNoteVO>> listMine(
            @Parameter(description = "题目 ID") @RequestParam(required = false) Long questionId) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(noteService.listMine(userId, questionId));
    }

    @Operation(summary = "取某题的笔记", description = "**访问级别：AUTH**。没写过返回 null。题目详情页进入时查询用。")
    @GetMapping("/question/{questionId}")
    public Result<QuestionNoteVO> getByQuestion(@PathVariable Long questionId) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(noteService.getByQuestion(userId, questionId));
    }

    @Operation(summary = "保存笔记",
            description = "**访问级别：AUTH**。**upsert 语义**：该题已有笔记则覆盖，没有则新建。")
    @PutMapping
    public Result<QuestionNoteVO> save(
            @Parameter(description = "题目 ID") @RequestParam Long questionId,
            @Parameter(description = "笔记内容，最多 2000 字") @RequestParam String content) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success("已保存", noteService.save(userId, questionId, content));
    }

    @Operation(summary = "删除笔记", description = "**访问级别：AUTH**。只能删除自己的笔记。")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = SecurityUtils.requireUserId();
        noteService.delete(userId, id);
        return Result.success("已删除", null);
    }
}
