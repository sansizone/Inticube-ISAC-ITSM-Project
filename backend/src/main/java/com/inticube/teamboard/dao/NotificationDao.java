package com.inticube.teamboard.dao;

import com.inticube.teamboard.domain.Notification;
import com.inticube.teamboard.domain.NotificationType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface NotificationDao {
    Notification selectById(@Param("id") Long id);
    List<Notification> selectRecent(@Param("userId") Long userId);

    List<Notification> selectRecentTalk(@Param("userId") Long userId);
    long selectUnreadCount(@Param("userId") Long userId);
    boolean selectExistsRecent(@Param("userId") Long userId, @Param("itemId") Long itemId, @Param("type") NotificationType type, @Param("after") LocalDateTime after);
    int insert(Notification n);
    int updateRead(@Param("id") Long id);
    int updateAllRead(@Param("userId") Long userId);
    int deleteByItems(@Param("ids") Collection<Long> ids);
    int deleteByPost(@Param("postId") Long postId);
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
