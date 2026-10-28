package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

/** 업무 × 사용자 정의 컬럼 값 (원본 문자열 그대로 보존) */
@Getter @Setter
public class ItemValue {
    private Long id;

    private Long itemId;

    private Long columnId;

    /** Oracle=CLOB, PostgreSQL=TEXT */
    private String value = "";
}
