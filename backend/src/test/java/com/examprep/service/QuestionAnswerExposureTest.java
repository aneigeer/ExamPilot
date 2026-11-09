package com.examprep.service;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.dto.QuestionVO;
import com.examprep.entity.ListeningAudio;
import com.examprep.entity.Question;
import com.examprep.entity.QuestionAnswer;
import com.examprep.entity.QuestionOption;
import com.examprep.mapper.ListeningAudioMapper;
import com.examprep.mapper.QuestionAnswerMapper;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.QuestionOptionMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A11 答案暴露面收口。
 *
 * <p>为什么值得测：Agent 只读工具若顺手把答案下发，模型会直接剧透，
 * 练习流程直接失效——而接口本身<b>不报错、HTTP 200</b>，评审很难发现。
 * 本测试锁定三件事：
 * <ol>
 *   <li>{@code includeAnswer=false} 时 {@code isAnswer}、{@code analysis}、
 *       {@code answerContent}、{@code gradingPoints} <b>整键消失</b>（不是 null 占位、不是空串）；</li>
 *   <li>{@code includeAnswer=true} 时四者都在，判分/讲解场景可用；</li>
 *   <li>单参 {@code getQuestionDetail(id)} 行为不变（学生端/题库浏览仍含答案），避免回归。</li>
 * </ol>
 */
@DisplayName("A11：Agent 题目工具默认不给答案")
class QuestionAnswerExposureTest {

    private QuestionMapper questionMapper;
    private QuestionOptionMapper questionOptionMapper;
    private QuestionAnswerMapper questionAnswerMapper;
    private ListeningAudioMapper listeningAudioMapper;
    private QuestionService service;

    /** 与 application.yml:52 的 default-property-inclusion: non_null 对齐 */
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        questionMapper = mock(QuestionMapper.class);
        questionOptionMapper = mock(QuestionOptionMapper.class);
        questionAnswerMapper = mock(QuestionAnswerMapper.class);
        listeningAudioMapper = mock(ListeningAudioMapper.class);
        service = new QuestionService(questionMapper, questionOptionMapper,
                questionAnswerMapper, listeningAudioMapper);

        json = new ObjectMapper();
        json.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private void stubApprovedSingleChoice() {
        Question question = new Question();
        question.setId(1L);
        question.setSubjectId(2L);
        question.setType("SINGLE_CHOICE");
        question.setStem("下列哪个是牛顿第二定律的表达式？");
        question.setAnalysis("F=ma，力等于质量乘以加速度。");
        question.setAuditStatus("APPROVED");
        question.setDifficulty(2);
        question.setScore(5);
        when(questionMapper.selectById(1L)).thenReturn(question);

        QuestionOption optionA = new QuestionOption();
        optionA.setId(11L);
        optionA.setQuestionId(1L);
        optionA.setOptionKey("A");
        optionA.setContent("F=ma");
        optionA.setSort(1);
        optionA.setIsAnswer(1);

        QuestionOption optionB = new QuestionOption();
        optionB.setId(12L);
        optionB.setQuestionId(1L);
        optionB.setOptionKey("B");
        optionB.setContent("F=mv");
        optionB.setSort(2);
        optionB.setIsAnswer(0);

        when(questionOptionMapper.selectList(any())).thenReturn(List.of(optionA, optionB));

        QuestionAnswer answer = new QuestionAnswer();
        answer.setQuestionId(1L);
        answer.setAnswerContent("A");
        answer.setGradingPoints(List.of("写出公式"));
        when(questionAnswerMapper.selectOne(any())).thenReturn(answer);
    }

    @Test
    @DisplayName("includeAnswer=false：选项无 isAnswer、无 analysis，JSON 整键消失")
    void hidesAnswerWhenFlagOff() throws Exception {
        stubApprovedSingleChoice();

        QuestionVO vo = service.getQuestionDetail(1L, false);

        assertEquals(2, vo.getOptions().size());
        assertNull(vo.getOptions().get(0).getIsAnswer(), "选项不得携带正确答案标记");
        assertNull(vo.getOptions().get(1).getIsAnswer());
        assertNull(vo.getAnalysis());
        assertNull(vo.getAnswerContent());
        assertNull(vo.getGradingPoints());

        String body = json.writeValueAsString(vo);
        assertFalse(body.contains("isAnswer"), "字段必须整键消失，而不是置 null：" + body);
        assertFalse(body.contains("analysis"), "字段必须整键消失，而不是置 null：" + body);
        assertFalse(body.contains("answerContent"), "字段必须整键消失：" + body);
        assertFalse(body.contains("gradingPoints"), "字段必须整键消失：" + body);
        // 题面仍要完整，否则模型没法讲题
        assertTrue(body.contains("牛顿第二定律"));
        assertTrue(body.contains("F=ma"));
    }

    @Test
    @DisplayName("includeAnswer=true：选项带 isAnswer，解析与答案都在")
    void exposesAnswerWhenFlagOn() throws Exception {
        stubApprovedSingleChoice();

        QuestionVO vo = service.getQuestionDetail(1L, true);

        assertEquals(Boolean.TRUE, vo.getOptions().get(0).getIsAnswer());
        assertEquals(Boolean.FALSE, vo.getOptions().get(1).getIsAnswer());
        assertNotNull(vo.getAnalysis());
        assertEquals("A", vo.getAnswerContent());
        assertNotNull(vo.getGradingPoints());

        String body = json.writeValueAsString(vo);
        assertTrue(body.contains("\"isAnswer\":true"), "显式开启时必须下发：" + body);
        assertTrue(body.contains("analysis"));
        assertTrue(body.contains("answerContent"));
    }

    @Test
    @DisplayName("单参 getQuestionDetail(id) 行为不变：仍含答案（学生端/题库浏览回归）")
    void singleArgKeepsHistoricalBehaviour() {
        stubApprovedSingleChoice();

        QuestionVO vo = service.getQuestionDetail(1L);

        assertEquals(Boolean.TRUE, vo.getOptions().get(0).getIsAnswer());
        assertNotNull(vo.getAnalysis());
        assertEquals("A", vo.getAnswerContent());
    }

    @Test
    @DisplayName("未通过审核的题目：两种入参都抛 QUESTION_NOT_FOUND")
    void rejectsUnapprovedQuestion() {
        Question draft = new Question();
        draft.setId(9L);
        draft.setType("SINGLE_CHOICE");
        draft.setAuditStatus("PENDING");
        when(questionMapper.selectById(9L)).thenReturn(draft);

        BizException withoutAnswer = assertThrows(BizException.class,
                () -> service.getQuestionDetail(9L, false));
        BizException withAnswer = assertThrows(BizException.class,
                () -> service.getQuestionDetail(9L, true));
        assertEquals(ResultCode.QUESTION_NOT_FOUND.getCode(), withoutAnswer.getCode());
        assertEquals(ResultCode.QUESTION_NOT_FOUND.getCode(), withAnswer.getCode());
    }
}
