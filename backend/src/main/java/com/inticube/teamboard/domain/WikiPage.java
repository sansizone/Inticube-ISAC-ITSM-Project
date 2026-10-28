package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 워크스페이스 위키 페이지 (마크다운, 트리 구조) */
@Getter @Setter
public class WikiPage {
    private Long id;

    private Long boardId;

    private Long parentId;

    private String title;

    private String content = "";

    private int sortOrder;

    private AppUser createdBy;

    private AppUser updatedBy;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();
}
