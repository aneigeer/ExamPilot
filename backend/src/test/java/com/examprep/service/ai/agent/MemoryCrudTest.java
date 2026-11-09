package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.dto.ai.AgentMemoryRequest;
import com.examprep.dto.ai.AgentMemoryVO;
import com.examprep.entity.AiAgentMemory;
import com.examprep.mapper.AiAgentMemoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 长期记忆 CRUD：本人隔离 / 上限 / 逻辑删除 / 复活。
 *
 * <p><b>为什么值得测</b>：长期记忆是<b>学生自己的数据</b>，出错代价比普通功能高：
 * <ul>
 *   <li>越权若返回 404 而不是 403，越权尝试会被记录成"记录不存在"，安全审计看不见；</li>
 *   <li>上限静默丢弃 → 学生以为存成功了，下一轮对话却没生效（最难查的一类问题）；</li>
 *   <li>逻辑删除 + {@code UNIQUE(user_id,mem_key)} → 「删了再加」必撞唯一键，
 *       不复活就是 500。这条是设计阶段就该想到、但极易漏掉的组合。</li>
 * </ul>
 */
@DisplayName("长期记忆：隔离 / 上限 / 逻辑删除 / 复活")
class MemoryCrudTest {

    private static final Long ME = 7L;
    private static final Long OTHER = 8L;

    private AiAgentMemoryMapper memoryMapper;
    private AgentMemoryService service;

    @BeforeEach
    void setUp() {
        memoryMapper = mock(AiAgentMemoryMapper.class);
        service = new AgentMemoryService(memoryMapper);
    }

    private AiAgentMemory memory(Long id, Long userId, String key, String value, int deleted) {
        AiAgentMemory m = new AiAgentMemory();
        m.setId(id);
        m.setUserId(userId);
        m.setMemKey(key);
        m.setMemValue(value);
        m.setSource(AiAgentMemory.SOURCE_USER);
        m.setStatus(AiAgentMemory.STATUS_ACTIVE);
        m.setIsDeleted(deleted);
        return m;
    }

    private AgentMemoryRequest request(String key, String value) {
        AgentMemoryRequest req = new AgentMemoryRequest();
        req.setMemKey(key);
        req.setMemValue(value);
        return req;
    }

    // ------------------------------------------------------------------ 隔离

    @Test
    @DisplayName("越权修改：403（不是 404 —— 404 会让越权在审计里隐身）")
    void updateRejectsOtherUsersMemory() {
        when(memoryMapper.selectAnyById(5L)).thenReturn(memory(5L, OTHER, "目标考试", "国考", 0));

        BizException e = assertThrows(BizException.class,
                () -> service.update(ME, 5L, request("目标考试", "省考")));

        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(memoryMapper, never()).updateValue(any(), anyString());
    }

    @Test
    @DisplayName("越权删除：403，且不执行逻辑删除")
    void deleteRejectsOtherUsersMemory() {
        when(memoryMapper.selectAnyById(5L)).thenReturn(memory(5L, OTHER, "目标考试", "国考", 0));

        BizException e = assertThrows(BizException.class, () -> service.delete(ME, 5L));

        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(memoryMapper, never()).softDelete(any());
    }

    @Test
    @DisplayName("记录不存在/已删除：404")
    void missingMemoryIs404() {
        when(memoryMapper.selectAnyById(404L)).thenReturn(null);
        when(memoryMapper.selectAnyById(405L)).thenReturn(memory(405L, ME, "k", "v", 1));

        assertEquals(ResultCode.NOT_FOUND.getCode(),
                assertThrows(BizException.class, () -> service.delete(ME, 404L)).getCode());
        assertEquals(ResultCode.NOT_FOUND.getCode(),
                assertThrows(BizException.class, () -> service.delete(ME, 405L)).getCode());
    }

    // ------------------------------------------------------------------ 上限

    @Test
    @DisplayName("上限 20：已满 → 409，不插入")
    void rejectsWhenLimitReached() {
        when(memoryMapper.selectAnyByKey(ME, "新键")).thenReturn(null);
        when(memoryMapper.countActive(ME)).thenReturn(20L);

        BizException e = assertThrows(BizException.class,
                () -> service.create(ME, request("新键", "新值")));

        assertEquals(ResultCode.CONFLICT.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("20"), e.getMessage());
        verify(memoryMapper, never()).insertMemory(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("上限未满（19 条）：可以新增")
    void allowsBelowLimit() {
        when(memoryMapper.selectAnyByKey(ME, "新键")).thenReturn(null);
        when(memoryMapper.countActive(ME)).thenReturn(19L);
        when(memoryMapper.selectAnyByKey(eq(ME), eq("新键")))
                .thenReturn(null, memory(1L, ME, "新键", "新值", 0));

        AgentMemoryVO vo = service.create(ME, request("新键", "新值"));

        assertEquals("新键", vo.getMemKey());
        verify(memoryMapper, times(1))
                .insertMemory(eq(ME), eq("新键"), eq("新值"), eq(AiAgentMemory.SOURCE_USER));
    }

    // ------------------------------------------------------------------ 键冲突与复活

    @Test
    @DisplayName("同键已存在（有效）：409，提示改用修改")
    void duplicateActiveKeyIs409() {
        when(memoryMapper.selectAnyByKey(ME, "目标考试"))
                .thenReturn(memory(3L, ME, "目标考试", "国考", 0));

        BizException e = assertThrows(BizException.class,
                () -> service.create(ME, request("目标考试", "省考")));

        assertEquals(ResultCode.CONFLICT.getCode(), e.getCode());
        verify(memoryMapper, never()).insertMemory(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("★ 同键曾被逻辑删除：复活原记录（UPDATE），不 INSERT —— 否则唯一键会 500")
    void revivesSoftDeletedKey() {
        when(memoryMapper.selectAnyByKey(ME, "目标考试"))
                .thenReturn(memory(3L, ME, "目标考试", "旧值", 1));
        when(memoryMapper.countActive(ME)).thenReturn(5L);
        when(memoryMapper.revive(3L, "新值", AiAgentMemory.SOURCE_USER)).thenReturn(1);
        when(memoryMapper.selectAnyById(3L)).thenReturn(memory(3L, ME, "目标考试", "新值", 0));

        AgentMemoryVO vo = service.create(ME, request("目标考试", "新值"));

        assertEquals("新值", vo.getMemValue());
        verify(memoryMapper, times(1)).revive(3L, "新值", AiAgentMemory.SOURCE_USER);
        verify(memoryMapper, never()).insertMemory(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("复活时并发被改：409（不静默成功）")
    void reviveConflictIs409() {
        when(memoryMapper.selectAnyByKey(ME, "目标考试"))
                .thenReturn(memory(3L, ME, "目标考试", "旧值", 1));
        when(memoryMapper.countActive(ME)).thenReturn(5L);
        when(memoryMapper.revive(3L, "新值", AiAgentMemory.SOURCE_USER)).thenReturn(0);

        assertEquals(ResultCode.CONFLICT.getCode(),
                assertThrows(BizException.class,
                        () -> service.create(ME, request("目标考试", "新值"))).getCode());
    }

    @Test
    @DisplayName("复活同样占额度：已满 20 条时复活 → 409")
    void reviveAlsoRespectsLimit() {
        when(memoryMapper.selectAnyByKey(ME, "目标考试"))
                .thenReturn(memory(3L, ME, "目标考试", "旧值", 1));
        when(memoryMapper.countActive(ME)).thenReturn(20L);

        assertEquals(ResultCode.CONFLICT.getCode(),
                assertThrows(BizException.class,
                        () -> service.create(ME, request("目标考试", "新值"))).getCode());
        verify(memoryMapper, never()).revive(any(), anyString(), anyString());
    }

    // ------------------------------------------------------------------ 参数校验

    @Test
    @DisplayName("value 超 200 字：400；空值/空键同样 400，且不查库")
    void validatesValueLength() {
        String tooLong = "字".repeat(AgentMemoryService.MAX_VALUE_CHARS + 1);
        assertEquals(ResultCode.PARAM_ERROR.getCode(),
                assertThrows(BizException.class, () -> service.create(ME, request("键", tooLong))).getCode());
        assertEquals(ResultCode.PARAM_ERROR.getCode(),
                assertThrows(BizException.class, () -> service.create(ME, request("键", "  "))).getCode());
        assertEquals(ResultCode.PARAM_ERROR.getCode(),
                assertThrows(BizException.class, () -> service.create(ME, request("", "值"))).getCode());
        verifyNoInteractions(memoryMapper);
    }

    @Test
    @DisplayName("value 恰好 200 字：合法（边界不能差一）")
    void acceptsExactlyMaxLength() {
        String exact = "字".repeat(AgentMemoryService.MAX_VALUE_CHARS);
        when(memoryMapper.selectAnyByKey(ME, "键")).thenReturn(null);
        when(memoryMapper.countActive(ME)).thenReturn(1L);
        when(memoryMapper.selectAnyByKey(eq(ME), eq("键")))
                .thenReturn(null, memory(1L, ME, "键", exact, 0));

        assertEquals(exact, service.create(ME, request("键", exact)).getMemValue());
    }

    @Test
    @DisplayName("★ 记忆键 33 字：400（不是 500、不是被 DB 截断）——三处上限必须同为 32")
    void rejectsKeyLongerThan32() {
        String tooLong = "键".repeat(AgentMemoryService.MAX_KEY_CHARS + 1);

        BizException e = assertThrows(BizException.class,
                () -> service.create(ME, request(tooLong, "值")));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("32"), e.getMessage());
        verifyNoInteractions(memoryMapper);
    }

    @Test
    @DisplayName("记忆键恰好 32 字：通过（与 DTO @Size(32)、DDL varchar(32) 对齐）")
    void acceptsKeyExactly32() {
        String exact = "键".repeat(32);
        when(memoryMapper.selectAnyByKey(ME, exact)).thenReturn(null);
        when(memoryMapper.countActive(ME)).thenReturn(1L);
        when(memoryMapper.selectAnyByKey(eq(ME), eq(exact)))
                .thenReturn(null, memory(1L, ME, exact, "值", 0));

        assertEquals(exact, service.create(ME, request(exact, "值")).getMemKey());
        assertEquals(32, AgentMemoryService.MAX_KEY_CHARS, "服务层上限必须是 32");
    }

    // ------------------------------------------------------------------ 读与删

    @Test
    @DisplayName("列表：按最后更新时间倒序（mapper 已排序，此处校验不下发已删除行）")
    void listReturnsOrderedItems() {
        when(memoryMapper.selectActive(ME, AgentMemoryService.LIST_LIMIT)).thenReturn(List.of(
                memory(2L, ME, "薄弱模块", "资料分析", 0),
                memory(1L, ME, "目标考试", "国考", 0)));

        List<AgentMemoryVO> list = service.list(ME);

        assertEquals(2, list.size());
        assertEquals("薄弱模块", list.get(0).getMemKey(), "最近更新的在前");
        assertEquals("目标考试", list.get(1).getMemKey());
    }

    @Test
    @DisplayName("删除：走逻辑删除（不物理删），删后不可再改")
    void deleteIsLogical() {
        when(memoryMapper.selectAnyById(3L)).thenReturn(memory(3L, ME, "目标考试", "国考", 0));
        when(memoryMapper.softDelete(3L)).thenReturn(1);

        service.delete(ME, 3L);

        verify(memoryMapper, times(1)).softDelete(3L);
    }

    @Test
    @DisplayName("管理端只读列表：userId 必填（400）")
    void adminListRequiresUserId() {
        assertEquals(ResultCode.PARAM_ERROR.getCode(),
                assertThrows(BizException.class, () -> service.listForAdmin(null)).getCode());
        verify(memoryMapper, never()).selectActiveForAdmin(any(), anyInt());
    }
}
