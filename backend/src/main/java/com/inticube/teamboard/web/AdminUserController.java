package com.inticube.teamboard.web;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.service.UserService;
import com.inticube.teamboard.web.dto.Dtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 사용자 관리 (ADMIN 전용 - SecurityConfig 에서 /api/admin/** 제한) */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {
    private final UserService userService;
    private final com.inticube.teamboard.service.TotpService totp;

    @GetMapping
    public List<UserAdminDto> list() { return userService.getAllUsers(); }

    @PostMapping
    public TempPasswordResponse create(@Valid @RequestBody UserCreateRequest req) { return userService.insertUser(req); }

    @PutMapping("/{id}")
    public UserAdminDto update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest req,
                               @AuthenticationPrincipal UserPrincipal me) {
        return userService.updateUser(id, req, me);
    }

    @PostMapping("/bulk-activate")
    public List<BulkActivateResult> bulkActivate(@RequestBody BulkActivateRequest req) { return userService.batchActivateUsers(req); }

    /** 기기 분실 등: 2단계 인증 초기화 */
    @PostMapping("/{id}/mfa-reset")
    public UserAdminDto mfaReset(@PathVariable Long id) { totp.updateReset(id); return UserAdminDto.of(userService.getUser(id)); }

    @PostMapping("/{id}/reset-password")
    public TempPasswordResponse reset(@PathVariable Long id) { return userService.updatePasswordReset(id); }
}
