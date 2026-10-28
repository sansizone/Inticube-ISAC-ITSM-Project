package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
public class Notification {
    private Long id;

    private AppUser recipient;

    private NotificationType type;

    private String message;

    private Long boardId;

    private Long itemId;

    /** 게시글 관련 알림인 경우 */
    private Long postId;

    private boolean read;

    private LocalDateTime createdAt = LocalDateTime.now();
}
