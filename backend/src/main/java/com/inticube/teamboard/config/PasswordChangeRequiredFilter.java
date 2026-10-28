package com.inticube.teamboard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 초기/초기화된 비밀번호 사용자는 비밀번호 변경 전까지 /api/auth/** 외 API 사용 불가 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p && p.mustChangePassword()
                && path.startsWith("/api/") && !path.startsWith("/api/auth/")) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"message\":\"비밀번호를 먼저 변경해야 합니다.\",\"code\":\"PASSWORD_CHANGE_REQUIRED\"}");
            return;
        }
        // 정책상 2단계 인증 필수인데 아직 설정 안 한 사용자: 로그인 시 세션에 표시해 두고 /api/auth/** 만 허용
        jakarta.servlet.http.HttpSession session = req.getSession(false);
        if (auth != null && session != null && Boolean.TRUE.equals(session.getAttribute("MFA_SETUP_REQUIRED"))
                && path.startsWith("/api/") && !path.startsWith("/api/auth/")) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"message\":\"2단계 인증을 먼저 설정해야 합니다.\",\"code\":\"MFA_SETUP_REQUIRED\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
