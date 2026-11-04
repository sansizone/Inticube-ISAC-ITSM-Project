package com.inticube.teamboard.web;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.domain.AppUser;
import com.inticube.teamboard.service.LoginAttemptService;
import com.inticube.teamboard.service.UserService;
import com.inticube.teamboard.web.dto.Dtos.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextRepository;
    private final CookieCsrfTokenRepository csrfRepo;
    private final LoginAttemptService attempts;
    private final UserService userService;
    private final com.inticube.teamboard.service.TotpService totp;
    private final com.inticube.teamboard.service.AuditService audit;
    private static final String MFA_PENDING = "MFA_PENDING_USER";
    private static final String MFA_PENDING_AT = "MFA_PENDING_AT";

    /** SPA 최초 진입 시 XSRF-TOKEN 쿠키 발급용 */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName());
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        String username = req.username().trim();
        if (attempts.isLocked(username))
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "로그인 실패가 반복되어 잠시 잠겼습니다. 잠시 후 다시 시도하세요.");
        Authentication auth;
        try {
            auth = authManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, req.password()));
        } catch (DisabledException e) {
            audit.recordAs(null, null, "LOGIN_FAIL", null, null, "USER", null, username, "비활성 계정으로 로그인 시도");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "비활성화된 계정입니다. 관리자에게 문의하세요.");
        } catch (AuthenticationException e) {
            attempts.failed(username);
            audit.recordAs(null, null, "LOGIN_FAIL", null, null, "USER", null, username, "아이디 또는 비밀번호 불일치");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        attempts.succeeded(username);
        UserPrincipal principal = ((UserPrincipal) auth.getPrincipal()).withoutPassword();
        AppUser u = userService.getUser(principal.id());
        if (u.isTotpEnabled()) {
            // 2단계: 세션에 '비밀번호 통과' 표시만 남기고, 인증은 OTP 확인 후에
            HttpSession s = request.getSession(true);
            s.setAttribute(MFA_PENDING, u.getId());
            s.setAttribute(MFA_PENDING_AT, System.currentTimeMillis());
            return new LoginResponse(true, null);
        }
        return new LoginResponse(false, finishLogin(u, request, response));
    }

    /** 2단계: OTP 앱 코드 또는 복구 코드 */
    @PostMapping("/mfa")
    public LoginResponse mfa(@Valid @RequestBody MfaCodeRequest req, HttpServletRequest request, HttpServletResponse response) {
        HttpSession s = request.getSession(false);
        Long uid = s == null ? null : (Long) s.getAttribute(MFA_PENDING);
        Long at = s == null ? null : (Long) s.getAttribute(MFA_PENDING_AT);
        if (uid == null || at == null || System.currentTimeMillis() - at > 5 * 60_000L)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "인증 시간이 지났습니다. 처음부터 다시 로그인하세요.");
        AppUser u = userService.getUser(uid);
        String key = "mfa:" + u.getUsername();
        if (attempts.isLocked(key)) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "인증 코드 실패가 반복되어 잠시 잠겼습니다. 잠시 후 다시 시도하세요.");
        if (!totp.getVerified(u, req.code())) {
            attempts.failed(key);
            audit.recordAs(u.getId(), u.getName(), "LOGIN_FAIL", null, null, "USER", u.getId(), u.getUsername(), "2단계 인증 코드 불일치");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "인증 코드가 맞지 않습니다.");
        }
        attempts.succeeded(key);
        s.removeAttribute(MFA_PENDING); s.removeAttribute(MFA_PENDING_AT);
        return new LoginResponse(false, finishLogin(u, request, response));
    }

    private MeDto finishLogin(AppUser u, HttpServletRequest request, HttpServletResponse response) {
        UserPrincipal principal = UserPrincipal.of(u).withoutPassword();
        audit.recordAs(u.getId(), u.getName(), "LOGIN", null, null, "USER", u.getId(), u.getUsername(), u.isTotpEnabled() ? "로그인 (2단계 인증)" : "로그인");
        HttpSession existing = request.getSession(false);
        if (existing != null) request.changeSessionId(); // 세션 고정 공격 방지
        saveAuthentication(principal, request, response);
        csrfRepo.saveToken(csrfRepo.generateToken(request), request, response); // 로그인 후 CSRF 토큰 교체
        boolean setupRequired = totp.getSetupRequired(u);
        if (setupRequired) request.getSession(true).setAttribute("MFA_SETUP_REQUIRED", true);
        return MeDto.of(u, setupRequired);
    }

    // ---------- 2단계 인증 설정 (본인) ----------
    @PostMapping("/mfa/setup")
    public MfaSetupResponse mfaSetup(@AuthenticationPrincipal UserPrincipal me) {
        if (me == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        return totp.insertSetup(me.id());
    }

    @PostMapping("/mfa/enable")
    public MfaEnableResponse mfaEnable(@AuthenticationPrincipal UserPrincipal me, @Valid @RequestBody MfaCodeRequest req, HttpServletRequest request) {
        if (me == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        MfaEnableResponse r = totp.updateEnable(me.id(), req.code());
        HttpSession s = request.getSession(false);
        if (s != null) s.removeAttribute("MFA_SETUP_REQUIRED");
        return r;
    }

    @PostMapping("/mfa/disable")
    public MeDto mfaDisable(@AuthenticationPrincipal UserPrincipal me, @Valid @RequestBody MfaDisableRequest req) {
        if (me == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        totp.updateDisable(me.id(), req.password(), req.code());
        return userService.getMe(me.id());
    }

    @GetMapping("/mfa/status")
    public Map<String, Object> mfaStatus(@AuthenticationPrincipal UserPrincipal me) {
        if (me == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        AppUser u = userService.getUser(me.id());
        return Map.of("enabled", u.isTotpEnabled(), "recoveryCodesLeft", totp.getRemainingRecoveryCodes(u), "setupRequired", totp.getSetupRequired(u));
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        csrfRepo.saveToken(null, request, response);
    }

    @GetMapping("/me")
    public MeDto me(@AuthenticationPrincipal UserPrincipal me) {
        if (me == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        return userService.getMe(me.id());
    }

    @PostMapping("/password")
    public MeDto changePassword(@AuthenticationPrincipal UserPrincipal me, @Valid @RequestBody PasswordChangeRequest req,
                                HttpServletRequest request, HttpServletResponse response) {
        if (me == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        AppUser u = userService.updatePassword(me, req.currentPassword(), req.newPassword());
        saveAuthentication(UserPrincipal.of(u).withoutPassword(), request, response);
        return MeDto.of(u);
    }

    private void saveAuthentication(UserPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, request, response);
    }
}
