package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
public class PostAttachment {
    private Long id;

    private Post post;

    private String originalName;

    private String contentType;

    private long fileSize;

    private String storedName;

    private AppUser uploader;

    private LocalDateTime createdAt = LocalDateTime.now();
}
