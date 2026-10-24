package com.examprep.service.wiki;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Wiki JSON 字段读写（key_points / source_chunk_ids / links_json）
 *
 * <p>数据库里统一按 JSON 文本存储，避免引入 JSON 列类型后旧客户端不兼容。
 * 所有解析都容错：脏数据只退化成一个空列表，绝不让问答链路抛异常。
 */
final class WikiJsonSupport {

    private WikiJsonSupport() {
    }

    static List<String> stringList(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = mapper.readValue(json, new TypeReference<List<String>>() {
            });
            List<String> cleaned = new ArrayList<>();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    cleaned.add(value.trim());
                }
            }
            return cleaned;
        } catch (Exception e) {
            return List.of();
        }
    }

    static List<Long> longList(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Object> values = mapper.readValue(json, new TypeReference<List<Object>>() {
            });
            List<Long> ids = new ArrayList<>();
            for (Object value : values) {
                if (value instanceof Number n) {
                    ids.add(n.longValue());
                } else if (value != null) {
                    try {
                        ids.add(Long.parseLong(String.valueOf(value)));
                    } catch (NumberFormatException ignored) {
                        // 单个脏元素跳过，不影响其余合法 ID
                    }
                }
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    static String write(ObjectMapper mapper, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    static String text(Object value, int maxLength) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
