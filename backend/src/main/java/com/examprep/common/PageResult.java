package com.examprep.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

/**
 * 统一分页结构：{ page, size, total, list }
 */
@Data
@Schema(description = "分页结果")
public class PageResult<T> {

    @Schema(description = "当前页码，从 1 开始", example = "1")
    private Long page;

    @Schema(description = "每页条数", example = "20")
    private Long size;

    @Schema(description = "总条数", example = "135")
    private Long total;

    @Schema(description = "数据列表")
    private List<T> list;

    public static <T> PageResult<T> of(Long page, Long size, Long total, List<T> list) {
        PageResult<T> r = new PageResult<>();
        r.setPage(page);
        r.setSize(size);
        r.setTotal(total);
        r.setList(list);
        return r;
    }

    /**
     * 从 MyBatis-Plus 的 IPage 转换（避免业务层直接暴露 ORM 对象）
     */
    public static <E, T> PageResult<T> of(com.baomidou.mybatisplus.core.metadata.IPage<E> page,
                                          Function<E, T> mapper) {
        return of(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(mapper).toList());
    }
}
