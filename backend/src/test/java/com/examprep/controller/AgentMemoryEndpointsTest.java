package com.examprep.controller;

import com.examprep.entity.AiAgentMemory;
import com.examprep.mapper.AiAgentMemoryMapper;
import com.examprep.security.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 长期记忆接口的 Web 层验证（真表 + 真鉴权 + 真 JSON）。
 *
 * <p><b>为什么单测不够</b>：{@code MemoryCrudTest} 用 mock mapper，覆盖不到
 * 只在真库/Spring 里才成立的几件事：
 * <ol>
 *   <li>{@code UNIQUE(user_id, mem_key)} 真的会挡住重复插入 —— 所以"删了再加"必须走复活，
 *       否则学生点两次就是 500；</li>
 *   <li>{@code is_deleted} 逻辑删除后，列表与上限计数是否真的把它排除；</li>
 *   <li>{@code SecurityUtils.requireUserId()} 从 {@link LoginUser} 取 userId，
 *       越权判定依赖它 —— 用 {@code @WithMockUser} 测不出来（principal 类型不对会直接 401）；</li>
 *   <li>Bean Validation 的 {@code @Size(32)} 是否真的变成 400。</li>
 * </ol>
 *
 * <p>数据用 {@code @Transactional} 回滚，不落库。
 */
@DisplayName("长期记忆接口：CRUD / 隔离 / 上限 / 逻辑删除 / 复活")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class AgentMemoryEndpointsTest {

    private static final Long ME = 900_101L;
    private static final Long OTHER = 900_102L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiAgentMemoryMapper memoryMapper;

    private Authentication me;

    @BeforeEach
    void setUp() {
        me = authenticationOf(ME);
        // ★ 刻意不清表：用专属 userId（900xxx）+ 事务回滚隔离。
        //   一次 DELETE 全表就是 5 秒内不可逆的灾难，测试绝不该有这种能力。
    }

    private Authentication authenticationOf(Long userId) {
        LoginUser user = LoginUser.fromToken(userId, "stu" + userId, "STUDENT");
        return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    }

    private void insertMemory(Long userId, String key, String value, int deleted) {
        AiAgentMemory memory = new AiAgentMemory();
        memory.setUserId(userId);
        memory.setMemKey(key);
        memory.setMemValue(value);
        memory.setSource(AiAgentMemory.SOURCE_USER);
        memory.setStatus(AiAgentMemory.STATUS_ACTIVE);
        memory.setIsDeleted(deleted);
        memoryMapper.insert(memory);
    }

    @Test
    @DisplayName("POST → GET：新增后能读到，且按更新时间倒序")
    void createThenList() throws Exception {
        mockMvc.perform(post("/me/agent-memory").with(authentication(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memKey\":\"目标考试\",\"memValue\":\"2027 国考\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.memKey").value("目标考试"))
                .andExpect(jsonPath("$.data.memValue").value("2027 国考"))
                .andExpect(jsonPath("$.data.source").value("USER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(post("/me/agent-memory").with(authentication(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memKey\":\"薄弱模块\",\"memValue\":\"资料分析\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/me/agent-memory").with(authentication(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].memKey").value("薄弱模块"));
    }

    @Test
    @DisplayName("PUT：改值成功，键不可变")
    void updateValue() throws Exception {
        insertMemory(ME, "目标考试", "旧值", 0);
        Long id = memoryMapper.selectActive(ME, 10).get(0).getId();

        mockMvc.perform(put("/me/agent-memory/" + id).with(authentication(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memKey\":\"随便传\",\"memValue\":\"省考\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memValue").value("省考"))
                .andExpect(jsonPath("$.data.memKey").value("目标考试"));
    }

    @Test
    @DisplayName("DELETE：逻辑删除（is_deleted=1），列表立即不再返回")
    void deleteIsLogical() throws Exception {
        insertMemory(ME, "目标考试", "国考", 0);
        Long id = memoryMapper.selectActive(ME, 10).get(0).getId();

        mockMvc.perform(delete("/me/agent-memory/" + id).with(authentication(me)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/me/agent-memory").with(authentication(me)))
                .andExpect(jsonPath("$.data.length()").value(0));
        // 行还在（逻辑删除），只是 is_deleted=1
        org.junit.jupiter.api.Assertions.assertEquals(1,
                memoryMapper.selectAnyById(id).getIsDeleted().intValue());
    }

    @Test
    @DisplayName("★ 删了再加同一个键：复活成功（不撞唯一键 500）")
    void reCreateAfterDeleteRevives() throws Exception {
        insertMemory(ME, "目标考试", "旧值", 1);

        mockMvc.perform(post("/me/agent-memory").with(authentication(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memKey\":\"目标考试\",\"memValue\":\"新值\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memValue").value("新值"));

        List<AiAgentMemory> active = memoryMapper.selectActive(ME, 10);
        org.junit.jupiter.api.Assertions.assertEquals(1, active.size(), "只应有一条（复活，不是新增）");
        org.junit.jupiter.api.Assertions.assertEquals("新值", active.get(0).getMemValue());
    }

    @Test
    @DisplayName("同键重复 POST（有效的那条）：409，提示改用修改")
    void duplicateKeyIs409() throws Exception {
        insertMemory(ME, "目标考试", "国考", 0);

        mockMvc.perform(post("/me/agent-memory").with(authentication(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memKey\":\"目标考试\",\"memValue\":\"省考\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @DisplayName("上限 20：第 21 条 → 409")
    void limitIs409() throws Exception {
        for (int i = 1; i <= 20; i++) {
            insertMemory(ME, "键" + i, "值" + i, 0);
        }

        mockMvc.perform(post("/me/agent-memory").with(authentication(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memKey\":\"第21条\",\"memValue\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @DisplayName("越权：改/删别人的记忆 → 403（不是 404）")
    void crossUserIs403() throws Exception {
        insertMemory(OTHER, "目标考试", "别人的", 0);
        Long otherId = memoryMapper.selectActive(OTHER, 10).get(0).getId();

        mockMvc.perform(put("/me/agent-memory/" + otherId).with(authentication(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memKey\":\"x\",\"memValue\":\"y\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/me/agent-memory/" + otherId).with(authentication(me)))
                .andExpect(status().isForbidden());
        // 别人的数据没被动
        org.junit.jupiter.api.Assertions.assertEquals("别人的",
                memoryMapper.selectAnyById(otherId).getMemValue());
    }

    @Test
    @DisplayName("校验：键超 32 字 / 值超 200 字 → 400")
    void validationIs400() throws Exception {
        mockMvc.perform(post("/me/agent-memory").with(authentication(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memKey\":\"" + "键".repeat(33) + "\",\"memValue\":\"v\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/me/agent-memory").with(authentication(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memKey\":\"k\",\"memValue\":\"" + "值".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("未登录 → 401")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/me/agent-memory")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("管理端只读：AUDITOR 可看某学生记忆；缺 userId → 400")
    void adminCanRead() throws Exception {
        insertMemory(OTHER, "目标考试", "国考", 0);
        LoginUser auditor = LoginUser.fromToken(1L, "audit", "AUDITOR");
        Authentication auditorAuth =
                new UsernamePasswordAuthenticationToken(auditor, null, auditor.getAuthorities());

        mockMvc.perform(get("/admin/ai/memory").with(authentication(auditorAuth))
                        .param("userId", String.valueOf(OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].memKey").value("目标考试"));

        mockMvc.perform(get("/admin/ai/memory").with(authentication(auditorAuth)))
                .andExpect(status().isBadRequest());
    }
}
