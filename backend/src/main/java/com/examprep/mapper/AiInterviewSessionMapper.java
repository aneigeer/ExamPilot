package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.AiInterviewSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 面试会话 Mapper
 *
 * <p>列表与详情用 MyBatis-Plus 的 {@code selectPage} / {@code selectById} 即可，
 * 无需自定义 SQL；复杂查询（逐场作答数）在
 * {@link AiInterviewQaMapper#countBySessions} 里一次查回。
 */
@Mapper
public interface AiInterviewSessionMapper extends BaseMapper<AiInterviewSession> {
}
