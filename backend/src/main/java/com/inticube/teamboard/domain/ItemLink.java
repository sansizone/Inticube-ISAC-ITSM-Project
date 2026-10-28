package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.util.Set;

/** 업무 간 관계. from → to 방향: BLOCKS(차단), RELATES(관련), DUPLICATES(중복), CAUSES(원인) */
@Getter @Setter
public class ItemLink {
    public static final Set<String> TYPES = Set.of("BLOCKS", "RELATES", "DUPLICATES", "CAUSES");

    private Long id;

    private Long fromItemId;

    private Long toItemId;

    private String type;

    private Long createdBy;
}
