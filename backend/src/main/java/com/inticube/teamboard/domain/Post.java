package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
public class Post {
    private Long id;

    private PostCategory category;

    private String title;

    /** 마크다운 본문. Oracle=CLOB, PostgreSQL=TEXT */
    private String content;

    /** 검색용 본문 요약 (마크다운 기호 제거, 소문자, 앞 1000자). CLOB 직접 LIKE 검색을 피하기 위함 */
    private String searchText;

    private AppUser author;

    private boolean pinned;

    private long viewCount;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    public void setContent(String content) {
        this.content = content;
        String plain = content == null ? "" : content
                .replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("[#>*`~\\[\\]()_|-]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();
        this.searchText = plain.length() > 1000 ? plain.substring(0, 1000) : plain;
    }
}
