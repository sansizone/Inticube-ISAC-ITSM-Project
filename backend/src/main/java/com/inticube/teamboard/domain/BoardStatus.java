package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

/** 보드별 상태 라벨 (예: 대기/진행중/막힘/완료) */
@Getter @Setter
public class BoardStatus {
    private Long id;

    private Board board;

    private String label;

    private String color;

    private int sortOrder;

    /** 완료로 간주되는 상태 여부 (진행률·지연 계산에 사용) */
    private boolean done;
}
