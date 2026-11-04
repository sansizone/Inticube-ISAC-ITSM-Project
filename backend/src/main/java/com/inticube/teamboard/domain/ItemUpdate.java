package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 아이템 업데이트(댓글) 및 시스템 활동 로그 */
@Getter @Setter
public class ItemUpdate {
    private Long id;

    private Item item;

    private AppUser author;

    /** Oracle=CLOB, PostgreSQL=TEXT 로 매핑 */
    private String content;

    /** 답글이면 원 댓글 ID */
    private Long parentId;

    /** 외부에서 가져온 댓글의 원래 작성자 이름 (계정과 별개로 원본 보존) */
    private String authorName;

    /** 외부 시스템 원본 ID (예: monday.com Post ID) */
    private String externalId;

    /** true 면 상태 변경 등 시스템이 남긴 활동 기록 */
    private boolean system;

    private LocalDateTime createdAt = LocalDateTime.now();

    /** 댓글을 수정한 시각 (수정 안 했으면 null → 화면에 '수정됨' 표시 안 함) */
    private LocalDateTime updatedAt;

    /** 논리 삭제 (내용은 보존, 화면에는 '삭제된 댓글'로 표시) */
    private LocalDateTime deletedAt;
    private Long deletedBy;
}
