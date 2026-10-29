package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 워크스페이스 그룹 (그룹 > 워크스페이스). 그룹 멤버만 그 안의 워크스페이스를 볼 수 있다 */
@Getter @Setter
public class WsGroup {
    private Long id;
    private String name;
    private String description;
    private int sortOrder;
    private Long createdBy;
    private LocalDateTime createdAt = LocalDateTime.now();
}
