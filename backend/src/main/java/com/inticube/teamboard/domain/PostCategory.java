package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 게시판 카테고리 (공지사항, 자유게시판, 자료실 등) */
@Getter @Setter
public class PostCategory {
    private Long id;

    private String name;

    private String description;

    private int sortOrder;

    /** true 면 관리자만 글쓰기 가능 (공지사항) */
    private boolean adminOnlyWrite;

    private LocalDateTime createdAt = LocalDateTime.now();
}
