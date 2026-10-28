package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
public class Board {
    private Long id;

    private String name;

    private String description;

    /** true = 모든 사용자에게 공개, false = 멤버만. null 은 v2.0 이전 데이터(기동 시 채움) */
    private Boolean publicBoard;

    /** 기본 컬럼(상태·담당자·마감일 등) 이름/숨김과 전체 컬럼 순서 (JSON) */
    private String columnConfig;

    /** 업무 키 접두어 (예: REQ → REQ-12). 기동 시 비어 있으면 자동 생성 */
    private String keyPrefix;

    /** 다음 업무 번호 */
    private Integer nextSeq;

    /** SLA 계산을 영업시간(평일 09~18시) 기준으로 */
    private Boolean slaBusinessHours;

    public boolean isPublic() { return Boolean.TRUE.equals(publicBoard); }
    public boolean isSlaBusiness() { return Boolean.TRUE.equals(slaBusinessHours); }

    private AppUser owner;

    private LocalDateTime createdAt = LocalDateTime.now();
}
