package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 워크스페이스(보드)별 담당업무 트리 노드. 깊이 제한 없음.
 * 부모는 ID 로만 참조(자기참조 FK 없음) — 하위 트리 일괄 삭제를 단순하게 하기 위함.
 */
@Getter @Setter
public class BoardDuty {
    private Long id;

    private Board board;

    private Long parentId;

    private String name;

    private int sortOrder;

    /** 정 담당자 — 업무에 이 담당업무를 지정하고 담당자가 비어 있으면 자동 배정 */
    private AppUser primaryUser;

    /** 부 담당자 */
    private AppUser secondaryUser;

    private LocalDateTime createdAt = LocalDateTime.now();
}
