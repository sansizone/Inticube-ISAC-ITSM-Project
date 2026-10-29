package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.util.Set;

/** 워크스페이스의 보기 탭 (보기 유형 + 필터·정렬·표시 컬럼 설정 JSON) */
@Getter @Setter
public class BoardView {
    public static final Set<String> TYPES = Set.of("list", "table", "kanban", "timeline", "dashboard", "duties", "roadmap", "wiki", "form");

    private Long id;

    private Long boardId;

    private String name;

    private String type;

    private String config;

    private int sortOrder;
}
