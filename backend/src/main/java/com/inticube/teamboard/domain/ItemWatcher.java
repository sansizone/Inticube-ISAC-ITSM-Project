package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

/** 업무 감시자 — 상태·담당자·댓글 변경 시 알림을 받는다 */
@Getter @Setter
public class ItemWatcher {
    private Long id;

    private Long itemId;

    private AppUser user;
}
