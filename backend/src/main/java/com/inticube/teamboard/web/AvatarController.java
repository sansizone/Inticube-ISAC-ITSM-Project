package com.inticube.teamboard.web;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.service.AvatarService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** 프로필 사진: 본인 등록·삭제, 로그인 사용자 누구나 조회, 관리자 삭제(/api/admin/** 는 ADMIN 전용) */
@RestController
@RequiredArgsConstructor
public class AvatarController {
    private final AvatarService avatars;

    @GetMapping("/api/users/{id}/avatar")
    public ResponseEntity<Resource> get(@PathVariable Long id) {
        // 주소에 ?v=버전 이 붙으므로 오래 캐시해도 변경 시 새로 받음
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(avatars.file(id)));
    }

    @PostMapping(value = "/api/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Long> upload(@AuthenticationPrincipal UserPrincipal me, @RequestParam("file") MultipartFile file) {
        if (me == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        return Map.of("avatar", avatars.store(me.id(), file));
    }

    @DeleteMapping("/api/me/avatar")
    public Map<String, Long> deleteMine(@AuthenticationPrincipal UserPrincipal me) {
        if (me == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        avatars.delete(me.id());
        Map<String, Long> r = new HashMap<>(); r.put("avatar", null); return r;
    }

    @DeleteMapping("/api/admin/users/{id}/avatar")
    public Map<String, Long> deleteByAdmin(@PathVariable Long id) {
        avatars.delete(id);
        Map<String, Long> r = new HashMap<>(); r.put("avatar", null); return r;
    }
}
