package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.AiQuotaOverride;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiQuotaOverrideMapper extends BaseMapper<AiQuotaOverride> {

    @Select("SELECT * FROM ai_quota_override WHERE user_id = #{userId} LIMIT 1")
    AiQuotaOverride selectByUserId(@Param("userId") Long userId);
}
