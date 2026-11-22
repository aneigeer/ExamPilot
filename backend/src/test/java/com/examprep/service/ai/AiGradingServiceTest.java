package com.examprep.service.ai;

import com.examprep.entity.Question;
import com.examprep.entity.QuestionAnswer;
import com.examprep.mapper.QuestionAnswerMapper;
import com.examprep.mapper.QuestionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 主观题 AI 判分测试
 *
 * <p><b>核心命题</b>：判分要在「模型靠谱」和「模型不靠谱」两种情况下<b>都不出事</b>——
 * 前者要给得出得分点，后者要如实说不可用并把决定权交回用户（回退自评），
 * <b>绝不能伪造一个分数</b>。
 */
@DisplayName("主观题 AI 判分：能判就判，判不了就说判不了")
class AiGradingServiceTest {

    private static final String GOOD_JSON = """
            {"level":"GOOD","scoreRate":0.72,
             "hitPoints":["malloc 用于动态分配","free 用于释放"],
             "missedPoints":["未说明配对使用"],"comment":"要点基本齐全，漏了释放配对"}
            """;

    /** 桩：可控返回，模拟各家大模型 */
    private static class StubLlm implements LlmClient {
        private final String name;
        private final String reply;
        private final boolean available;

        StubLlm(String name, String reply, boolean available) {
            this.name = name;
            this.reply = reply;
            this.available = available;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public LlmAnswer ask(String question, List<ChunkContext> contexts) {
            throw new AiUnavailableException("桩不支持 ask");
        }

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            if (reply == null) {
                throw new AiUnavailableException("桩故障");
            }
            return reply;
        }
    }

    private record Fixture(AiGradingService service, Question question) {
    }

    private Fixture fixture(List<LlmClient> clients, Object answerContent) {
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        QuestionAnswerMapper answerMapper = mock(QuestionAnswerMapper.class);
        Question question = new Question();
        question.setId(42L);
        question.setStem("C语言中用于动态分配与释放内存的函数是什么？");
        when(questionMapper.selectById(42L)).thenReturn(question);

        QuestionAnswer answer = new QuestionAnswer();
        answer.setQuestionId(42L);
        answer.setAnswerContent(answerContent);
        when(answerMapper.selectOne(any())).thenReturn(answer);

        AiGradingService service = new AiGradingService(
                questionMapper, answerMapper, new ObjectMapper(), clients);
        return new Fixture(service, question);
    }

    /** 参考答案**行不存在**（question_answer 里没有这条记录） */
    private Fixture fixtureWithoutReference(List<LlmClient> clients) {
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        QuestionAnswerMapper answerMapper = mock(QuestionAnswerMapper.class);
        Question question = new Question();
        question.setId(42L);
        question.setStem("C语言中用于动态分配与释放内存的函数是什么？");
        when(questionMapper.selectById(42L)).thenReturn(question);
        when(answerMapper.selectOne(any())).thenReturn(null);
        return new Fixture(new AiGradingService(questionMapper, answerMapper,
                new ObjectMapper(), clients), question);
    }

    @Test
    @DisplayName("① 模型返回规范 JSON → 解析出等级/得分率/得分点，且不是伪造的分数")
    void 正常判分() {
        AiGradingService.GradeResult result =
                fixture(List.of(new StubLlm("zhipu", GOOD_JSON, true)), Map.of("reference", "malloc 申请，free 释放"))
                        .service().grade(42L, "malloc 用来申请内存，free 用来释放内存");

        assertTrue(result.available());
        assertEquals("GOOD", result.level());
        assertEquals(0.72, result.scoreRate());
        assertEquals(List.of("malloc 用于动态分配", "free 用于释放"), result.hitPoints());
        assertEquals(List.of("未说明配对使用"), result.missedPoints());
        assertEquals("zhipu", result.generator());
        assertTrue(result.comment().contains("配对"), "理由要能说明漏了什么");
    }

    @Test
    @DisplayName("② 模型把 JSON 包在代码块/多说一句话 → 仍能解析（容错）")
    void 容错解析() {
        String wrapped = "好的，我按参考答案批改：\n```json\n" + GOOD_JSON + "\n```\n以上。";
        AiGradingService.GradeResult result =
                fixture(List.of(new StubLlm("zhipu", wrapped, true)), Map.of("reference", "malloc/free"))
                        .service().grade(42L, "malloc 申请，free 释放");

        assertTrue(result.available());
        assertEquals("GOOD", result.level());
    }

    @Test
    @DisplayName("③ 模型返回非结构化文本 → 如实判不可用，绝不猜分数")
    void 模型乱答时如实降级() {
        AiGradingService.GradeResult result =
                fixture(List.of(new StubLlm("zhipu", "这道题我觉得答得还行吧，给个及格。", true)),
                        Map.of("reference", "malloc/free"))
                        .service().grade(42L, "malloc 申请，free 释放");

        assertFalse(result.available(), "解析不了就必须判不可用，不能编一个分数出来");
        assertEquals("UNAVAILABLE", result.level());
        assertEquals("none", result.generator());
    }

    @Test
    @DisplayName("④ 第一个客户端故障 → 自动切下一个可用客户端（复用问答降级链）")
    void 自动切换生成器() {
        AiGradingService.GradeResult result = fixture(List.of(
                new StubLlm("dashscope", null, true),
                new StubLlm("zhipu", GOOD_JSON, true)), Map.of("reference", "malloc/free"))
                .service().grade(42L, "malloc 申请，free 释放");

        assertTrue(result.available());
        assertEquals("zhipu", result.generator(), "dashscope 挂了应落到 zhipu");
    }

    @Test
    @DisplayName("⑤ 只剩抽取式兜底 → 判不可用（抽取式只会摘抄，不会判分）")
    void 无可用大模型时回退自评() {
        AiGradingService.GradeResult result =
                fixture(List.of(new StubLlm("extractive", "参考答案原文……", true)),
                        Map.of("reference", "malloc/free"))
                        .service().grade(42L, "malloc 申请，free 释放");

        assertFalse(result.available());
        assertTrue(result.comment().contains("自评"), "必须明确把决定权交回用户");
    }

    @Test
    @DisplayName("⑤b 模型报 401 → 降级原因必须带出真实错误，而不是笼统说「不可用」")
    void 降级原因带出真实错误() {
        LlmClient unauthorized = new StubLlm("zhipu", null, true) {
            @Override
            public String complete(String systemPrompt, String userPrompt) {
                throw new AiUnavailableException("zhipu 结构化生成失败：401 Unauthorized: [no body]");
            }
        };
        AiGradingService.GradeResult result =
                fixture(List.of(unauthorized), Map.of("reference", "malloc/free"))
                        .service().grade(42L, "malloc 申请，free 释放");

        assertFalse(result.available());
        assertTrue(result.reason().contains("401"),
                "Key 失效与熔断是两种完全不同的故障，必须能区分：" + result.reason());
    }

    @Test
    @DisplayName("⑥ 作答过短 → 直接判 INVALID，不调用大模型（省钱且不虚假鼓励）")
    void 过短作答不打分() {
        StubLlm counting = new StubLlm("zhipu", "不应被调用", true);
        AiGradingService.GradeResult result =
                fixture(List.of(counting), Map.of("reference", "malloc/free"))
                        .service().grade(42L, "不知道");

        assertTrue(result.available());
        assertEquals("INVALID", result.level());
        assertEquals("rule", result.generator());
    }

    @Test
    @DisplayName("⑦ 参考答案行不存在 → 如实说明「未配置参考答案」，不硬判")
    void 无参考答案行时不判() {
        AiGradingService.GradeResult result =
                fixtureWithoutReference(List.of(new StubLlm("zhipu", GOOD_JSON, true)))
                        .service().grade(42L, "malloc 申请，free 释放");

        assertFalse(result.available());
        assertTrue(result.reason().contains("未配置参考答案"), "实际：" + result.reason());
    }

    @Test
    @DisplayName("⑦b 参考答案内容为空 → 同样不硬判（空参考会诱使模型乱给分）")
    void 参考答案为空时不判() {
        AiGradingService.GradeResult result =
                fixture(List.of(new StubLlm("zhipu", GOOD_JSON, true)), null)
                        .service().grade(42L, "malloc 申请，free 释放");

        assertFalse(result.available());
        assertTrue(result.reason().contains("参考答案为空"), "实际：" + result.reason());
    }

    @Test
    @DisplayName("⑧ parse 对越界得分率做钳制（模型给 1.5 不能变成 150%）")
    void 得分率钳制() {
        AiGradingService service = new AiGradingService(
                mock(QuestionMapper.class), mock(QuestionAnswerMapper.class),
                new ObjectMapper(), List.of());
        AiGradingService.GradeResult r = service.parse(
                "{\"level\":\"EXCELLENT\",\"scoreRate\":1.5}", "zhipu");
        assertNotNull(r);
        assertEquals(1.0, r.scoreRate());
        AiGradingService.GradeResult r2 = service.parse(
                "{\"level\":\"未知等级\",\"scoreRate\":-0.3}", "zhipu");
        assertNotNull(r2);
        assertEquals("PARTIAL", r2.level(), "非法等级应回落 PARTIAL");
        assertEquals(0.0, r2.scoreRate());
        assertNull(service.parse("没有 JSON 的普通文本", "zhipu"));
    }

    /** 按顺序返回多条回复，记录 completeJson 调用次数（验证「先 JSON 模式、失败再重试」） */
    private static class SeqLlm implements LlmClient {
        private final List<String> replies;
        private int index;
        private int jsonCalls;

        SeqLlm(List<String> replies) {
            this.replies = replies;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String name() {
            return "seq";
        }

        @Override
        public LlmAnswer ask(String question, List<ChunkContext> contexts) {
            throw new AiUnavailableException("桩不支持 ask");
        }

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            return replies.get(Math.min(index++, replies.size() - 1));
        }

        @Override
        public String completeJson(String systemPrompt, String userPrompt) {
            jsonCalls++;
            return complete(systemPrompt, userPrompt);
        }
    }

    @Test
    @DisplayName("⑨ 畸形 JSON（围栏 + 尾逗号 + 缺右括号）经修复后仍能判分")
    void 畸形JSON修复后判分() {
        String broken = """
                ```json
                {"level":"GOOD","scoreRate":0.72,
                 "hitPoints":["malloc 用于动态分配","free 用于释放",],
                 "missedPoints":["未说明配对使用",],
                 "comment":"要点基本齐全，漏了释放配对"
                """; // 故意缺少最后的 }
        SeqLlm client = new SeqLlm(List.of(broken));
        AiGradingService.GradeResult result = fixture(List.of(client), Map.of("reference", "malloc/free"))
                .service().grade(42L, "malloc 申请，free 释放");

        assertTrue(result.available(), "修复后应可用，实际：" + result.reason());
        assertEquals("GOOD", result.level());
        assertEquals(0.72, result.scoreRate());
        assertEquals(1, client.jsonCalls, "首次即应使用 JSON 模式，不应进入重试");
    }

    @Test
    @DisplayName("⑩ 首次完全无法解析 → 带纠正提示重试一次，第二次成功")
    void 解析失败后重试一次() {
        SeqLlm client = new SeqLlm(List.of("这道题答得还行，给个及格。", GOOD_JSON));
        AiGradingService.GradeResult result = fixture(List.of(client), Map.of("reference", "malloc/free"))
                .service().grade(42L, "malloc 申请，free 释放");

        assertTrue(result.available(), "第二次应成功");
        assertEquals(2, client.jsonCalls, "最多重试一次");
        assertEquals("GOOD", result.level());
    }

    @Test
    @DisplayName("⑪ 两次都无法解析 → 才如实降级，不会无限重试")
    void 两次失败后降级() {
        SeqLlm client = new SeqLlm(List.of("乱答一", "乱答二"));
        AiGradingService.GradeResult result = fixture(List.of(client), Map.of("reference", "malloc/free"))
                .service().grade(42L, "malloc 申请，free 释放");

        assertFalse(result.available());
        assertEquals(2, client.jsonCalls, "首次 + 1 次重试");
    }
}
