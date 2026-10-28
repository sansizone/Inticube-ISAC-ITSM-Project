package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Setter
public class Item {
    private Long id;

    private Board board;

    private BoardGroup group;

    private String name;

    /** 업무 설명 (마크다운). null 대신 빈 문자열 유지 */
    private String description = "";

    private BoardStatus status;

    /** 담당업무 (트리의 아무 단계) */
    private BoardDuty duty;

    /** 하위 업무면 상위 업무 (같은 그룹) */
    private Item parent;

    /** 외부 시스템 원본 ID (예: monday.com Item ID) */
    private String externalId;

    private AppUser assignee;

    private Priority priority;

    private LocalDate startDate;

    private LocalDate dueDate;

    private int sortOrder;

    // ---------- v3.0 유지보수 기능 ----------
    /** 워크스페이스 안 업무 번호 (키 = 접두어-번호) */
    private Integer keyNum;

    /** 업무 유형 (요청·장애·변경…) */
    private BoardType type;

    /** 버전·마일스톤 */
    private BoardVersion version;

    /** 예상 소요시간(시간) */
    private java.math.BigDecimal estimateHours;

    /** 진척률 0~100 */
    private Integer progress;

    /** 최초 응답 시각 (첫 상태 변경 또는 등록자 외 첫 댓글) */
    private LocalDateTime respondedAt;

    /** 완료 상태가 된 시각 (완료 아닌 상태로 되돌리면 비움) */
    private LocalDateTime resolvedAt;

    /** 반복 규칙: DAILY:1 / WEEKLY:2 / MONTHLY:1 / YEARLY:1 (완료 처리 시 다음 업무 자동 생성) */
    private String recurRule;

    private AppUser createdBy;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();
}
