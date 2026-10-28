package com.inticube.teamboard.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 사용자. Oracle 예약어(USER) 회피를 위해 APP_USER 테이블 사용 */
@Getter @Setter
public class AppUser {
    private Long id;

    private String username;

    private String passwordHash;

    private String name;

    private String department;

    private Role role = Role.MEMBER;

    private boolean active = true;

    private boolean mustChangePassword = true;

    private LocalDateTime createdAt = LocalDateTime.now();

    // ---------- 2단계 인증 (Google Authenticator 등 TOTP) ----------
    /** Base32 비밀키. 설정 완료 전에는 임시값, 완료되면 totpEnabled=true */
    private String totpSecret;

    private boolean totpEnabled;

    /** 복구 코드 해시 (쉼표 구분). 하나 쓰면 지움 */
    private String recoveryCodes;
}
