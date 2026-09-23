package com.examprep.service;

import com.examprep.common.QuestionType;
import com.examprep.entity.QuestionAnswer;
import com.examprep.entity.QuestionOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
public class JudgeService {

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
