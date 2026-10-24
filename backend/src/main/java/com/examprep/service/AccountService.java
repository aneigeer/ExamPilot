package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.dto.AccountExportVO;
import com.examprep.dto.DeactivateRequest;
import com.examprep.entity.DownloadRecord;
import com.examprep.entity.Favorite;
import com.examprep.entity.PracticeRecord;
import com.examprep.entity.QuestionNote;
import com.examprep.entity.User;
import com.examprep.entity.WrongBook;
import com.examprep.mapper.DownloadRecordMapper;
import com.examprep.mapper.FavoriteMapper;
import com.examprep.mapper.PracticeRecordMapper;
import com.examprep.mapper.QuestionNoteMapper;
import com.examprep.mapper.UserMapper;
import com.examprep.mapper.WrongBookMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 账号生命周期服务：个人数据导出 + 账号注销
 *
 * <p>合规要点（个人信息保护法）：
 * <ul>
 *   <li>可携带权：用户可一次性导出自己的数据；</li>
 *   <li>删除权：用户可自助注销，注销后个人身份信息即刻匿名化，账号不可再登录。</li>
 * </ul>
 *
 * <p>注销采用「匿名化 + 逻辑删除」而非物理删除：保留去标识化的历史答题流水用于统计与
 * 审计，但不含任何可关联到个人的身份字段（学号、姓名、密码全部清除/改写）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private static final String DEACTIVATE_CONFIRM = "注销账号";
    private static final String DELETED_NICKNAME = "已注销用户";

    private final UserMapper userMapper;
    private final PracticeRecordMapper practiceRecordMapper;
    private final WrongBookMapper wrongBookMapper;
    private final FavoriteMapper favoriteMapper;
    private final DownloadRecordMapper downloadRecordMapper;
    private final QuestionNoteMapper questionNoteMapper;
    private final MyStatsService myStatsService;
    private final PasswordEncoder passwordEncoder;

    /** 导出当前登录用户的个人数据（不含密码等凭证） */
    public AccountExportVO export(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }

        AccountExportVO vo = new AccountExportVO();
        vo.setExportedAt(LocalDateTime.now());

        AccountExportVO.Profile profile = new AccountExportVO.Profile();
        profile.setId(user.getId());
        profile.setStudentNo(user.getStudentNo());
        profile.setNickname(user.getNickname());
        profile.setRealName(user.getRealName());
        profile.setRole(user.getRole());
        profile.setSchoolId(user.getSchoolId());
        profile.setCollegeId(user.getCollegeId());
        profile.setMajorId(user.getMajorId());
        profile.setGrade(user.getGrade());
        profile.setCreatedAt(user.getCreatedAt());
        profile.setLastLoginAt(user.getLastLoginAt());
        vo.setProfile(profile);

        vo.setStats(myStatsService.stats(userId));

        vo.setWrongBook(wrongBookMapper.selectList(Wrappers.<WrongBook>lambdaQuery()
                .eq(WrongBook::getUserId, userId)
                .orderByDesc(WrongBook::getId)));
        vo.setPracticeRecords(practiceRecordMapper.selectList(Wrappers.<PracticeRecord>lambdaQuery()
                .eq(PracticeRecord::getUserId, userId)
                .orderByDesc(PracticeRecord::getId)));
        vo.setFavorites(favoriteMapper.selectList(Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId)
                .orderByDesc(Favorite::getId)));
        vo.setDownloads(downloadRecordMapper.selectList(Wrappers.<DownloadRecord>lambdaQuery()
                .eq(DownloadRecord::getUserId, userId)
                .orderByDesc(DownloadRecord::getId)));
        vo.setNotes(questionNoteMapper.selectList(Wrappers.<QuestionNote>lambdaQuery()
                .eq(QuestionNote::getUserId, userId)
                .orderByDesc(QuestionNote::getId)));

        log.info("[账号] 用户 {} 导出个人数据", userId);
        return vo;
    }

    /**
     * 注销账号：校验密码与确认语 → 匿名化个人标识 → 逻辑删除。
     * 若该用户此前已注销（查不到有效记录），返回「用户不存在」。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deactivate(Long userId, DeactivateRequest req) {
        if (!Objects.equals(req.getConfirmText(), DEACTIVATE_CONFIRM)) {
            throw new BizException(ResultCode.PARAM_ERROR, "请在确认语中填写「" + DEACTIVATE_CONFIRM + "」以确认注销");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException(ResultCode.OLD_PASSWORD_WRONG, "密码不正确，无法注销");
        }

        // 匿名化：释放原学号（改为 deleted_<id>，仍全局唯一且不可复活），抹去姓名与昵称，
        // 密码改写为随机值（即便未来被误启用也无法用旧密码登录）
        String anonymizedNo = "deleted_" + userId;
        String randomHash = passwordEncoder.encode(UUID.randomUUID().toString());
        int updated = userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getStudentNo, anonymizedNo)
                .set(User::getNickname, DELETED_NICKNAME)
                .set(User::getRealName, null)
                .set(User::getPasswordHash, randomHash)
                .set(User::getStatus, 0)
                .set(User::getIsDeleted, 1));
        if (updated == 0) {
            throw new BizException(ResultCode.SERVER_ERROR, "注销失败，请稍后重试");
        }
        log.info("[账号] 用户 {} 已注销（匿名化为 {}）", userId, anonymizedNo);
    }
}
