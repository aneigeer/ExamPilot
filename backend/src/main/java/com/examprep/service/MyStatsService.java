package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.dto.MyStatsVO;
import com.examprep.entity.DownloadRecord;
import com.examprep.entity.Favorite;
import com.examprep.entity.Notification;
import com.examprep.entity.PracticeRecord;
import com.examprep.entity.Resource;
import com.examprep.entity.WrongBook;
import com.examprep.mapper.DownloadRecordMapper;
import com.examprep.mapper.FavoriteMapper;
import com.examprep.mapper.NotificationMapper;
import com.examprep.mapper.PracticeRecordMapper;
import com.examprep.mapper.ResourceMapper;
import com.examprep.mapper.WrongBookMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 「我的」页面统计
 */
@Service
@RequiredArgsConstructor
public class MyStatsService {

    private final PracticeRecordMapper practiceRecordMapper;
    private final WrongBookMapper wrongBookMapper;
    private final FavoriteMapper favoriteMapper;
    private final DownloadRecordMapper downloadRecordMapper;
    private final ResourceMapper resourceMapper;
    private final NotificationMapper notificationMapper;

    public MyStatsVO stats(Long userId) {
        MyStatsVO vo = new MyStatsVO();

        java.util.List<PracticeRecord> practices = practiceRecordMapper.selectList(
                Wrappers.<PracticeRecord>lambdaQuery().eq(PracticeRecord::getUserId, userId));
        vo.setPracticeCount((long) practices.size());
        long answered = practices.stream()
                .mapToLong(p -> p.getAnsweredCount() == null ? 0 : p.getAnsweredCount()).sum();
        long correct = practices.stream()
                .mapToLong(p -> p.getCorrectCount() == null ? 0 : p.getCorrectCount()).sum();
        vo.setTotalAnswered(answered);
        vo.setTotalCorrect(correct);
        vo.setAccuracy(answered == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(correct).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(answered), 1, RoundingMode.HALF_UP));

        vo.setWrongBookCount(wrongBookMapper.selectCount(Wrappers.<WrongBook>lambdaQuery()
                .eq(WrongBook::getUserId, userId)
                .eq(WrongBook::getMastered, 0)));

        vo.setFavoriteResourceCount(favoriteMapper.selectCount(Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getTargetType, "RESOURCE")));
        vo.setFavoriteQuestionCount(favoriteMapper.selectCount(Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getTargetType, "QUESTION")));

        vo.setDownloadCount(downloadRecordMapper.selectCount(Wrappers.<DownloadRecord>lambdaQuery()
                .eq(DownloadRecord::getUserId, userId)));

        vo.setUploadCount(resourceMapper.selectCount(Wrappers.<Resource>lambdaQuery()
                .eq(Resource::getUploaderId, userId)));

        vo.setUnreadNoticeCount(notificationMapper.selectCount(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, userId)
                .isNull(Notification::getReadAt)));

        return vo;
    }
}
