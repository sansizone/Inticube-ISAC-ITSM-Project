package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
public class Attachment {
    private Long id;

    private Item item;

    private String originalName;

    private String contentType;

    private long fileSize;

    /** 디스크 저장 파일명 (UUID) */
    private String storedName;

    private AppUser uploader;

    private LocalDateTime createdAt = LocalDateTime.now();
}
