package com.examprep;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.dto.AccountExportVO;
import com.examprep.dto.DeactivateRequest;
import com.examprep.dto.MyStatsVO;
import com.examprep.entity.User;
import com.examprep.mapper.DownloadRecordMapper;
import com.examprep.mapper.FavoriteMapper;
import com.examprep.mapper.PracticeRecordMapper;
import com.examprep.mapper.QuestionNoteMapper;
import com.examprep.mapper.UserMapper;
import com.examprep.mapper.WrongBookMapper;
import com.examprep.service.AccountService;
import com.examprep.service.MyStatsService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 账号生命周期（数据导出 / 注销）单元测试
 *
 * <p>这两条链路是上架合规硬要求，且「注销」不可逆，因此用不依赖数据库的
 * 轻量单测把关键的拒绝分支与匿名化行为钉死（Mapper 全部 mock）。
 */
@DisplayName("账号合规：数据导出 / 注销")
class AccountServiceTest {

    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private MyStatsService myStatsService;
    private AccountService accountService;

    /**
     * 让 MyBatis-Plus 的 lambda 缓存（TableInfo）就绪：
     * 业务代码里用了 {@code Wrappers.<User>lambdaUpdate()}，纯单测环境下没有启动
     * MyBatis 容器，必须手动初始化，否则解析 {@code User::getStudentNo} 会抛
     * 「can not find lambda cache for this entity」。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
    }

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        myStatsService = mock(MyStatsService.class);

        // 真实 MyBatis-Plus 的 selectList 只会返回空列表而非 null，这里对齐真实行为
        PracticeRecordMapper practiceMapper = mock(PracticeRecordMapper.class);
        WrongBookMapper wrongBookMapper = mock(WrongBookMapper.class);
        FavoriteMapper favoriteMapper = mock(FavoriteMapper.class);
        DownloadRecordMapper downloadMapper = mock(DownloadRecordMapper.class);
        QuestionNoteMapper noteMapper = mock(QuestionNoteMapper.class);
        when(practiceMapper.selectList(any())).thenReturn(List.of());
        when(wrongBookMapper.selectList(any())).thenReturn(List.of());
        when(favoriteMapper.selectList(any())).thenReturn(List.of());
        when(downloadMapper.selectList(any())).thenReturn(List.of());
        when(noteMapper.selectList(any())).thenReturn(List.of());
        when(myStatsService.stats(anyLong())).thenReturn(new MyStatsVO());

        accountService = new AccountService(userMapper, practiceMapper, wrongBookMapper,
                favoriteMapper, downloadMapper, noteMapper, myStatsService, passwordEncoder);
    }

    private User existingUser() {
        User u = new User();
        u.setId(7L);
        u.setStudentNo("2021007");
        u.setNickname("小明");
        u.setRealName("张小明");
        u.setRole("STUDENT");
        u.setStatus(1);
        u.setPasswordHash("hash");
        return u;
    }

    private DeactivateRequest request(String password, String confirmText) {
        DeactivateRequest req = new DeactivateRequest();
        req.setPassword(password);
        req.setConfirmText(confirmText);
        return req;
    }

    @Test
    void 注销_确认语不符_直接拒绝且不碰数据库() {
        BizException ex = assertThrows(BizException.class,
                () -> accountService.deactivate(7L, request("pwd", "注销")));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verifyNoInteractions(userMapper);
    }

    @Test
    void 注销_用户不存在_报用户不存在() {
        when(userMapper.selectById(7L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> accountService.deactivate(7L, request("pwd", "注销账号")));
        assertEquals(ResultCode.USER_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void 注销_密码错误_拒绝且不改库() {
        when(userMapper.selectById(7L)).thenReturn(existingUser());
        when(passwordEncoder.matches("bad", "hash")).thenReturn(false);
        BizException ex = assertThrows(BizException.class,
                () -> accountService.deactivate(7L, request("bad", "注销账号")));
        assertEquals(ResultCode.OLD_PASSWORD_WRONG.getCode(), ex.getCode());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void 注销_成功_匿名化并逻辑删除() {
        when(userMapper.selectById(7L)).thenReturn(existingUser());
        when(passwordEncoder.matches("right", "hash")).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("randomHash");
        when(userMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        assertDoesNotThrow(() -> accountService.deactivate(7L, request("right", "注销账号")));

        // 必须回写且密码被随机化（旧密码不可再登录）
        verify(userMapper).update(isNull(), any(Wrapper.class));
        verify(passwordEncoder).encode(anyString());
    }

    @Test
    void 注销_回写影响行数为0_报服务器错误() {
        when(userMapper.selectById(7L)).thenReturn(existingUser());
        when(passwordEncoder.matches("right", "hash")).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("randomHash");
        when(userMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> accountService.deactivate(7L, request("right", "注销账号")));
        assertEquals(ResultCode.SERVER_ERROR.getCode(), ex.getCode());
    }

    @Test
    void 导出_用户不存在_报用户不存在() {
        when(userMapper.selectById(7L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> accountService.export(7L));
        assertEquals(ResultCode.USER_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void 导出_成功_打包账号资料且为结构完整() {
        when(userMapper.selectById(7L)).thenReturn(existingUser());

        AccountExportVO vo = accountService.export(7L);

        assertNotNull(vo);
        assertEquals("1.0", vo.getFormatVersion());
        assertNotNull(vo.getExportedAt());
        assertEquals(7L, vo.getProfile().getId());
        assertEquals("2021007", vo.getProfile().getStudentNo());
        assertNotNull(vo.getStats());
        // 空数据也必须是空列表而非 null，保证前端可直接遍历
        assertEquals(List.of(), vo.getWrongBook());
        assertEquals(List.of(), vo.getPracticeRecords());
        assertEquals(List.of(), vo.getFavorites());
        assertEquals(List.of(), vo.getDownloads());
        assertEquals(List.of(), vo.getNotes());
    }

    @Test
    void 导出_统计委托给统计服务() {
        when(userMapper.selectById(7L)).thenReturn(existingUser());
        accountService.export(7L);
        verify(myStatsService).stats(7L);
    }
}
