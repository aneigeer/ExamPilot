package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.Resource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ResourceMapper extends BaseMapper<Resource> {

    /**
     * 重算已审核通过资料的热度分（推荐排序物化用）。
     *
     * <p>公式与设计一致：（下载量×2 + 有效评分均分×min(评分人数,5)）/（发布天数+7）；
     * 评分人数不足 3 人不计评分权重（评分下沉）。时间衰减项含 {@code NOW()}，
     * 故必须由定时任务周期性重算，不能作为生成列。
     *
     * @return 受影响行数
     */
    @Update("""
            UPDATE resource
            SET hot_score = (download_count * 2
                             + IF(rating_count >= 3, IFNULL(rating_avg, 0) * LEAST(rating_count, 5), 0))
                            / (DATEDIFF(NOW(), created_at) + 7)
            WHERE audit_status = 'APPROVED' AND is_deleted = 0
            """)
    int recomputeHotScore();
}
