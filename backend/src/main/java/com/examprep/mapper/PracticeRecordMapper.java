package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.PracticeRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface PracticeRecordMapper extends BaseMapper<PracticeRecord> {

    /**
     * 今日已作答情况（学习计划完成度用）
     *
     * <p>★ 完成度**从真实作答派生**，不单独落一张「计划完成表」：
     * 否则会出现「计划表说完成了、实际没做题」这类自欺数据。
     *
     * @param userId    用户 ID
     * @param subjectId 目标科目，null 表示不限
     * @return 每行 {answeredCount, correctCount}
     */
    @Select("""
            SELECT COALESCE(SUM(answered_count), 0) AS answeredCount,
                   COALESCE(SUM(correct_count), 0)  AS correctCount
            FROM practice_record
            WHERE user_id = #{userId}
              AND started_at >= CURDATE()
              AND (#{subjectId} IS NULL OR subject_id = #{subjectId})
            """)
    Map<String, Object> selectTodayProgress(@Param("userId") Long userId,
                                            @Param("subjectId") Long subjectId);
}

