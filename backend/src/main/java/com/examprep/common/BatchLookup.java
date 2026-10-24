package com.examprep.common;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 按需批量查询工具。
 *
 * <p>用于列表页「补齐关联名称」场景：只拿<b>本页实际引用到的 ID</b>去批量查，
 * 替代原先的 {@code selectList(null)} 整表加载——后者会随用户量/资料量线性放大内存与扫描量，
 * 且会把全表用户昵称读进内存。
 */
public final class BatchLookup {

    private BatchLookup() {
    }

    /**
     * 去重、去空后按 ID 批量查询。
     *
     * <p>ID 集合为空时<b>不发起查询</b>：MyBatis-Plus 的 {@code selectBatchIds} 在空集合下会生成
     * {@code WHERE id IN ()} 语法错误，必须在此拦截。
     */
    public static <T> List<T> byIds(BaseMapper<T> mapper, Collection<? extends Serializable> rawIds) {
        Set<Serializable> ids = distinct(rawIds);
        return ids.isEmpty() ? List.of() : mapper.selectBatchIds(ids);
    }

    /**
     * 按 ID 批量查询并构建「ID → 值」映射（如 科目ID → 科目名）。
     *
     * <p>用 {@link HashMap} 手动装载而非 {@code Collectors.toMap}：后者在值为 null（如昵称为空）时会抛 NPE。
     */
    public static <T, K, V> Map<K, V> nameMap(BaseMapper<T> mapper,
                                              Collection<? extends Serializable> rawIds,
                                              Function<T, K> keyFn,
                                              Function<T, V> valueFn) {
        Map<K, V> map = new HashMap<>();
        for (T item : byIds(mapper, rawIds)) {
            K key = keyFn.apply(item);
            if (key != null) {
                map.putIfAbsent(key, valueFn.apply(item));
            }
        }
        return map;
    }

    private static Set<Serializable> distinct(Collection<? extends Serializable> rawIds) {
        Set<Serializable> ids = new LinkedHashSet<>();
        if (rawIds != null) {
            for (Serializable id : rawIds) {
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }
}
