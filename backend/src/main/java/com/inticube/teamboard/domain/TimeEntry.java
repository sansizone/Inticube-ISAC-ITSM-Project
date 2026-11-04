package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 시간 기록 (Redmine 타임로그) */
@Getter @Setter
public class TimeEntry {
    private Long id;

    private Long itemId;

    private Long boardId;

    private AppUser user;

    private LocalDate workDate;

    private BigDecimal hours;

    private String comment;

    private LocalDateTime createdAt = LocalDateTime.now();

    /** 논리 삭제 */
    private LocalDateTime deletedAt;
}
