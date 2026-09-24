package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.QuestionType;
import com.examprep.entity.Question;
import com.examprep.entity.QuestionAnswer;
import com.examprep.entity.QuestionOption;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.examprep.mapper.QuestionAnswerMapper;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.QuestionOptionMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 判分服务：按题型分派
 *
 * 客观题自动判分；主观题返回 null，交由学生自评。
 * 所有题型判分逻辑集中在此，禁止散落到各 Controller。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JudgeService {

    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final QuestionAnswerMapper questionAnswerMapper;

    public record Result(boolean correct, String correctAnswerText) {
    }

    /**
     * 判分
     *
     * @return null 表示主观题（不自动判分）
     */
    public Result judge(String type, List<QuestionOption> options,
                        QuestionAnswer answer, String userAnswer) {
        if (!StringUtils.hasText(type)) {
            return null;
        }
        return switch (type) {
            case "SINGLE_CHOICE", "TRUE_FALSE" -> judgeSingle(options, userAnswer);
            case "MULTI_CHOICE" -> judgeMulti(options, userAnswer);
            case "FILL_BLANK" -> judgeBlank(answer, userAnswer);
            case "MATCHING" -> judgeMatching(answer, userAnswer);
            case "ORDERING" -> judgeOrdering(answer, userAnswer);
            default -> {
                // 主观题：SHORT_ANSWER / ESSAY / WRITING / TRANSLATION ...
                yield null;
            }
        };
    }

    /** 是否为需要自评的主观题 */
    public boolean needSelfEval(String type) {
        return !QuestionType.isObjective(type);
    }

    /**
     * 统一判分入口（P1）：组合类大题走子题判分，其余按题型分派
     */
    public Result judge(Question q, List<QuestionOption> options, QuestionAnswer answer, String userAnswer) {
        if (QuestionType.isComposite(q.getType())) {
            return judgeComposite(q.getId(), userAnswer);
        }
        return judge(q.getType(), options, answer, userAnswer);
    }

    /**
     * 组合类大题判分：用户答案为 JSON {"子题ID":"答案"}，逐子题判分，全部正确才算对
     */
    public Result judgeComposite(Long parentQuestionId, String userAnswer) {
        List<Question> children = questionMapper.selectList(Wrappers.<Question>lambdaQuery()
                .eq(Question::getParentId, parentQuestionId)
                .orderByAsc(Question::getId));
        if (children.isEmpty()) {
            log.warn("组合题 {} 没有子题，按错误处理", parentQuestionId);
            return new Result(false, "");
        }
        Map<String, String> userMap = parseUserAnswer(userAnswer);

        int correctCount = 0;
        for (Question child : children) {
            List<QuestionOption> options = questionOptionMapper.selectList(
                    Wrappers.<QuestionOption>lambdaQuery()
                            .eq(QuestionOption::getQuestionId, child.getId())
                            .orderByAsc(QuestionOption::getSort));
            QuestionAnswer answer = questionAnswerMapper.selectOne(
                    Wrappers.<QuestionAnswer>lambdaQuery()
                            .eq(QuestionAnswer::getQuestionId, child.getId()));
            String sub = userMap.get(String.valueOf(child.getId()));
            Result r = judge(child.getType(), options, answer, sub == null ? "" : sub);
            if (r != null && r.correct()) {
                correctCount++;
            }
        }
        return new Result(correctCount == children.size(),
                "子题 " + correctCount + "/" + children.size() + " 正确");
    }

    private Map<String, String> parseUserAnswer(String userAnswer) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        if (!StringUtils.hasText(userAnswer)) {
            return map;
        }
        try {
            ObjectMapper om = new ObjectMapper();
            Map<?, ?> raw = om.readValue(userAnswer, Map.class);
            raw.forEach((k, v) -> map.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        } catch (Exception e) {
            log.warn("组合题答案解析失败：{}", userAnswer);
        }
        return map;
    }

    // ==================== 匹配 / 排序（P1） ====================

    /** 匹配题：answerContent.pairs = {"A":"1","B":"3"}，用户答案 "A:1,B:3" */
    private Result judgeMatching(QuestionAnswer answer, String userAnswer) {
        Map<?, ?> pairs = answer != null && answer.getAnswerContent() instanceof Map<?, ?> m
                ? (Map<?, ?>) m.get("pairs") : null;
        if (pairs == null || pairs.isEmpty()) {
            log.warn("匹配题缺少答案，无法判分");
            return new Result(false, "");
        }
        // 用户答案为文本格式 "A:1,B:3,C:2"（兼容 = 与全角冒号），不能走 JSON 解析
        Map<String, String> userMap = new java.util.LinkedHashMap<>();
        for (String part : userAnswer.split("[,，]")) {
            String[] kv = part.trim().split("[:=：]");
            if (kv.length == 2 && StringUtils.hasText(kv[0])) {
                userMap.put(kv[0].trim(), kv[1].trim());
            }
        }
        boolean ok = pairs.size() == userMap.size();
        if (ok) {
            for (Map.Entry<?, ?> e : pairs.entrySet()) {
                String v = userMap.get(String.valueOf(e.getKey()));
                if (v == null || !v.equalsIgnoreCase(String.valueOf(e.getValue()))) {
                    ok = false;
                    break;
                }
            }
        }
        String correctText = pairs.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(","));
        return new Result(ok, correctText);
    }

    /** 排序题：answerContent.order = ["C","A","B"]，用户答案 "C,A,B" */
    private Result judgeOrdering(QuestionAnswer answer, String userAnswer) {
        List<?> order = answer != null && answer.getAnswerContent() instanceof Map<?, ?> m
                ? (List<?>) m.get("order") : null;
        if (order == null || order.isEmpty()) {
            log.warn("排序题缺少答案，无法判分");
            return new Result(false, "");
        }
        List<String> expected = order.stream().map(String::valueOf).toList();
        List<String> actual = Arrays.stream(userAnswer.split("[,，]"))
                .map(String::trim).filter(StringUtils::hasText).toList();
        return new Result(expected.equals(actual), String.join(",", expected));
    }

    // ==================== 单选 / 判断 ====================

    private Result judgeSingle(List<QuestionOption> options, String userAnswer) {
        String correctKey = options.stream()
                .filter(o -> Integer.valueOf(1).equals(o.getIsAnswer()))
                .map(QuestionOption::getOptionKey)
                .findFirst()
                .orElse(null);
        if (correctKey == null) {
            log.warn("题目缺少正确答案标记，无法判分");
            return new Result(false, "");
        }
        boolean ok = correctKey.equalsIgnoreCase(trim(userAnswer));
        return new Result(ok, correctKey);
    }

    // ==================== 多选 ====================

    private Result judgeMulti(List<QuestionOption> options, String userAnswer) {
        Set<String> correctKeys = options.stream()
                .filter(o -> Integer.valueOf(1).equals(o.getIsAnswer()))
                .map(QuestionOption::getOptionKey)
                .map(String::toUpperCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> userKeys = splitKeys(userAnswer);

        // 集合完全一致才算对（多选少选都算错，与考试一致）
        boolean ok = !correctKeys.isEmpty() && correctKeys.equals(userKeys);

        String correctText = String.join(",", correctKeys);
        return new Result(ok, correctText);
    }

    private Set<String> splitKeys(String userAnswer) {
        if (!StringUtils.hasText(userAnswer)) {
            return Set.of();
        }
        return Arrays.stream(userAnswer.split("[,，\\s]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(String::toUpperCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ==================== 填空 ====================

    @SuppressWarnings("unchecked")
    private Result judgeBlank(QuestionAnswer answer, String userAnswer) {
        List<String> blanks = extractBlanks(answer);
        if (blanks.isEmpty()) {
            log.warn("填空题缺少答案，无法判分");
            return new Result(false, "");
        }

        boolean caseSensitive = answer != null && Integer.valueOf(1).equals(answer.getCaseSensitive());

        // 用户多个空用竖线分隔
        String[] userParts = userAnswer == null ? new String[0] : userAnswer.split("\\|", -1);

        boolean allOk = true;
        for (int i = 0; i < blanks.size(); i++) {
            String expected = blanks.get(i) == null ? "" : blanks.get(i).trim();
            String actual = i < userParts.length ? userParts[i].trim() : "";
            if (!equalsBlank(expected, actual, caseSensitive)) {
                allOk = false;
                break;
            }
        }

        return new Result(allOk, String.join(" | ", blanks));
    }

    private boolean equalsBlank(String expected, String actual, boolean caseSensitive) {
        if (caseSensitive) {
            return expected.equals(actual);
        }
        return expected.equalsIgnoreCase(actual);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractBlanks(QuestionAnswer answer) {
        if (answer == null || answer.getAnswerContent() == null) {
            return List.of();
        }
        if (answer.getAnswerContent() instanceof Map<?, ?> map) {
            Object blanks = map.get("blanks");
            if (blanks instanceof List<?> list) {
                return list.stream().map(v -> v == null ? "" : String.valueOf(v)).toList();
            }
        }
        return List.of();
    }

    private String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
