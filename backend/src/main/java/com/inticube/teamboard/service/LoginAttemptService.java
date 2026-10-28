package com.inticube.teamboard.service;

import com.inticube.teamboard.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 아이디별 로그인 실패 횟수 관리 및 일시 잠금 (메모리 기반) */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {
    private final AppProperties props;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private record Attempt(int count, Instant lockedUntil) {}

    public boolean isLocked(String username) {
        Attempt a = attempts.get(key(username));
        return a != null && a.lockedUntil() != null && a.lockedUntil().isAfter(Instant.now());
    }

    public void failed(String username) {
        attempts.compute(key(username), (k, a) -> {
            int count = (a == null || (a.lockedUntil() != null && a.lockedUntil().isBefore(Instant.now()))) ? 1 : a.count() + 1;
            Instant lock = count >= props.getMaxLoginFailures()
                    ? Instant.now().plusSeconds(props.getLockMinutes() * 60L) : null;
            return new Attempt(count, lock);
        });
    }

    public void succeeded(String username) {
        attempts.remove(key(username));
    }

    private String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}
