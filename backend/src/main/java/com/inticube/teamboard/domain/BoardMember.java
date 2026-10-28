package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

/** 워크스페이스(보드) 멤버. OWNER 는 설정·컬럼·멤버 관리 가능 */
@Getter @Setter
public class BoardMember {
    private Long id;

    private Long boardId;

    private AppUser user;

    private String role = "MEMBER";
}
