package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.dto.ai.AgentMemoryRequest;
import com.examprep.dto.ai.AgentMemoryVO;
import com.examprep.entity.AiAgentMemory;
import com.examprep.mapper.AiAgentMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 学生长期记忆（LTM）读写。
 *
 * <p><b>产品边界</b>：长期记忆是<b>学生自己的东西</b>——可见、可改、可删，且只对本人生效。
 * 因此这里的每个写操作都必须过归属校验；跨用户访问一律 403，不是 404
 * （404 会让人以为"记录不存在"，而实际上是越权，这两种语义不能混）。
 *
 * <p><b>三条硬约束</b>：
 * <ol>
 *   <li><b>单人上限</b> {@value #MAX_PER_USER} 条 → 超出 409（不是静默丢弃，
 *       否则学生会以为存成功了）；</li>
 *   <li><b>value ≤ {@value #MAX_VALUE_CHARS} 字</b> → 超出 400；</li>
 *   <li><b>键唯一</b>：同键已存在 → 409（要用 PUT 改）；但<b>已逻辑删除</b>的同键 →
 *       走复活（UPDATE 覆盖），否则 {@code UNIQUE(user_id, mem_key)} 会让"删了再加"直接 500。</li>
 * </ol>
 *
 * <p><b>注入时机</b>：记忆在<b>每次请求</b>时现查（{@link AgentSessionService#longTermBlock}），
 * 所以学生删/改一条记忆后，下一轮对话立即生效——不做进程内缓存，避免"改了没生效"这种
 * 最容易被投诉的体验问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryService {

    /** 单学生记忆条数上限 */
    public static final int MAX_PER_USER = 20;

    /** 记忆值最大字符数 */
    public static final int MAX_VALUE_CHARS = 200;

    /** 记忆键最大字符数（★ 必须与 {@code AgentMemoryRequest.@Size(max=32)} 和 DDL {@code mem_key VARCHAR(32)} 三处一致） */
    public static final int MAX_KEY_CHARS = 32;

    /** 注入提示词的条数（只取最近的几条，避免记忆把 prompt 预算吃光） */
    public static final int INJECT_LIMIT = 5;

    /** 列表返回上限（管理端/学生端都用它兜底） */
    public static final int LIST_LIMIT = 100;

    private final AiAgentMemoryMapper memoryMapper;

    /**
     * 学生本人的记忆列表（按更新时间倒序）。
     *
     * @param userId 当前学生
     * @return 记忆列表
     */
    public List<AgentMemoryVO> list(Long userId) {
        return memoryMapper.selectActive(userId, LIST_LIMIT).stream()
                .map(AgentMemoryVO::of)
                .toList();
    }

    /**
     * 新增一条记忆。
     *
     * @param userId  当前学生
     * @param request 键值
     * @return 新建（或复活）后的记忆
     * @throws BizException 键已存在（409）、超上限（409）、参数非法（400）
     */
    public AgentMemoryVO create(Long userId, AgentMemoryRequest request) {
        String key = normalizeKey(request == null ? null : request.getMemKey());
        String value = normalizeValue(request == null ? null : request.getMemValue());

        AiAgentMemory existing = memoryMapper.selectAnyByKey(userId, key);
        if (existing != null && !isDeleted(existing)) {
            throw new BizException(ResultCode.CONFLICT, "记忆键「" + key + "」已存在，请直接修改");
        }
        // 复活也占额度：先校验上限，再决定插入还是复活
        assertUnderLimit(userId);

        if (existing != null) {
            // 复活：UNIQUE(user_id, mem_key) 不允许再 INSERT 一条同键
            int updated = memoryMapper.revive(existing.getId(), value, AiAgentMemory.SOURCE_USER);
            if (updated == 0) {
                throw new BizException(ResultCode.CONFLICT, "该记忆已被并发修改，请刷新后重试");
            }
            log.info("[Memory] 复活长期记忆 userId={} key={}", userId, key);
            return AgentMemoryVO.of(memoryMapper.selectAnyById(existing.getId()));
        }

        memoryMapper.insertMemory(userId, key, value, AiAgentMemory.SOURCE_USER);
        AiAgentMemory saved = memoryMapper.selectAnyByKey(userId, key);
        log.info("[Memory] 新增长期记忆 userId={} key={}", userId, key);
        return AgentMemoryVO.of(saved);
    }

    /**
     * 修改记忆值（键不可改）。
     *
     * @param userId  当前学生
     * @param id      记忆 ID
     * @param request 新值（忽略其中的 memKey）
     * @return 修改后的记忆
     * @throws BizException 记录不存在（404）、越权（403）、参数非法（400）
     */
    public AgentMemoryVO update(Long userId, Long id, AgentMemoryRequest request) {
        AiAgentMemory memory = requireOwned(userId, id);
        String value = normalizeValue(request == null ? null : request.getMemValue());
        if (memoryMapper.updateValue(memory.getId(), value) == 0) {
            throw new BizException(ResultCode.NOT_FOUND, "记忆不存在或已删除");
        }
        log.info("[Memory] 修改长期记忆 userId={} id={}", userId, id);
        return AgentMemoryVO.of(memoryMapper.selectAnyById(memory.getId()));
    }

    /**
     * 删除记忆（逻辑删除）。
     *
     * @param userId 当前学生
     * @param id     记忆 ID
     * @throws BizException 记录不存在（404）、越权（403）
     */
    public void delete(Long userId, Long id) {
        AiAgentMemory memory = requireOwned(userId, id);
        if (memoryMapper.softDelete(memory.getId()) == 0) {
            throw new BizException(ResultCode.NOT_FOUND, "记忆不存在或已删除");
        }
        log.info("[Memory] 删除长期记忆 userId={} id={} key={}", userId, id, memory.getMemKey());
    }

    /**
     * 管理端只读：某学生的记忆列表（不做归属校验，AUDITOR/ADMIN 由安全配置约束）。
     *
     * @param userId 学生 ID
     * @return 记忆列表
     */
    public List<AgentMemoryVO> listForAdmin(Long userId) {
        if (userId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "userId 必填");
        }
        return memoryMapper.selectActiveForAdmin(userId, LIST_LIMIT).stream()
                .map(AgentMemoryVO::of)
                .toList();
    }

    /**
     * 取待注入的「【学生长期记忆】」文本块（由 {@link AgentSessionService} 拼进 memorySummary）。
     *
     * <p>格式固定为 {@code 【学生长期记忆】\n键: 值\n键: 值}：
     * 单独一行的标题让模型能明确区分「学生长期设定」与「最近几轮对话摘要」，
     * 避免把偏好当成上一轮说过的话。
     *
     * <p>只取最近 {@value #INJECT_LIMIT} 条：记忆是"背景信息"，不该挤占 prompt 预算；
     * 学生想扩展影响面时应该改已有记忆，而不是无限加条目（上限 {@value #MAX_PER_USER}）。
     *
     * @param userId 学生 ID，游客为 null
     * @return 文本块；无记忆返回空串（调用方据此决定是否拼这一行）
     */
    public String longTermBlock(Long userId) {
        if (userId == null) {
            return "";
        }
        List<AiAgentMemory> memories = memoryMapper.selectActive(userId, INJECT_LIMIT);
        if (memories == null || memories.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【学生长期记忆】");
        for (AiAgentMemory memory : memories) {
            if (!StringUtils.hasText(memory.getMemKey()) || !StringUtils.hasText(memory.getMemValue())) {
                continue;
            }
            sb.append('\n').append(memory.getMemKey().trim()).append(": ").append(memory.getMemValue().trim());
        }
        // 一条有效内容都没拼进去时不要留一个空标题
        return sb.length() == "【学生长期记忆】".length() ? "" : sb.toString();
    }

    // ==================================================================
    // 内部校验
    // ==================================================================

    private AiAgentMemory requireOwned(Long userId, Long id) {
        if (id == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "缺少记忆 ID");
        }
        AiAgentMemory memory = memoryMapper.selectAnyById(id);
        if (memory == null || isDeleted(memory)) {
            throw new BizException(ResultCode.NOT_FOUND, "记忆不存在");
        }
        if (!Objects.equals(memory.getUserId(), userId)) {
            // 越权必须是 403：404 会让人误以为"记录不存在"，掩盖了真实的越权尝试
            throw new BizException(ResultCode.FORBIDDEN, "无权操作该记忆");
        }
        return memory;
    }

    private void assertUnderLimit(Long userId) {
        if (memoryMapper.countActive(userId) >= MAX_PER_USER) {
            throw new BizException(ResultCode.CONFLICT,
                    "长期记忆最多 " + MAX_PER_USER + " 条，请先删除不用的记忆");
        }
    }

    private static boolean isDeleted(AiAgentMemory memory) {
        return memory.getIsDeleted() != null && memory.getIsDeleted() != 0;
    }

    private static String normalizeKey(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BizException(ResultCode.PARAM_ERROR, "记忆键不能为空");
        }
        String key = raw.trim();
        if (key.length() > MAX_KEY_CHARS) {
            throw new BizException(ResultCode.PARAM_ERROR, "记忆键最多 " + MAX_KEY_CHARS + " 字");
        }
        return key;
    }

    private static String normalizeValue(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BizException(ResultCode.PARAM_ERROR, "记忆值不能为空");
        }
        String value = raw.trim();
        if (value.length() > MAX_VALUE_CHARS) {
            throw new BizException(ResultCode.PARAM_ERROR, "记忆值最多 " + MAX_VALUE_CHARS + " 字");
        }
        return value;
    }
}
