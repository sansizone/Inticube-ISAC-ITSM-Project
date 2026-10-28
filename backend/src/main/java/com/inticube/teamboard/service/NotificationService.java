package com.inticube.teamboard.service;

import com.inticube.teamboard.dao.ItemDao;
import com.inticube.teamboard.dao.NotificationDao;
import com.inticube.teamboard.domain.AppUser;
import com.inticube.teamboard.domain.Item;
import com.inticube.teamboard.domain.Notification;
import com.inticube.teamboard.domain.NotificationType;
import com.inticube.teamboard.web.ApiException;
import com.inticube.teamboard.web.dto.Dtos.NotificationDto;
import com.inticube.teamboard.web.dto.Dtos.NotificationList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {
    private final NotificationDao notiDao;
    private final ItemDao itemDao;

    /** 본인이 한 행동에 대해서는 알림을 만들지 않음 */
    public void insertNotify(AppUser recipient, AppUser actor, NotificationType type, String message, Item item) {
        if (recipient == null || !recipient.isActive()) return;
        if (actor != null && Objects.equals(recipient.getId(), actor.getId())) return;
        Notification n = new Notification();
        n.setRecipient(recipient);
        n.setType(type);
        n.setMessage(message.length() > 500 ? message.substring(0, 497) + "..." : message);
        if (item != null) {
            n.setItemId(item.getId());
            n.setBoardId(item.getBoard().getId());
        }
        notiDao.insert(n);
    }

    public void insertNotifyPost(AppUser recipient, AppUser actor, NotificationType type, String message, com.inticube.teamboard.domain.Post post) {
        if (recipient == null || !recipient.isActive()) return;
        if (actor != null && Objects.equals(recipient.getId(), actor.getId())) return;
        Notification n = new Notification();
        n.setRecipient(recipient);
        n.setType(type);
        n.setMessage(message.length() > 500 ? message.substring(0, 497) + "..." : message);
        n.setPostId(post.getId());
        notiDao.insert(n);
    }

    @Transactional(readOnly = true)
    public NotificationList getList(Long userId) {
        return new NotificationList(notiDao.selectUnreadCount(userId),
                notiDao.selectRecent(userId).stream().map(NotificationDto::of).toList());
    }

    public void updateRead(Long id, Long userId) {
        Notification n = notiDao.selectById(id);
        if (n == null) throw ApiException.notFound("알림");
        if (!n.getRecipient().getId().equals(userId)) throw ApiException.forbidden();
        notiDao.updateRead(id);
    }

    public void updateAllRead(Long userId) {
        notiDao.updateAllRead(userId);
    }

    /** 매일 08:30 마감 임박(오늘/내일) 미완료 업무 알림 */
    @Scheduled(cron = "${teamboard.due-reminder-cron:0 30 8 * * *}")
    public void batchDueReminder() {
        LocalDate today = LocalDate.now();
        LocalDateTime since = LocalDateTime.now().minusHours(20);
        int count = 0;
        for (Item i : itemDao.selectOpenDueBetween(today, today.plusDays(1))) {
            if (notiDao.selectExistsRecent(i.getAssignee().getId(), i.getId(), NotificationType.DUE_SOON, since)) continue;
            String when = i.getDueDate().equals(today) ? "오늘" : "내일";
            insertNotify(i.getAssignee(), null, NotificationType.DUE_SOON,
                    "[" + i.getBoard().getName() + "] '" + i.getName() + "' 마감이 " + when + "입니다.", i);
            count++;
        }
        log.info("마감 임박 알림 {}건 발송", count);
    }

    /** 매일 03:00 90일 지난 알림 정리 */
    @Scheduled(cron = "0 0 3 * * *")
    public void batchCleanup() {
        notiDao.deleteOlderThan(LocalDateTime.now().minusDays(90));
    }
}
