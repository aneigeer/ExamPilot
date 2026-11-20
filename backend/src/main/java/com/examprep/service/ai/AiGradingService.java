package com.examprep.service.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.entity.Question;
import com.examprep.entity.QuestionAnswer;
import com.examprep.mapper.QuestionAnswerMapper;
import com.examprep.mapper.QuestionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 主观题 AI 判分（MVP）
 *
 * <p><b>为什么需要它</b>：原方案里主观题只有「自评（会了/模糊/不会）」，
 * 而用户最初要的是「AI 判分」——两者差着一个核心模块：自评拿不到得分点，
 * 也就无法回答「我这段答案到底漏了什么」。
 *
 * <p><b>MVP 的刻意取舍</b>：不做 Rubric 逐项精算（那需要多轮交互与评分细则标注），
 * 先做<b>单轮 Prompt</b>：题目 + 参考答案 + 学生作答 → 输出
 * 「命中得分点 / 遗漏得分点 / 一句话理由 / 保守得分率」。
 * 因此对外只给<b>等级 + 理由</b>，不冒充精确分数。
 *
 * <p><b>降级铁律</b>：大模型不可用时<b>绝不阻塞作答</b>，
 * 返回 {@link GradeResult#unavailable} 并把决定权交回用户（仍可自评）。
 * 这条与全项目「AI 组件故障必须静默降级」的口径一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGradingService {

    private final QuestionMapper questionMapper;
    private final QuestionAnswerMapper questionAnswerMapper;
    private final ObjectMapper objectMapper;
    private final List<LlmClient> llmClients;

    /** 学生作答短于此长度直接判「无效作答」，不浪费一次大模型调用 */
    private static final int MIN_ANSWER_LENGTH = 4;

    /** 参与判分的客户端档位（与问答链路一致，仅排除抽取式兜底） */
    private static final String EXTRACTIVE = "extractive";

    private static final String SYSTEM_PROMPT = """
            你是严谨的阅卷老师。你只做一件事：把学生作答与参考答案做结构化比对。
            铁律：
            1. 只依据参考答案判断，不引入参考答案之外的额外要求；
            2. 不因学生写得多就给分，也不因表述口语化就扣分；
            3. 得分率必须保守，宁可偏低不要虚高；
            4. 只输出 JSON，不输出任何其他文字。""";

    /**
     * 判分结果
     *
     * @param available    判分是否真的执行了（false 时前端应回退到自评）
     * @param level        EXCELLENT / GOOD / PARTIAL / POOR / INVALID / UNAVAILABLE
     * @param scoreRate    参考得分率 0~1（**参考值，不是精确分数**）
     * @param hitPoints    命中的得分点
     * @param missedPoints 遗漏的得分点
     * @param comment      一句话理由
     * @param generator    生成器标识（llm / rule / none）
     * @param reason       不可用原因（available=false 时）
     */
    public record GradeResult(
            boolean available,
            String level,
            double scoreRate,
            List<String> hitPoints,
            List<String> missedPoints,
            String comment,
            String generator,
            String reason
    ) {
        /** 降级：判分没跑，前端回退自评 */
        public static GradeResult unavailable(String reason) {
            return new GradeResult(false, "UNAVAILABLE", 0, List.of(), List.of(),
                    "AI 判分暂不可用，你的作答已保留，可先自评", "none", reason);
        }
    }

    /**
     * 对一道主观题判分
     *
     * @param questionId 题目 ID
     * @param userAnswer 学生作答原文
     * @return 判分结果；<b>任何异常都不外抛</b>（判分失败绝不能让作答丢失）
     */
    public GradeResult grade(Long questionId, String userAnswer) {
        String answer = userAnswer == null ? "" : userAnswer.trim();
        if (questionId == null) {
            return GradeResult.unavailable("缺少题目 ID");
        }
        if (answer.length() < MIN_ANSWER_LENGTH) {
            // 空答/乱答不调用大模型：既省钱，也避免模型「硬凑得分点」给出虚假鼓励
            return new GradeResult(true, "INVALID", 0.0, List.of(), List.of(),
                    "作答过短，无法判分。请写出你的思路或关键结论", "rule", "");
        }
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            return GradeResult.unavailable("题目不存在");
        }
        QuestionAnswer reference = questionAnswerMapper.selectOne(
                Wrappers.<QuestionAnswer>lambdaQuery()
                        .eq(QuestionAnswer::getQuestionId, questionId)
                        .last("LIMIT 1"));
        if (reference == null) {
            return GradeResult.unavailable("该题未配置参考答案，暂不支持 AI 判分");
        }

        String referenceText = flattenReference(reference.getAnswerContent());
        if (referenceText.isBlank()) {
            return GradeResult.unavailable("该题参考答案为空，暂不支持 AI 判分");
        }
        return grade(question.getStem(), referenceText, answer);
    }

    /**
     * 按「题干 + 参考答案」判分。
     *
     * <p>与 {@link #grade(Long, String)} 共用同一套 Prompt 与解析；
     * 供评测脚本（不依赖题库 ID）与内部预览使用。
     *
     * @param stem          题干
     * @param referenceText 参考答案（已拍平为文本）
     * @param userAnswer    学生作答
     * @return 判分结果；任何异常都不外抛
     */
    public GradeResult grade(String stem, String referenceText, String userAnswer) {
        String answer = userAnswer == null ? "" : userAnswer.trim();
        if (answer.length() < MIN_ANSWER_LENGTH) {
            return new GradeResult(true, "INVALID", 0.0, List.of(), List.of(),
                    "作答过短，无法判分。请写出你的思路或关键结论", "rule", "");
        }
        String reference = referenceText == null ? "" : referenceText.trim();
        if (reference.isEmpty()) {
            return GradeResult.unavailable("参考答案为空，暂不支持 AI 判分");
        }
        String userPrompt = buildUserPrompt(stem, reference, answer);

        String lastError = "";
        for (LlmClient client : llmClients) {
            if (!isCandidate(client) || !client.available()) {
                continue;
            }
            try {
                String raw = client.complete(SYSTEM_PROMPT, userPrompt);
                GradeResult parsed = parse(raw, client.name());
                if (parsed != null) {
                    log.info("[AI判分] 判分完成（{}，等级 {}，得分率 {}）",
                            client.name(), parsed.level(), parsed.scoreRate());
                    return parsed;
                }
                lastError = client.name() + "：返回内容无法解析为判分结构";
                log.warn("[AI判分] {}", lastError);
            } catch (Exception e) {
                lastError = client.name() + "：" + e.getMessage();
                log.warn("[AI判分] {} 判分失败，尝试下一个生成器: {}", client.name(), e.getMessage());
            }
        }
        log.info("[AI判分] 无可用大模型，回退自评（{}）", lastError);
        // ★ 必须把真实原因带出来：只说「不可用」会让「Key 失效」和「熔断」
        //   这类完全不同的故障混在一起，排障时无从下手（本项目此前踩过同一个坑）。
        return GradeResult.unavailable(lastError.isEmpty()
                ? "大模型不可用（未配置 Key 或已熔断）"
                : "大模型调用失败 —— " + lastError);
    }

    /** 是否可作为判分生成器（排除抽取式兜底：它只会摘抄，不会判分） */
    private boolean isCandidate(LlmClient client) {
        return client != null && !EXTRACTIVE.equals(client.name());
    }

    /**
     * 构造单轮判分 Prompt
     *
     * <p>输出契约写死为 JSON 且给出字段含义，是为了让解析稳定；
     * 同时明确「不要虚高」，避免模型讨好式给分（这在判分场景是毒性行为）。
     */
    String buildUserPrompt(String stem, String referenceText, String userAnswer) {
        return """
                请对照【参考答案】批改【学生作答】。

                【题目】
                %s

                【参考答案】
                %s

                【学生作答】
                %s

                请先把参考答案拆成 2~6 个得分点（若参考答案是一条完整表述，就拆成语义要点），
                再指出学生命中了哪些、遗漏了哪些，并给出一个保守的得分率（0~1，两位小数）。

                只输出下面这个 JSON，不要输出任何其他文字，不要用代码块包裹：
                {"level":"EXCELLENT|GOOD|PARTIAL|POOR","scoreRate":0.00,
                 "hitPoints":["..."],"missedPoints":["..."],"comment":"一句话理由"}
                """.formatted(nvl(stem), nvl(referenceText), nvl(userAnswer));
    }

    /**
     * 把参考答案 JSON 拍平成可读文本
     *
     * <p>不同题型的 key 不同（填空 {@code blanks}、简答 {@code reference}、
     * 作文 {@code modelEssay}、翻译 {@code referenceTranslation}…），
     * 这里统一按键值对展开，避免为每种题型写一份模板。
     */
    String flattenReference(Object answerContent) {
        if (answerContent == null) {
            return "";
        }
        if (answerContent instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sb.append(String.valueOf(entry.getKey())).append("：")
                        .append(String.valueOf(entry.getValue())).append('\n');
            }
            return sb.toString().trim();
        }
        return String.valueOf(answerContent).trim();
    }

    /**
     * 解析模型输出的判分 JSON
     *
     * <p>容错点：模型常把 JSON 包在 ```json 代码块里或前后多说一句，
     * 所以取第一个 {@code {} 到最后一个 {@code }} 之间的片段再解析；
     * 解析不了就返回 {@code null}，由调用方换下一个生成器，**绝不猜分数**。
     *
     * @param raw       模型原始输出
     * @param generator 生成器标识（写回结果里便于排查）
     * @return 判分结果；无法解析时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    GradeResult parse(String raw, String generator) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            Map<String, Object> map =
                    objectMapper.readValue(raw.substring(start, end + 1), Map.class);
            double rate = parseRate(map.get("scoreRate"));
            String level = alignLevelWithRate(
                    String.valueOf(map.getOrDefault("level", "PARTIAL")).toUpperCase(), rate);
            return new GradeResult(true, level, rate,
                    stringList(map.get("hitPoints")),
                    stringList(map.get("missedPoints")),
                    String.valueOf(map.getOrDefault("comment", "")),
                    generator, "");
        } catch (Exception e) {
            return null;
        }
    }

    private static String alignLevelWithRate(String rawLevel, double rate) {
        if (!List.of("EXCELLENT", "GOOD", "PARTIAL", "POOR").contains(rawLevel)) {
            return "PARTIAL";
        }
        if (rate >= 0.85) {
            return "EXCELLENT";
        }
        if (rate >= 0.60) {
            return "GOOD";
        }
        if (rate >= 0.20) {
            return "PARTIAL";
        }
        return "POOR";
    }

    private static double parseRate(Object value) {
        double rate = 0.0;
        if (value instanceof Number n) {
            rate = n.doubleValue();
        } else if (value != null) {
            try {
                rate = Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return Math.max(0.0, Math.min(1.0, rate));
    }

    private static List<String> stringList(Object value) {
        List<String> list = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item != null) {
                    list.add(String.valueOf(item));
                }
            }
        } else if (value != null) {
            list.add(String.valueOf(value));
        }
        return list;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }
}
