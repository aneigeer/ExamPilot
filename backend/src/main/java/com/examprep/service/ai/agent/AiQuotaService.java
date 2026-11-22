package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.entity.AiQuotaOverride;
import com.examprep.mapper.AiAgentRunMapper;
import com.examprep.mapper.AiQuotaOverrideMapper;
import com.examprep.service.ai.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用户/角色级每日 AI Token 与请求配额闸门服务。
 *
 * <p>职责：
 * <ul>
 *   <li>合并 {@code ai_agent_run} 当日实际落库用量与进程内单轮 QA 计数；</li>
 *   <li>支持通过 {@code ai_quota_override} 为指定用户设置高于或低于角色默认值的每日配额；</li>
 *   <li>超额时抛出 {@link ResultCode#TOO_MANY_REQUESTS}（HTTP 429），并返回当日剩余与明日 00:00 重置提示；</li>
 *   <li>数据库查询异常时优雅降级放行（fail-open），绝不因配额表故障导致核心问答 500。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiQuotaService {

    private final AiProperties properties;
    private final AiAgentRunMapper runMapper;
    private final AiQuotaOverrideMapper overrideMapper;

    /** 进程内当日附加请求计数（覆盖不落 ai_agent_run 的单轮 /ai/ask 调用），key = yyyy-MM-dd:userId */
    private final ConcurrentHashMap<String, AtomicInteger> extraRequests = new ConcurrentHashMap<>();

    public record QuotaSnapshot(
            Long userId,
            String role,
            boolean enabled,
            int dailyTokenLimit,
            int dailyRequestLimit,
            long usedTokens,
            long usedRequests,
            long remainingTokens,
            long remainingRequests,
            boolean overridden,
            String resetAt
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("userId", userId);
            map.put("role", role);
            map.put("enabled", enabled);
            map.put("dailyTokenLimit", dailyTokenLimit);
            map.put("dailyRequestLimit", dailyRequestLimit);
            map.put("usedTokens", usedTokens);
            map.put("usedRequests", usedRequests);
            map.put("remainingTokens", remainingTokens);
            map.put("remainingRequests", remainingRequests);
            map.put("overridden", overridden);
            map.put("resetAt", resetAt);
            return map;
        }
    }

    /**
     * 校验用户当日配额；通过时可选累加一次单轮请求计数。
     *
     * @param userId             当前登录用户 ID（游客为 null 时跳过）
     * @param role               角色（ADMIN / AUDITOR / STUDENT）
     * @param countExtraRequest  是否计入进程内附加请求计数（/ai/ask 传 true，/ai/agent/stream 因落 ai_agent_run 传 false）
     */
    public QuotaSnapshot checkAndRecord(Long userId, String role, boolean countExtraRequest) {
        AiProperties.Quota cfg = properties.getQuota();
        if (cfg == null || !cfg.isEnabled() || userId == null) {
            return snapshot(userId, role);
        }
        QuotaSnapshot snap = snapshot(userId, role);
        if (snap.usedRequests() >= snap.dailyRequestLimit()) {
            throw new BizException(ResultCode.TOO_MANY_REQUESTS,
                    String.format("今日 AI 请求次数已达上限（%d/%d），将于 %s 重置",
                            snap.usedRequests(), snap.dailyRequestLimit(), snap.resetAt()));
        }
        if (snap.usedTokens() >= snap.dailyTokenLimit()) {
            throw new BizException(ResultCode.TOO_MANY_REQUESTS,
                    String.format("今日 AI Token 额度已用尽（%d/%d），将于 %s 重置",
                            snap.usedTokens(), snap.dailyTokenLimit(), snap.resetAt()));
        }
        if (countExtraRequest) {
            String key = LocalDate.now() + ":" + userId;
            extraRequests.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
        }
        return snap;
    }

    /** 查询指定用户当日配额与已用快照 */
    public QuotaSnapshot snapshot(Long userId, String role) {
        AiProperties.Quota cfg = properties.getQuota() != null ? properties.getQuota() : new AiProperties.Quota();
        boolean isPrivileged = "ADMIN".equalsIgnoreCase(role) || "AUDITOR".equalsIgnoreCase(role);
        int tokenLimit = isPrivileged ? cfg.getAdminDailyTokens() : cfg.getStudentDailyTokens();
        int reqLimit = isPrivileged ? cfg.getAdminDailyRequests() : cfg.getStudentDailyRequests();
        boolean overridden = false;

        if (userId != null && overrideMapper != null) {
            try {
                AiQuotaOverride ov = overrideMapper.selectByUserId(userId);
                if (ov != null) {
                    if (ov.getDailyTokenLimit() != null && ov.getDailyTokenLimit() > 0) {
                        tokenLimit = ov.getDailyTokenLimit();
                        overridden = true;
                    }
                    if (ov.getDailyRequestLimit() != null && ov.getDailyRequestLimit() > 0) {
                        reqLimit = ov.getDailyRequestLimit();
                        overridden = true;
                    }
                }
            } catch (Exception e) {
                log.warn("[Quota] 查询单用户配额覆盖异常，回退角色默认配额 userId={}：{}", userId, e.getMessage());
            }
        }

        long usedTokens = 0L;
        long usedRequests = 0L;
        LocalDate today = LocalDate.now();
        if (userId != null) {
            AtomicInteger extra = extraRequests.get(today + ":" + userId);
            if (extra != null) {
                usedRequests += extra.get();
            }
            if (runMapper != null) {
                try {
                    LocalDateTime from = LocalDateTime.of(today, LocalTime.MIN);
                    LocalDateTime to = LocalDateTime.of(today, LocalTime.MAX);
                    Map<String, Object> row = runMapper.selectUserDailyUsage(userId, from, to);
                    if (row != null) {
                        usedRequests += toLong(row.get("requestCount"));
                        usedTokens += toLong(row.get("tokenSum"));
                    }
                } catch (Exception e) {
                    log.warn("[Quota] 查询当日用量异常，按已统计值放行 userId={}：{}", userId, e.getMessage());
                }
            }
        }

        long remTokens = Math.max(0L, tokenLimit - usedTokens);
        long remReqs = Math.max(0L, reqLimit - usedRequests);
        String resetAt = LocalDateTime.of(today.plusDays(1), LocalTime.MIN).toString();
        return new QuotaSnapshot(
                userId,
                role == null ? "STUDENT" : role,
                cfg.isEnabled(),
                tokenLimit,
                reqLimit,
                usedTokens,
                usedRequests,
                remTokens,
                remReqs,
                overridden,
                resetAt
        );
    }

    /** 管理员新增或更新指定用户的每日配额覆盖 */
    public QuotaSnapshot updateOverride(Long userId, Integer dailyTokenLimit, Integer dailyRequestLimit,
                                        String remark, Long operatorId) {
        if (userId == null || userId <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "userId 非法");
        }
        if (dailyTokenLimit != null && dailyTokenLimit <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "dailyTokenLimit 必须大于 0");
        }
        if (dailyRequestLimit != null && dailyRequestLimit <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "dailyRequestLimit 必须大于 0");
        }
        AiQuotaOverride existing = overrideMapper.selectByUserId(userId);
        if (existing == null) {
            AiQuotaOverride entity = new AiQuotaOverride();
            entity.setUserId(userId);
            entity.setDailyTokenLimit(dailyTokenLimit);
            entity.setDailyRequestLimit(dailyRequestLimit);
            entity.setRemark(remark);
            entity.setUpdatedBy(operatorId);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            overrideMapper.insert(entity);
        } else {
            existing.setDailyTokenLimit(dailyTokenLimit);
            existing.setDailyRequestLimit(dailyRequestLimit);
            existing.setRemark(remark);
            existing.setUpdatedBy(operatorId);
            existing.setUpdatedAt(LocalDateTime.now());
            overrideMapper.updateById(existing);
        }
        return snapshot(userId, "STUDENT");
    }

    private static long toLong(Object val) {
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val != null) {
            try {
                return Long.parseLong(val.toString());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
