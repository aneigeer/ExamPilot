package com.examprep.service;

import com.examprep.mapper.ResourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 资料热度分定时重算（推荐排序物化列的刷新器）
 *
 * <p>推荐排序原先是含 {@code NOW()} 的表达式，任何索引都用不上、必然 filesort。
 * 物化成 {@code resource.hot_score} 列后可走索引；但时间衰减项随时间变化，
 * 故需要周期性重算。
 *
 * <p><b>失败不影响主业务</b>：重算失败只打 warn，排序退化为上一次的分数快照。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceHotScoreJob {

    private final ResourceMapper resourceMapper;

    @Scheduled(
            initialDelayString = "${app.resource.hot-score.initial-delay-millis:60000}",
            fixedDelayString = "${app.resource.hot-score.refresh-millis:3600000}")
    public void refreshHotScore() {
        try {
            int rows = resourceMapper.recomputeHotScore();
            log.info("[资料] 热度分重算完成，更新 {} 行", rows);
        } catch (Exception e) {
            // 未执行 migration-perf.sql 时会因缺列而失败；只告警，不影响列表与其它业务
            log.warn("[资料] 热度分重算失败（未执行 sql/migration-perf.sql 时会如此，不影响主业务）: {}",
                    e.getMessage());
        }
    }
}
