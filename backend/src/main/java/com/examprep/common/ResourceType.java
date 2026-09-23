package com.examprep.common;

import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 资料类型
 */
@Getter
public enum ResourceType {

    COURSEWARE("课件"),
    NOTE("笔记"),
    SUMMARY("知识点总结"),
    MOCK("模拟卷"),
    RECALL("回忆题型"),
    ORIGINAL("原创题");

    private final String label;

    ResourceType(String label) {
        this.label = label;
    }

    private static final Map<String, ResourceType> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toMap(Enum::name, t -> t));

    public static ResourceType of(String name) {
        return name == null ? null : BY_NAME.get(name);
    }

    public static String labelOf(String name) {
        ResourceType t = of(name);
        return t == null ? name : t.getLabel();
    }

    public static boolean isValid(String name) {
        return BY_NAME.containsKey(name);
    }
}
