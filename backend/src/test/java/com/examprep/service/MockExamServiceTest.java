package com.examprep.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BizException;
import com.examprep.dto.ExamHistoryVO;
import com.examprep.dto.MockPaperVO;
import com.examprep.dto.MockReportVO;
import com.examprep.dto.MockStartRequest;
import com.examprep.dto.PracticeReportVO;
import com.examprep.dto.QuestionVO;
import com.examprep.entity.ExamAnswerDetail;
import com.examprep.entity.ExamPaper;
import com.examprep.entity.ExamPaperQuestion;
import com.examprep.entity.ExamRecord;
import com.examprep.entity.Question;
import com.examprep.entity.QuestionAnswer;
import com.examprep.mapper.ExamAnswerDetailMapper;
import com.examprep.mapper.ExamPaperMapper;
import com.examprep.mapper.ExamPaperQuestionMapper;
import com.examprep.mapper.ExamRecordMapper;
import com.examprep.mapper.QuestionAnswerMapper;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.QuestionOptionMapper;
import com.examprep.mapper.SubjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模拟考试服务测试（WS-1 / T1.4）
 *
 * <p>覆盖范围：
 * <ol>
 *   <li>随机组卷：全库（不传 subjectId）与按类别 + 年份筛选</li>
 *   <li>固定套卷开考：总分按卷面分值求和、题序按 sort 保序</li>
 *   <li>未发布套卷拦截：直接 404，不创建 exam_record</li>
 *   <li>重复交卷：409 CONFLICT</li>
 *   <li>超时自动交卷：状态 EXPIRED 且写入交卷时间</li>
 *   <li>答错自动写入 wrong_book（与刷题同一套 upsert 口径）</li>
 *   <li>填空题成绩报告回填参考答案</li>
 *   <li>历史列表严格按 userId 隔离</li>
 * </ol>
 */
@DisplayName("模拟考试：组卷 / 交卷 / 错题闭环 / 历史")
class MockExamServiceTest {

    private ExamPaperMapper examPaperMapper;
    private ExamPaperQuestionMapper paperQuestionMapper;
    private ExamRecordMapper examRecordMapper;
    private ExamAnswerDetailMapper answerDetailMapper;
    private QuestionMapper questionMapper;
    private QuestionOptionMapper optionMapper;
    private QuestionAnswerMapper answerMapper;
    private QuestionService questionService;
    private JudgeService judgeService;
    private SubjectMapper subjectMapper;
    private WrongBookService wrongBookService;
    private ExamPaperService examPaperService;
    private MockExamService service;

    /**
     * 纯单元测试没有 Spring 容器，LambdaQueryWrapper 的列名解析依赖 TableInfo 缓存，
     * 这里手动初始化一次，否则断言 SQL 片段时会抛 "can not find lambda cache"。
     */
    @BeforeAll
    static void initTableInfoCache() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Question.class);
        TableInfoHelper.initTableInfo(assistant, ExamRecord.class);
    }

    @BeforeEach
    void setUp() {
        examPaperMapper = mock(ExamPaperMapper.class);
        paperQuestionMapper = mock(ExamPaperQuestionMapper.class);
        examRecordMapper = mock(ExamRecordMapper.class);
        answerDetailMapper = mock(ExamAnswerDetailMapper.class);
        questionMapper = mock(QuestionMapper.class);
        optionMapper = mock(QuestionOptionMapper.class);
        answerMapper = mock(QuestionAnswerMapper.class);
        questionService = mock(QuestionService.class);
        judgeService = mock(JudgeService.class);
        subjectMapper = mock(SubjectMapper.class);
        wrongBookService = mock(WrongBookService.class);

        // 真实 ExamPaperService：让「未发布套卷 404」走真实校验逻辑，而不是 mock 出来的假异常
        examPaperService = new ExamPaperService(examPaperMapper, paperQuestionMapper,
                questionMapper, subjectMapper);

        service = new MockExamService(examPaperMapper, paperQuestionMapper, examRecordMapper,
                answerDetailMapper, questionMapper, optionMapper, answerMapper,
                questionService, judgeService, examPaperService, wrongBookService);

        // 题目详情：卷面只需要 id / 题干 / 题型
        when(questionService.buildDetail(any(Question.class), anyBoolean())).thenAnswer(inv -> {
            Question q = inv.getArgument(0);
            QuestionVO vo = new QuestionVO();
            vo.setId(q.getId());
            vo.setStem(q.getStem());
            vo.setType(q.getType());
            vo.setTypeName(q.getType());
            return vo;
        });
        // 默认无选项、无答案，具体用例按需覆盖
        when(optionMapper.selectList(any())).thenReturn(List.of());
        when(answerMapper.selectList(any())).thenReturn(List.of());
        when(paperQuestionMapper.selectCount(any())).thenReturn(2L);
        // 主键回填：MyBatis-Plus 在真实库中会写回自增 ID，这里用 doAnswer 模拟
        doAnswer(inv -> {
            ExamPaper p = inv.getArgument(0);
            p.setId(500L);
            return 1;
        }).when(examPaperMapper).insert(any(ExamPaper.class));
        doAnswer(inv -> {
            ExamRecord r = inv.getArgument(0);
            r.setId(1000L);
            return 1;
        }).when(examRecordMapper).insert(any(ExamRecord.class));
    }

    // ==================================================================
    // ① 随机组卷
    // ==================================================================

    @Test
    @DisplayName("① 全库随机组卷：不传 subjectId 时不加科目条件，试卷类型为 RANDOM")
    void 全库随机组卷() {
        Question q1 = question(1L, "SINGLE_CHOICE", 1);
        Question q2 = question(2L, "TRUE_FALSE", 1);
        when(questionMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(q1, q2)));
        stubPaperQuestions(pq(1L, 1, 1), pq(2L, 2, 1));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(q1, q2));

        MockStartRequest req = new MockStartRequest();
        req.setCount(2);
        req.setDurationMinutes(30);

        MockPaperVO paper = service.start(7L, req);

        assertEquals(2, paper.getQuestions().size());
        assertEquals(1000L, paper.getRecordId().longValue());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Question>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(questionMapper).selectList(captor.capture());
        String sql = sqlOf(captor.getValue());
        assertFalse(sql.contains("subject_id"), "未指定科目时不应出现 subject_id 条件：" + sql);
        assertFalse(sql.contains("exam_code"), "未指定类别时不应出现 exam_code 条件：" + sql);

        ArgumentCaptor<ExamPaper> paperCaptor = ArgumentCaptor.forClass(ExamPaper.class);
        verify(examPaperMapper).insert(paperCaptor.capture());
        assertEquals("RANDOM", paperCaptor.getValue().getPaperType());
        assertEquals(0L, paperCaptor.getValue().getSubjectId().longValue(), "全库组卷用 0 表示不限科目");
    }

    @Test
    @DisplayName("② 按类别 + 年份组卷：exam_code 与 year 条件同时生效")
    void 按类别年份组卷() {
        Question q1 = question(11L, "SINGLE_CHOICE", 2);
        when(questionMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(q1)));
        stubPaperQuestions(pq(11L, 1, 2));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(q1));

        MockStartRequest req = new MockStartRequest();
        req.setExamCode("CET4");
        req.setYear(2023);
        req.setCount(10);
        req.setDurationMinutes(60);

        service.start(7L, req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Question>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(questionMapper).selectList(captor.capture());
        Wrapper<Question> wrapper = captor.getValue();
        String sql = sqlOf(wrapper);
        assertTrue(sql.contains("exam_code"), "应拼接 exam_code 条件：" + sql);
        assertTrue(sql.contains("year"), "应拼接 year 条件：" + sql);
        Collection<Object> values = paramsOf(wrapper);
        assertTrue(values.contains("CET4"), "exam_code 参数应为 CET4，实际：" + values);
        assertTrue(values.contains(2023), "year 参数应为 2023，实际：" + values);
    }

    // ==================================================================
    // ② 固定套卷
    // ==================================================================

    @Test
    @DisplayName("③ 固定套卷开考：总分按卷面分值求和，题序按 sort 保序")
    void 固定套卷开考() {
        ExamPaper paper = fixedPaper(9L, 1, "CUSTOM", 60);
        when(examPaperMapper.selectById(9L)).thenReturn(paper);
        // 故意让 selectBatchIds 返回乱序，验证服务层按 sort 重排而不是信数据库返回顺序
        Question q10 = question(10L, "SINGLE_CHOICE", 1);
        Question q20 = question(20L, "MULTI_CHOICE", 1);
        Question q30 = question(30L, "TRUE_FALSE", 1);
        stubPaperQuestions(pq(10L, 1, 2), pq(20L, 2, 5), pq(30L, 3, 3));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(q30, q10, q20));

        MockPaperVO vo = service.startFromPaper(7L, 9L);

        assertEquals(9L, vo.getPaperId().longValue());
        assertEquals(10, vo.getTotalScore().intValue(), "2 + 5 + 3 = 10");
        assertEquals(List.of(10L, 20L, 30L),
                vo.getQuestions().stream().map(QuestionVO::getId).toList(),
                "卷面题序必须与 exam_paper_question.sort 一致");

        ArgumentCaptor<ExamRecord> recordCaptor = ArgumentCaptor.forClass(ExamRecord.class);
        verify(examRecordMapper).insert(recordCaptor.capture());
        assertEquals("ONGOING", recordCaptor.getValue().getStatus());
        assertEquals(10, recordCaptor.getValue().getTotalScore().intValue());
        assertTrue(recordCaptor.getValue().getDeadline().isAfter(LocalDateTime.now().plusMinutes(50)));
    }

    @Test
    @DisplayName("④ 未发布套卷：开考返回 404，且不创建考试记录")
    void 未发布套卷拦截() {
        when(examPaperMapper.selectById(9L)).thenReturn(fixedPaper(9L, 0, "CUSTOM", 60));

        BizException ex = assertThrows(BizException.class, () -> service.startFromPaper(7L, 9L));
        assertEquals(404, ex.getCode().intValue());
        verify(examRecordMapper, org.mockito.Mockito.never()).insert(any(ExamRecord.class));
    }

    @Test
    @DisplayName("⑤ 随机卷不可被当作固定套卷开考（paperType=RANDOM 同样 404）")
    void 随机卷不可开考() {
        ExamPaper random = fixedPaper(9L, 1, "RANDOM", 60);
        when(examPaperMapper.selectById(9L)).thenReturn(random);

        BizException ex = assertThrows(BizException.class, () -> service.startFromPaper(7L, 9L));
        assertEquals(404, ex.getCode().intValue());
    }

    // ==================================================================
    // ③ 交卷判分与错题闭环
    // ==================================================================

    @Test
    @DisplayName("⑥ 交卷：答错题写入错题本，填空题报告回填参考答案")
    void 交卷写错题本并回填填空答案() {
        ExamRecord record = ongoingRecord(1000L, 500L, 7L, 30);
        when(examRecordMapper.selectById(1000L)).thenReturn(record);

        ExamAnswerDetail d1 = detail(1000L, 1L, "B");
        ExamAnswerDetail d2 = detail(1000L, 2L, "printf");
        when(answerDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(d1, d2)));

        Question q1 = question(1L, "SINGLE_CHOICE", 1);
        Question q2 = question(2L, "FILL_BLANK", 1);
        stubPaperQuestions(pq(1L, 1, 1), pq(2L, 2, 1));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(q1, q2));

        QuestionAnswer fillAnswer = new QuestionAnswer();
        fillAnswer.setQuestionId(2L);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("blanks", List.of("printf", "scanf"));
        fillAnswer.setAnswerContent(content);
        when(answerMapper.selectOne(any())).thenReturn(fillAnswer);

        when(judgeService.judge(eq(q1), anyList(), any(), eq("B")))
                .thenReturn(new JudgeService.Result(true, "B"));
        when(judgeService.judge(eq(q2), anyList(), any(), eq("printf")))
                .thenReturn(new JudgeService.Result(false, "printf | scanf"));

        MockReportVO report = service.submit(7L, 1000L);

        assertEquals("FINISHED", report.getStatus());
        assertEquals(0, new BigDecimal("1").compareTo(report.getGotScore()), "只有第 1 题答对");
        assertEquals(1, report.getCorrectCount().intValue());

        PracticeReportVO.Item fillItem = report.getDetails().stream()
                .filter(i -> i.getQuestionId().equals(2L))
                .findFirst().orElseThrow();
        assertEquals("printf | scanf", fillItem.getCorrectAnswer(), "填空题必须回填参考答案");
        assertFalse(fillItem.getCorrect());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(wrongBookService).recordWrongBatch(eq(7L), idsCaptor.capture(), eq("MOCK_EXAM"));
        assertEquals(List.of(2L), List.copyOf(idsCaptor.getValue()), "只有答错的第 2 题进入错题本");
    }

    @Test
    @DisplayName("⑦ 重复交卷：409 CONFLICT，不重复写错题本")
    void 重复交卷409() {
        ExamRecord record = ongoingRecord(1000L, 500L, 7L, 30);
        record.setStatus("FINISHED");
        when(examRecordMapper.selectById(1000L)).thenReturn(record);

        BizException ex = assertThrows(BizException.class, () -> service.submit(7L, 1000L));
        assertEquals(409, ex.getCode().intValue());
        verify(wrongBookService, org.mockito.Mockito.never())
                .recordWrongBatch(any(), any(), any());
    }

    @Test
    @DisplayName("⑧ 超时自动交卷：状态 EXPIRED 且落交卷时间")
    void 超时自动交卷() {
        ExamRecord record = ongoingRecord(1000L, 500L, 7L, 30);
        record.setDeadline(LocalDateTime.now().minusMinutes(5));
        when(examRecordMapper.selectById(1000L)).thenReturn(record);

        ExamAnswerDetail d1 = detail(1000L, 1L, "B");
        when(answerDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(d1)));
        Question q1 = question(1L, "SINGLE_CHOICE", 1);
        stubPaperQuestions(pq(1L, 1, 1));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(q1));
        when(judgeService.judge(eq(q1), anyList(), any(), eq("B")))
                .thenReturn(new JudgeService.Result(false, "C"));

        MockPaperVO paper = service.getPaper(7L, 1000L);

        assertEquals("EXPIRED", paper.getStatus());
        assertNotNull(record.getSubmittedAt());
        assertEquals(0, BigDecimal.ZERO.compareTo(record.getGotScore()));
        assertTrue(record.getDeadline().isBefore(LocalDateTime.now()));
    }

    // ==================================================================
    // ④ 历史列表
    // ==================================================================

    @Test
    @DisplayName("⑨ 历史列表：按 userId 过滤，得分率按总分换算")
    void 历史列表按用户隔离() {
        ExamRecord r1 = finishedRecord(1L, 500L, 7L, 2, 1);
        ExamRecord r2 = finishedRecord(2L, 500L, 7L, 2, 2);
        Page<ExamRecord> page = new Page<>(1, 10);
        page.setRecords(List.of(r1, r2));
        page.setTotal(2);
        doReturn(page).when(examRecordMapper).selectPage(any(), any());

        ExamPaper paper = fixedPaper(500L, 1, "OFFICIAL", 120);
        paper.setName("2023 四级真题第一套");
        when(examPaperMapper.selectBatchIds(any())).thenReturn(List.of(paper));

        var result = service.history(7L, 1, 10);

        assertEquals(2L, result.getTotal().longValue());
        assertEquals(2, result.getList().size());
        ExamHistoryVO first = result.getList().get(0);
        assertEquals("OFFICIAL", first.getPaperType());
        assertEquals("2023 四级真题第一套", first.getPaperName());
        assertEquals(0, new BigDecimal("50.0").compareTo(first.getScoreRate()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<ExamRecord>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(examRecordMapper).selectPage(any(), captor.capture());
        assertTrue(sqlOf(captor.getValue()).contains("user_id"), "历史查询必须带 userId 条件");
        assertTrue(paramsOf(captor.getValue()).contains(7L), "userId 参数应为当前登录用户");
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private void stubPaperQuestions(ExamPaperQuestion... pqs) {
        when(paperQuestionMapper.selectList(any())).thenReturn(List.of(pqs));
    }

    private static Question question(Long id, String type, int score) {
        Question q = new Question();
        q.setId(id);
        q.setType(type);
        q.setStem("题干 " + id);
        q.setScore(score);
        q.setAuditStatus("APPROVED");
        q.setParentId(0L);
        return q;
    }

    private static ExamPaperQuestion pq(Long questionId, int sort, int score) {
        ExamPaperQuestion pq = new ExamPaperQuestion();
        pq.setPaperId(500L);
        pq.setQuestionId(questionId);
        pq.setSort(sort);
        pq.setScore(score);
        return pq;
    }

    private static ExamPaper fixedPaper(Long id, int published, String type, int duration) {
        ExamPaper paper = new ExamPaper();
        paper.setId(id);
        paper.setName("固定套卷 " + id);
        paper.setSubjectId(5L);
        paper.setDurationMinutes(duration);
        paper.setTotalScore(10);
        paper.setQuestionCount(3);
        paper.setPaperType(type);
        paper.setPublished(published);
        paper.setExamCode("CET4");
        paper.setYear(2023);
        return paper;
    }

    private static ExamRecord ongoingRecord(Long id, Long paperId, Long userId, int durationMinutes) {
        ExamRecord record = new ExamRecord();
        record.setId(id);
        record.setPaperId(paperId);
        record.setUserId(userId);
        record.setStatus("ONGOING");
        record.setStartedAt(LocalDateTime.now().minusMinutes(5));
        record.setDeadline(LocalDateTime.now().plusMinutes(durationMinutes));
        record.setTotalScore(2);
        record.setGotScore(BigDecimal.ZERO);
        record.setCorrectCount(0);
        record.setAnsweredCount(2);
        return record;
    }

    private static ExamRecord finishedRecord(Long id, Long paperId, Long userId, int total, int got) {
        ExamRecord record = new ExamRecord();
        record.setId(id);
        record.setPaperId(paperId);
        record.setUserId(userId);
        record.setStatus("FINISHED");
        record.setStartedAt(LocalDateTime.now().minusHours(1));
        record.setSubmittedAt(LocalDateTime.now().minusMinutes(30));
        record.setDeadline(LocalDateTime.now().minusMinutes(30));
        record.setTotalScore(total);
        record.setGotScore(BigDecimal.valueOf(got));
        record.setCorrectCount(got);
        record.setAnsweredCount(total);
        return record;
    }

    private static ExamAnswerDetail detail(Long recordId, Long questionId, String answer) {
        ExamAnswerDetail d = new ExamAnswerDetail();
        d.setId(questionId);
        d.setRecordId(recordId);
        d.setQuestionId(questionId);
        d.setUserAnswer(answer);
        d.setAnsweredAt(LocalDateTime.now());
        return d;
    }

    @SuppressWarnings("unchecked")
    private static String sqlOf(Wrapper<?> wrapper) {
        return ((AbstractWrapper<?, ?, ?>) wrapper).getSqlSegment();
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> paramsOf(Wrapper<?> wrapper) {
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values();
    }
}
