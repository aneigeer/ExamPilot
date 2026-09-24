package com.examprep.common;

import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 题型枚举与分期开放策略
 *
 * P0 只开放 5 种核心题型，其余题型数据库已预留，界面后续批次开放。
 */
@Getter
public enum QuestionType {

    // ===== P0 开放 =====
    SINGLE_CHOICE("单选题", true),
    MULTI_CHOICE("多选题", true),
    TRUE_FALSE("判断题", true),
    FILL_BLANK("填空题", true),
    SHORT_ANSWER("简答题", true),

    // ===== P0-B：自评类主观题（已开放，与简答同为自评闭环）=====
    TERM_EXPLAIN("名词解释", true),
    CALCULATION("计算题", true),
    APPLICATION("应用题", true),
    WRITING("作文题", true),
    TRANSLATION("翻译题", true),

    // ===== P1：复杂交互题型（已开放：匹配/排序/完形/阅读/听力/组合/编程；其余预留）=====
    MATCHING("匹配题", true),
    ORDERING("排序题", true),
    CLOZE("完形填空", true),
    READING("阅读理解", true),
    LISTENING("听力选择题", true),
    WORD_FILL("选词填空", false),
    ESSAY("论述题", false),
    PROGRAMMING("编程题", true),
    CASE_ANALYSIS("案例分析题", false),
    ERROR_CORRECTION("改错题", false),
    PROOF("证明题", false),
    COMPOSITE("组合题", true);

    private final String label;
    /** 是否已在 P0 开放 */
    private final boolean open;

    QuestionType(String label, boolean open) {
        this.label = label;
        this.open = open;
    }

    private static final Map<String, QuestionType> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toMap(Enum::name, t -> t));

    /** P0 已开放的题型集合 */
    public static final Set<String> OPEN_TYPES = Arrays.stream(values())
            .filter(QuestionType::isOpen)
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    public static QuestionType of(String name) {
        return name == null ? null : BY_NAME.get(name);
    }

    /** 取中文名，未知题型回退为原值 */
    public static String labelOf(String name) {
        QuestionType t = of(name);
        return t == null ? name : t.getLabel();
    }

    /** 是否为已开放题型 */
    public static boolean isOpen(String name) {
        return OPEN_TYPES.contains(name);
    }

    /** 是否客观题（后端可自动判分） */
    public static boolean isObjective(String name) {
        return switch (name == null ? "" : name) {
            case "SINGLE_CHOICE", "MULTI_CHOICE", "TRUE_FALSE", "FILL_BLANK",
                 "MATCHING", "ORDERING" -> true;
            default -> false;
        };
    }

    /** 是否组合类大题（材料 + 子题） */
    public static boolean isComposite(String name) {
        return switch (name == null ? "" : name) {
            case "READING", "CLOZE", "LISTENING", "COMPOSITE" -> true;
            default -> false;
        };
    }
}
