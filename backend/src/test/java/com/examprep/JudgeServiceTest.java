package com.examprep;

import com.examprep.entity.QuestionAnswer;
import com.examprep.entity.QuestionOption;
import com.examprep.service.JudgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判分服务单元测试（大纲 10.1：判分逻辑 P0 五种题型）。
 * 判分不依赖数据库（组合题除外），可直接构造参数测试。
 */
class JudgeServiceTest {

    private JudgeService judgeService;

    @BeforeEach
    void setUp() {
        // 判分不触库，mapper 传 null 即可
        judgeService = new JudgeService(null, null, null);
    }

    private QuestionOption opt(String key, String content, boolean answer) {
        QuestionOption o = new QuestionOption();
        o.setOptionKey(key);
        o.setContent(content);
        o.setIsAnswer(answer ? 1 : 0);
        o.setSort(0);
        return o;
    }

    private QuestionAnswer answer(Map<String, Object> content) {
        QuestionAnswer a = new QuestionAnswer();
        a.setAnswerContent(content);
        return a;
    }

    @Test
    void 单选_选对() {
        List<QuestionOption> options = List.of(
                opt("A", "malloc", false), opt("B", "free", false),
                opt("C", "printf", true), opt("D", "scanf", false));
        assertTrue(judgeService.judge("SINGLE_CHOICE", options, null, "C").correct());
    }

    @Test
    void 单选_选错() {
        List<QuestionOption> options = List.of(
                opt("A", "malloc", true), opt("B", "free", false));
        assertFalse(judgeService.judge("SINGLE_CHOICE", options, null, "B").correct());
    }

    @Test
    void 多选_乱序仍算对() {
        List<QuestionOption> options = List.of(
                opt("A", "int", true), opt("B", "float", false),
                opt("C", "char", true), opt("D", "goto", false));
        assertTrue(judgeService.judge("MULTI_CHOICE", options, null, "C,A").correct());
    }

    @Test
    void 多选_漏选算错() {
        List<QuestionOption> options = List.of(
                opt("A", "int", true), opt("C", "char", true));
        assertFalse(judgeService.judge("MULTI_CHOICE", options, null, "A").correct());
    }

    @Test
    void 判断_选对() {
        List<QuestionOption> options = List.of(
                opt("A", "对", true), opt("B", "错", false));
        assertTrue(judgeService.judge("TRUE_FALSE", options, null, "A").correct());
    }

    @Test
    void 填空_忽略大小写与多空() {
        QuestionAnswer answer = answer(Map.of(
                "blanks", List.of("printf", "int"), "caseSensitive", false));
        assertTrue(judgeService.judge("FILL_BLANK", List.of(), answer, "PRINTF|int").correct());
    }

    @Test
    void 填空_数量不符算错() {
        QuestionAnswer answer = answer(Map.of(
                "blanks", List.of("printf", "int"), "caseSensitive", false));
        assertFalse(judgeService.judge("FILL_BLANK", List.of(), answer, "printf").correct());
    }

    @Test
    void 匹配_全对() {
        QuestionAnswer answer = answer(Map.of("pairs", Map.of("A", "1", "B", "3", "C", "2")));
        assertTrue(judgeService.judge("MATCHING", List.of(), answer, "A:1,B:3,C:2").correct());
    }

    @Test
    void 匹配_配错() {
        QuestionAnswer answer = answer(Map.of("pairs", Map.of("A", "1", "B", "3", "C", "2")));
        assertFalse(judgeService.judge("MATCHING", List.of(), answer, "A:2,B:1,C:3").correct());
    }

    @Test
    void 排序_全对() {
        QuestionAnswer answer = answer(Map.of("order", List.of("C", "A", "B")));
        assertTrue(judgeService.judge("ORDERING", List.of(), answer, "C,A,B").correct());
    }

    @Test
    void 排序_顺序错() {
        QuestionAnswer answer = answer(Map.of("order", List.of("C", "A", "B")));
        assertFalse(judgeService.judge("ORDERING", List.of(), answer, "A,B,C").correct());
    }

    @Test
    void 主观题_返回空表示走自评() {
        assertTrue(judgeService.judge("SHORT_ANSWER", List.of(), null, "任意作答") == null);
    }
}
