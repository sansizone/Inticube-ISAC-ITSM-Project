package com.inticube.teamboard.service;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.dao.AppUserDao;
import com.inticube.teamboard.dao.WsGroupDao;
import com.inticube.teamboard.domain.AppUser;
import com.inticube.teamboard.domain.WsGroup;
import com.inticube.teamboard.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 워크스페이스 그룹 (그룹 > 워크스페이스).
 * <ul>
 *   <li>그룹 멤버만 그 그룹의 워크스페이스를 볼 수 있다. 그 안에서 '멤버 전용' 워크스페이스는 다시 워크스페이스 멤버로 제한</li>
 *   <li>그룹 관리자(ADMIN)는 그룹 안 워크스페이스를 모두 보고 관리하며, 그룹 멤버를 관리한다</li>
 *   <li>그룹 생성·삭제는 시스템 관리자만</li>
 * </ul>
 * v3.2 업그레이드 SQL 을 실행하지 않은 DB 에서는 {@link #isEnabled()} 가 false 이고 그룹 없이 예전처럼 동작한다.
 */
@Slf4j
@Service
@Order(20)
@RequiredArgsConstructor
@Transactional
public class WsGroupService implements ApplicationRunner {
    public static final String DEFAULT_NAME = "인티큐브서비스관리";
    private final WsGroupDao dao;
    private final AppUserDao userDao;
    private final com.inticube.teamboard.config.AppProperties props;
    private volatile Boolean enabled;

    /** isDefault: 내가 기본으로 정한 그룹 (사이드바 첫 화면) */
    public record GroupDto(Long id, String name, String description, int sortOrder, String myRole, long memberCount, long boardCount, boolean isDefault) {}
    public record GroupRequest(String name, String description) {}
    public record MemberDto(Long userId, String username, String name, String department, boolean active, String role) {}
    public record MemberRequest(Long userId, String role) {}

    // ───────────────────────── 기능 사용 여부 · 초기화

    @Transactional(readOnly = true)
    public boolean isEnabled() {
        if (enabled == null) {
            try { enabled = dao.selectTableCount() >= 3; } catch (Exception e) { enabled = false; }
        }
        return enabled;
    }

    /** 기동 시: 그룹이 하나도 없으면 기본 그룹을 만들고 사용자 전원·워크스페이스 전부를 넣는다. 그룹 없는 워크스페이스는 첫 그룹으로 */
    @Override
    public void run(ApplicationArguments args) {
        if (!isEnabled()) {
            log.warn("워크스페이스 그룹 테이블이 없어 그룹 기능을 끕니다 (deploy/postgres-upgrade-3.2.sql 실행 필요).");
            return;
        }
        List<WsGroup> all = dao.selectAll();
        if (all.isEmpty()) {
            WsGroup g = new WsGroup();
            g.setName(DEFAULT_NAME);
            g.setSortOrder(1);
            dao.insert(g);
            int n = 0;
            for (AppUser u : userDao.selectAllOrderByName()) {
                dao.insertMember(g.getId(), u.getId(), "ADMIN".equals(String.valueOf(u.getRole())) ? "ADMIN" : "MEMBER");
                n++;
            }
            int boards = dao.updateBoardGroupWhereNull(g.getId());
            log.info("워크스페이스 그룹 '{}' 생성: 사용자 {}명, 워크스페이스 {}개", DEFAULT_NAME, n, boards);
        } else {
            int boards = dao.updateBoardGroupWhereNull(all.get(0).getId());
            if (boards > 0) log.info("그룹 없는 워크스페이스 {}개를 '{}' 그룹으로", boards, all.get(0).getName());
        }
    }

    // ───────────────────────── 접근 확인용 (AccessGuard)

    /** 그룹 ID → 내 역할(ADMIN/MEMBER) */
    @Transactional(readOnly = true)
    public Map<Long, String> getMyRoles(Long userId) {
        Map<Long, String> m = new HashMap<>();
        if (!isEnabled()) return m;
        for (Map<String, Object> r : dao.selectRolesByUser(userId)) m.put(((Number) r.get("group_id")).longValue(), (String) r.get("member_role"));
        return m;
    }

    /** 워크스페이스 ID → 그룹 ID (그룹 없으면 키 없음) */
    @Transactional(readOnly = true)
    public Map<Long, Long> getBoardGroups() {
        Map<Long, Long> m = new HashMap<>();
        if (!isEnabled()) return m;
        for (Map<String, Object> r : dao.selectBoardGroups()) {
            Object g = r.get("ws_group_id");
            if (g != null) m.put(((Number) r.get("id")).longValue(), ((Number) g).longValue());
        }
        return m;
    }

    @Transactional(readOnly = true)
    public Long getBoardGroup(Long boardId) {
        return isEnabled() ? dao.selectBoardGroup(boardId) : null;
    }

    @Transactional(readOnly = true)
    public String getRole(Long groupId, Long userId) {
        return isEnabled() && groupId != null ? dao.selectRole(groupId, userId) : null;
    }

    // ───────────────────────── 그룹

    @Transactional(readOnly = true)
    public List<GroupDto> getGroups(UserPrincipal me) {
        if (!isEnabled()) return List.of();
        Map<Long, String> roles = getMyRoles(me.id());
        Map<Long, Long> members = new HashMap<>();
        for (Map<String, Object> r : dao.selectMemberCounts()) members.put(((Number) r.get("group_id")).longValue(), ((Number) r.get("cnt")).longValue());
        Map<Long, Long> boards = new HashMap<>();
        for (Long g : getBoardGroups().values()) boards.merge(g, 1L, Long::sum);
        List<GroupDto> out = new ArrayList<>();
        Long def = getDefault(me.id());
        for (WsGroup g : dao.selectAll()) {
            String role = roles.get(g.getId());
            if (role == null && !me.isAdmin()) continue;
            if (me.isAdmin()) role = "ADMIN";
            out.add(new GroupDto(g.getId(), g.getName(), g.getDescription(), g.getSortOrder(), role,
                    members.getOrDefault(g.getId(), 0L), boards.getOrDefault(g.getId(), 0L), g.getId().equals(def)));
        }
        return out;
    }

    public GroupDto insertGroup(GroupRequest req, UserPrincipal me) {
        requireEnabled();
        if (!me.isAdmin()) throw ApiException.forbidden();
        WsGroup g = new WsGroup();
        g.setName(name(req));
        g.setDescription(desc(req));
        g.setSortOrder(dao.selectMaxSort() + 1);
        g.setCreatedBy(me.id());
        dao.insert(g);
        dao.insertMember(g.getId(), me.id(), "ADMIN");
        return new GroupDto(g.getId(), g.getName(), g.getDescription(), g.getSortOrder(), "ADMIN", 1, 0, false);
    }

    public void updateGroup(Long id, GroupRequest req, UserPrincipal me) {
        WsGroup g = getManaged(id, me);
        g.setName(name(req));
        g.setDescription(desc(req));
        dao.update(g);
    }

    /** 순서 변경 (시스템 관리자) */
    public void setOrder(List<Long> ids, UserPrincipal me) {
        requireEnabled();
        if (!me.isAdmin()) throw ApiException.forbidden();
        int i = 1;
        for (Long id : ids) {
            WsGroup g = dao.selectById(id);
            if (g == null) continue;
            g.setSortOrder(i++);
            dao.update(g);
        }
    }

    public void deleteGroup(Long id, UserPrincipal me) {
        requireEnabled();
        if (!me.isAdmin()) throw ApiException.forbidden();
        if (dao.selectById(id) == null) throw ApiException.notFound("워크스페이스 그룹");
        long boards = dao.selectBoardCount(id);
        if (boards > 0) throw ApiException.bad("워크스페이스가 " + boards + "개 있는 그룹은 삭제할 수 없습니다. 워크스페이스를 다른 그룹으로 옮긴 뒤 삭제하세요.");
        if (dao.selectAll().size() <= 1) throw ApiException.bad("마지막 그룹은 삭제할 수 없습니다.");
        dao.deleteMembersByGroup(id);
        dao.delete(id);
    }

    // ───────────────────────── 멤버

    @Transactional(readOnly = true)
    public List<MemberDto> getMembers(Long id, UserPrincipal me) {
        requireEnabled();
        if (dao.selectById(id) == null) throw ApiException.notFound("워크스페이스 그룹");
        if (!me.isAdmin() && getRole(id, me.id()) == null) throw ApiException.forbidden();
        return dao.selectMembers(id).stream().map(r -> new MemberDto(((Number) r.get("user_id")).longValue(), (String) r.get("username"),
                (String) r.get("name"), (String) r.get("department"), Boolean.TRUE.equals(r.get("is_active")), (String) r.get("member_role"))).toList();
    }

    /** 멤버 목록 전체 교체. 그룹 관리자가 한 명도 없게 만들 수는 없다 (시스템 관리자는 예외) */
    public List<MemberDto> setMembers(Long id, List<MemberRequest> req, UserPrincipal me) {
        getManaged(id, me);
        Map<Long, String> next = new LinkedHashMap<>();
        for (MemberRequest m : req == null ? List.<MemberRequest>of() : req) {
            if (m.userId() == null) continue;
            if (userDao.selectById(m.userId()) == null) throw ApiException.bad("없는 사용자입니다: " + m.userId());
            next.put(m.userId(), "ADMIN".equals(m.role()) ? "ADMIN" : "MEMBER");
        }
        if (!me.isAdmin() && !"ADMIN".equals(next.get(me.id())))
            throw ApiException.bad("자기 자신의 그룹 관리자 권한은 뺄 수 없습니다. 다른 관리자나 시스템 관리자에게 요청하세요.");
        Map<Long, String> cur = new HashMap<>();
        for (Map<String, Object> r : dao.selectMembers(id)) cur.put(((Number) r.get("user_id")).longValue(), (String) r.get("member_role"));
        for (Long uid : cur.keySet()) if (!next.containsKey(uid)) dao.deleteMember(id, uid);
        for (var e : next.entrySet()) {
            String old = cur.get(e.getKey());
            if (old == null) dao.insertMember(id, e.getKey(), e.getValue());
            else if (!old.equals(e.getValue())) dao.updateMemberRole(id, e.getKey(), e.getValue());
        }
        return getMembers(id, me);
    }

    /** 새 계정은 첫 그룹의 멤버로 (시스템 관리자가 나중에 다른 그룹으로 옮김) */
    public void insertUserToDefault(Long userId) {
        if (!isEnabled()) return;
        List<WsGroup> all = dao.selectAll();
        if (!all.isEmpty()) dao.insertMember(all.get(0).getId(), userId, "MEMBER");
    }

    // ───────────────────────── 워크스페이스의 그룹

    /** 새 워크스페이스가 들어갈 그룹: 지정한 그룹(그 그룹 멤버여야 함) 또는 내가 속한 첫 그룹 */
    @Transactional(readOnly = true)
    public Long getGroupForNewBoard(Long requested, UserPrincipal me) {
        if (!isEnabled()) return null;
        if (requested != null) {
            if (dao.selectById(requested) == null) throw ApiException.notFound("워크스페이스 그룹");
            if (!me.isAdmin() && getRole(requested, me.id()) == null) throw new ApiException(HttpStatus.FORBIDDEN, "이 그룹의 멤버만 워크스페이스를 만들 수 있습니다.");
            return requested;
        }
        List<WsGroup> all = dao.selectAll();
        Map<Long, String> roles = getMyRoles(me.id());
        for (WsGroup g : all) if (roles.containsKey(g.getId())) return g.getId();
        if (me.isAdmin() && !all.isEmpty()) return all.get(0).getId();
        throw new ApiException(HttpStatus.FORBIDDEN, "속한 워크스페이스 그룹이 없어 워크스페이스를 만들 수 없습니다.");
    }

    public void setBoardGroupRaw(Long boardId, Long groupId) {
        if (isEnabled() && groupId != null) dao.updateBoardGroup(boardId, groupId);
    }

    /** 워크스페이스를 다른 그룹으로: 시스템 관리자, 또는 지금 그룹과 옮길 그룹 둘 다의 관리자 */
    public void setBoardGroup(Long boardId, Long groupId, UserPrincipal me) {
        requireEnabled();
        if (groupId == null || dao.selectById(groupId) == null) throw ApiException.bad("옮길 그룹을 선택하세요.");
        Long cur = dao.selectBoardGroup(boardId);
        if (!me.isAdmin()) {
            boolean ok = "ADMIN".equals(getRole(groupId, me.id())) && (cur == null || "ADMIN".equals(getRole(cur, me.id())));
            if (!ok) throw new ApiException(HttpStatus.FORBIDDEN, "두 그룹의 관리자만 워크스페이스를 옮길 수 있습니다.");
        }
        dao.updateBoardGroup(boardId, groupId);
    }

    // ───────────────────────── 사용자별 기본 그룹 (DB 변경 없이 업로드 폴더의 작은 파일에 저장)

    private java.nio.file.Path defaultFile(Long userId) {
        return java.nio.file.Paths.get(props.getUploadDir()).toAbsolutePath().normalize().resolve("settings").resolve("ws-group-default-" + userId + ".txt");
    }

    @Transactional(readOnly = true)
    public Long getDefault(Long userId) {
        try {
            java.nio.file.Path f = defaultFile(userId);
            if (!java.nio.file.Files.exists(f)) return null;
            String v = java.nio.file.Files.readString(f).trim();
            return v.matches("\\d{1,18}") ? Long.valueOf(v) : null;
        } catch (Exception e) { return null; }
    }

    /** 기본 그룹 지정 (내가 볼 수 있는 그룹만). null 이면 해제 */
    public void setDefault(Long groupId, UserPrincipal me) {
        requireEnabled();
        if (groupId != null) {
            if (dao.selectById(groupId) == null) throw ApiException.notFound("워크스페이스 그룹");
            if (!me.isAdmin() && getRole(groupId, me.id()) == null) throw ApiException.forbidden();
        }
        try {
            java.nio.file.Path f = defaultFile(me.id());
            if (groupId == null) { java.nio.file.Files.deleteIfExists(f); return; }
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.writeString(f, String.valueOf(groupId));
        } catch (Exception e) {
            log.warn("그룹 고정 저장 실패 user={} group={} dir={}: {}", me.id(), groupId, props.getUploadDir(), e.toString());
            throw ApiException.bad("그룹 고정을 저장하지 못했습니다. (업로드 폴더 쓰기 권한 확인)");
        }
    }

    // ───────────────────────── 내부

    private WsGroup getManaged(Long id, UserPrincipal me) {
        requireEnabled();
        WsGroup g = dao.selectById(id);
        if (g == null) throw ApiException.notFound("워크스페이스 그룹");
        if (!me.isAdmin() && !"ADMIN".equals(getRole(id, me.id()))) throw new ApiException(HttpStatus.FORBIDDEN, "그룹 관리자만 변경할 수 있습니다.");
        return g;
    }

    private void requireEnabled() {
        if (!isEnabled()) throw ApiException.bad("워크스페이스 그룹 기능이 꺼져 있습니다 (DB 업그레이드 3.2 필요).");
    }

    private static String name(GroupRequest req) {
        String n = req == null || req.name() == null ? "" : req.name().trim();
        if (n.isEmpty() || n.length() > 100) throw ApiException.bad("그룹 이름은 1~100자여야 합니다.");
        return n;
    }

    private static String desc(GroupRequest req) {
        String d = req == null || req.description() == null ? null : req.description().trim();
        if (d != null && d.length() > 500) throw ApiException.bad("설명은 500자 이하여야 합니다.");
        return d == null || d.isEmpty() ? null : d;
    }
}
