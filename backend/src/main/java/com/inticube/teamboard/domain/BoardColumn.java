package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.util.Set;

/**
 * 워크스페이스별 사용자 정의 컬럼 (monday.com 의 컬럼에 해당).
 * 값은 {@link ItemValue} 에 문자열로 저장한다.
 */
@Getter @Setter
public class BoardColumn {
    /** TEXT 한 줄 / LONG_TEXT 여러 줄 / NUMBER / DATE(yyyy-MM-dd) / DATETIME(yyyy-MM-dd HH:mm) / PEOPLE(이름, 쉼표 구분) / DROPDOWN / LINK / RELATION(다른 워크스페이스 업무 연결, 값 = 업무 ID 쉼표 구분) */
    public static final Set<String> TYPES = Set.of("TEXT", "LONG_TEXT", "NUMBER", "DATE", "DATETIME", "PEOPLE", "DROPDOWN", "LINK", "RELATION");
    /** ITEM = 업무용 컬럼, SUBITEM = 하위 업무용 컬럼 */
    public static final Set<String> SCOPES = Set.of("ITEM", "SUBITEM");

    private Long id;

    private Board board;

    private String name;

    private String type = "TEXT";

    private String scope = "ITEM";

    private int sortOrder;

    /** 목록/표 화면에 표시 여부 */
    private boolean visible = true;

    /** RELATION: 연결 대상 워크스페이스(보드) */
    private Long refBoardId;

    /** RELATION: 대상에서 표시할 컬럼 (null = 업무명) */
    private Long refColumnId;
}
