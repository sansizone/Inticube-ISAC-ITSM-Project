package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
public class PostComment {
    private Long id;

    private Post post;

    /** 답글인 경우 상위 댓글 ID (1단계까지만) */
    private Long parentId;

    private AppUser author;

    private String content;

    /** 답글이 달린 댓글은 내용만 지우고 자리 유지 */
    private boolean deleted;

    private LocalDateTime createdAt = LocalDateTime.now();

    /** 댓글을 수정한 시각 (수정 안 했으면 null) */
    private LocalDateTime updatedAt;
}
