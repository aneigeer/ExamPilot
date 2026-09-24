package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.dto.MasteryVO;
import com.examprep.dto.MyStatsVO;
import com.examprep.dto.PracticeTrendVO;
import com.examprep.entity.DownloadRecord;
import com.examprep.entity.Favorite;
import com.examprep.entity.Notification;
import com.examprep.entity.PracticeRecord;
import com.examprep.entity.Resource;
import com.examprep.entity.Subject;
import com.examprep.entity.WrongBook;
import com.examprep.mapper.AnswerDetailMapper;
import com.examprep.mapper.DownloadRecordMapper;
import com.examprep.mapper.FavoriteMapper;
import com.examprep.mapper.NotificationMapper;
import com.examprep.mapper.PracticeRecordMapper;
import com.examprep.mapper.ResourceMapper;
import com.examprep.mapper.SubjectMapper;
import com.examprep.mapper.WrongBookMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「我的」页面统计
 */
@Service
@RequiredArgsConstructor
public class MyStatsService {

    private final PracticeRecordMapper practiceRecordMapper;
    private final AnswerDetailMapper answerDetailMapper;
    private final SubjectMapper subjectMapper;
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

    /** 知识点掌握度（P0-A「简单统计」要求项） */
    public MasteryVO mastery(Long userId, Long subjectId) {
        Subject subject = subjectId == null ? null : subjectMapper.selectById(subjectId);
        MasteryVO vo = new MasteryVO();
        vo.setSubjectName(subject == null ? "全部科目" : subject.getName());

        List<MasteryVO.Item> items = new ArrayList<>();
        for (Map<String, Object> row : answerDetailMapper.selectMastery(userId, subjectId)) {
            MasteryVO.Item item = new MasteryVO.Item();
            item.setKnowledgeId(((Number) row.get("knowledgeId")).longValue());
            item.setKnowledgeName((String) row.get("knowledgeName"));
            long answered = ((Number) row.get("answered")).longValue();
            long correct = row.get("correct") == null ? 0 : ((Number) row.get("correct")).longValue();
            item.setAnswered(answered);
            item.setCorrect(correct);
            item.setMasteryRate(answered == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(correct).multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(answered), 1, RoundingMode.HALF_UP));
            items.add(item);
        }
        vo.setItems(items);
        return vo;
    }

    /** 刷题趋势（P0-B 统计图表），逐日补零 */
    public PracticeTrendVO trend(Long userId, Integer days) {
        int d = days == null ? 7 : Math.min(Math.max(days, 1), 90);
        LocalDate since = LocalDate.now().minusDays(d - 1L);

        Map<String, long[]> byDay = new LinkedHashMap<>();
        for (Map<String, Object> row : answerDetailMapper.selectDailyTrend(userId, since.atStartOfDay())) {
            String day = String.valueOf(row.get("day"));
            long answered = ((Number) row.get("answered")).longValue();
            long correct = row.get("correct") == null ? 0 : ((Number) row.get("correct")).longValue();
            byDay.put(day, new long[]{answered, correct});
        }

        List<PracticeTrendVO.Item> items = new ArrayList<>();
        for (int i = 0; i < d; i++) {
            String day = since.plusDays(i).toString();
            long[] arr = byDay.get(day);
            items.add(new PracticeTrendVO.Item(day,
                    arr == null ? 0L : arr[0], arr == null ? 0L : arr[1]));
        }

        PracticeTrendVO vo = new PracticeTrendVO();
        vo.setDays(d);
        vo.setItems(items);
        return vo;
    }
}
