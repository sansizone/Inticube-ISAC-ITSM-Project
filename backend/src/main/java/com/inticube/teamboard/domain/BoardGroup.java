package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class BoardGroup {
    private Long id;

    private Board board;

    private String name;

    private String color;

    private int sortOrder;
}
