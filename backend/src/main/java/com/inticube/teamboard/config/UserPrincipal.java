package com.inticube.teamboard.config;

import com.inticube.teamboard.domain.AppUser;
import com.inticube.teamboard.domain.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/** 세션에 저장되는 로그인 사용자 정보 */
public record UserPrincipal(Long id, String username, String name, Role role,
                            boolean mustChangePassword, boolean active,
                            String passwordHash) implements UserDetails, Serializable {

    public static UserPrincipal of(AppUser u) {
        return new UserPrincipal(u.getId(), u.getUsername(), u.getName(), u.getRole(),
                u.isMustChangePassword(), u.isActive(), u.getPasswordHash());
    }

    public boolean isAdmin() { return role == Role.ADMIN; }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return username; }
    @Override public boolean isEnabled() { return active; }

    /** 세션 저장 시 해시는 제거 */
    public UserPrincipal withoutPassword() {
        return new UserPrincipal(id, username, name, role, mustChangePassword, active, null);
    }
}
