package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

/** 업무 유형 (Jira 이슈 유형 + SLA 목표). 응답/처리 시간은 시간 단위, null 이면 SLA 없음 */
@Getter @Setter
public class BoardType {
    private Long id;

    private Board board;

    private String name;

    private String color = "#0061F2";

    /** 아이콘 키 (프론트 아이콘 이름) */
    private String icon;

    private Integer responseHours;

    private Integer resolveHours;

    private int sortOrder;

    /** 새 업무 기본 유형 */
    private boolean defaultType;
}
