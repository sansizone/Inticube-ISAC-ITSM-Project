package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** 버전·마일스톤 (Redmine 버전 / OpenProject 마일스톤). 로드맵 보기의 단위 */
@Getter @Setter
public class BoardVersion {
    private Long id;

    private Board board;

    private String name;

    private String description;

    private LocalDate startDate;

    private LocalDate dueDate;

    /** OPEN / CLOSED */
    private String status = "OPEN";

    private int sortOrder;
}
