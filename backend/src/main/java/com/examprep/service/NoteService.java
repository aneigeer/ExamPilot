package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BizException;
import com.examprep.common.QuestionType;
import com.examprep.common.ResultCode;
import com.examprep.dto.QuestionNoteVO;
import com.examprep.entity.Question;
import com.examprep.entity.QuestionNote;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.QuestionNoteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 题目笔记（P0-B）：一人一题一篇，保存时 upsert
 */
@Service
@RequiredArgsConstructor
public class NoteService {

    private final QuestionNoteMapper questionNoteMapper;
    private final QuestionMapper questionMapper;

    /** 我的笔记列表（可按题目过滤） */
    public List<QuestionNoteVO> listMine(Long userId, Long questionId) {
        List<QuestionNote> notes = questionNoteMapper.selectList(Wrappers.<QuestionNote>lambdaQuery()
                .eq(QuestionNote::getUserId, userId)
                .eq(questionId != null, QuestionNote::getQuestionId, questionId)
                .orderByDesc(QuestionNote::getUpdatedAt));
        if (notes.isEmpty()) {
            return List.of();
        }
        Map<Long, Question> questionMap = questionMapper.selectBatchIds(
                        notes.stream().map(QuestionNote::getQuestionId).toList()).stream()
                .collect(Collectors.toMap(Question::getId, Function.identity()));
        return notes.stream()
                .map(n -> toVO(n, questionMap.get(n.getQuestionId())))
                .toList();
    }

    /** 取某题的笔记（没有返回 null） */
    public QuestionNoteVO getByQuestion(Long userId, Long questionId) {
        QuestionNote note = questionNoteMapper.selectOne(Wrappers.<QuestionNote>lambdaQuery()
                .eq(QuestionNote::getUserId, userId)
                .eq(QuestionNote::getQuestionId, questionId));
        return note == null ? null : toVO(note, questionMapper.selectById(questionId));
    }

    /** 保存笔记（已有则覆盖） */
    @Transactional(rollbackFor = Exception.class)
    public QuestionNoteVO save(Long userId, Long questionId, String content) {
        if (!StringUtils.hasText(content)) {
            throw new BizException("笔记内容不能为空");
        }
        if (content.length() > 2000) {
            throw new BizException("笔记内容不能超过 2000 字");
        }
        if (questionMapper.selectById(questionId) == null) {
            throw new BizException(ResultCode.QUESTION_NOT_FOUND);
        }

        QuestionNote note = questionNoteMapper.selectOne(Wrappers.<QuestionNote>lambdaQuery()
                .eq(QuestionNote::getUserId, userId)
                .eq(QuestionNote::getQuestionId, questionId));
        if (note == null) {
            note = new QuestionNote();
            note.setUserId(userId);
            note.setQuestionId(questionId);
            note.setContent(content.trim());
            questionNoteMapper.insert(note);
        } else {
            note.setContent(content.trim());
            questionNoteMapper.updateById(note);
        }
        return toVO(note, questionMapper.selectById(questionId));
    }

    /** 删除笔记（只能删自己的） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long noteId) {
        QuestionNote note = questionNoteMapper.selectById(noteId);
        if (note == null) {
            throw new BizException(ResultCode.NOT_FOUND, "笔记不存在");
        }
        if (!note.getUserId().equals(userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "只能删除自己的笔记");
        }
        questionNoteMapper.deleteById(noteId);
    }

    private QuestionNoteVO toVO(QuestionNote note, Question question) {
        QuestionNoteVO vo = new QuestionNoteVO();
        vo.setId(note.getId());
        vo.setQuestionId(note.getQuestionId());
        vo.setContent(note.getContent());
        vo.setCreatedAt(note.getCreatedAt());
        vo.setUpdatedAt(note.getUpdatedAt());
        if (question != null) {
            vo.setTypeName(QuestionType.labelOf(question.getType()));
            String stem = question.getStem();
            vo.setStem(stem == null ? null : (stem.length() <= 50 ? stem : stem.substring(0, 50) + "..."));
        }
        return vo;
    }
}
