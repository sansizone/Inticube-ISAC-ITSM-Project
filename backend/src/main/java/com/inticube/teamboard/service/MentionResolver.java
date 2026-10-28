package com.inticube.teamboard.service;

import com.inticube.teamboard.dao.AppUserDao;
import com.inticube.teamboard.domain.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 본문의 @언급 → 알림 받을 활성 사용자.
 * - @아이디 / @이메일아이디 : 아이디로 찾음
 * - @이름(아이디) : 화면 자동완성이 넣는 형식. 괄호 안 아이디(메일 앞부분 또는 전체)로 정확히 찾음 → 동명이인 구분
 * - @이름 : 이름으로 찾음 (뒤에 조사가 붙은 '@홍길동님' 도 아는 이름까지 잘라서 찾음, 동명이인이면 모두)
 */
@Component
@RequiredArgsConstructor
public class MentionResolver {
    static final Pattern MENTION = Pattern.compile(
            "@(?:[가-힣A-Za-z]{1,20}\\(([A-Za-z0-9._%+@-]{2,64})\\)|([A-Za-z0-9._-]{3,50}(?:@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)?|[가-힣]{2,20}))");

    private final AppUserDao userDao;

    public List<AppUser> getMentionedUsers(String text) {
        if (text == null || text.indexOf('@') < 0) return List.of();
        Set<String> ids = new LinkedHashSet<>();     // @이름(아이디) 의 아이디
        Set<String> tokens = new LinkedHashSet<>();  // @아이디 / @이름
        Matcher m = MENTION.matcher(text);
        while (m.find()) { if (m.group(1) != null) ids.add(m.group(1).toLowerCase(Locale.ROOT)); else tokens.add(m.group(2)); }
        if (ids.isEmpty() && tokens.isEmpty()) return List.of();
        Map<String, AppUser> byId = new HashMap<>();
        Map<String, AppUser> byLocal = new HashMap<>();   // 메일 아이디의 @ 앞부분
        Map<String, List<AppUser>> byName = new HashMap<>();
        for (AppUser u : userDao.selectAllOrderByName()) {
            if (!u.isActive()) continue;
            String un = u.getUsername().toLowerCase(Locale.ROOT);
            byId.put(un, u);
            if (un.contains("@")) byLocal.putIfAbsent(un.substring(0, un.indexOf('@')), u);
            byName.computeIfAbsent(u.getName(), k -> new ArrayList<>()).add(u);
        }
        LinkedHashMap<Long, AppUser> out = new LinkedHashMap<>();
        for (String id : ids) {
            AppUser u = byId.containsKey(id) ? byId.get(id) : byLocal.get(id);
            if (u != null) out.putIfAbsent(u.getId(), u);
        }
        for (String t : tokens) {
            AppUser u = byId.get(t.toLowerCase(Locale.ROOT));
            if (u != null) { out.putIfAbsent(u.getId(), u); continue; }
            for (int n = t.length(); n >= 2; n--) {
                List<AppUser> same = byName.get(t.substring(0, n));
                if (same != null) { same.forEach(x -> out.putIfAbsent(x.getId(), x)); break; }
            }
        }
        return new ArrayList<>(out.values());
    }
}
