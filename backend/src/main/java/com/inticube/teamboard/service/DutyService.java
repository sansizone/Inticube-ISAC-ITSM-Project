package com.inticube.teamboard.service;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.dao.AppUserDao;
import com.inticube.teamboard.dao.BoardDutyDao;
import com.inticube.teamboard.dao.ItemDao;
import com.inticube.teamboard.domain.AppUser;
import com.inticube.teamboard.domain.Board;
import com.inticube.teamboard.domain.BoardDuty;
import com.inticube.teamboard.web.ApiException;
import com.inticube.teamboard.web.dto.Dtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.inticube.teamboard.dao.Rows.lng;
import static com.inticube.teamboard.dao.Rows.lngOr0;

/** 워크스페이스별 담당업무 트리 관리 (구조 변경은 보드 소유자·관리자만) */
@Service
@RequiredArgsConstructor
@Transactional
public class DutyService {
    public static final String PATH_SEP = " > ";
    private static final int MAX_NODES = 1000;

    private final AccessGuard guard;
    private final BoardDutyDao dutyDao;
    private final ItemDao itemDao;
    private final AppUserDao userDao;
    private final BoardService boardService;

    @Transactional(readOnly = true)
    public List<DutyDto> getDutyList(Long boardId) {
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> r : dutyDao.selectItemCounts(boardId)) counts.put(lng(r, "duty_id"), lngOr0(r, "cnt"));
        return dutyDao.selectByBoard(boardId).stream()
                .map(d -> new DutyDto(d.getId(), d.getParentId(), d.getName(), d.getSortOrder(),
                        UserSummary.of(d.getPrimaryUser()), UserSummary.of(d.getSecondaryUser()),
                        counts.getOrDefault(d.getId(), 0L)))
                .toList();
    }

    public DutyDto insertDuty(Long boardId, DutyRequest req, UserPrincipal me) {
        Board board = boardService.getBoard(boardId);
        checkManage(board, me);
        List<BoardDuty> all = dutyDao.selectByBoard(boardId);
        if (all.size() >= MAX_NODES) throw ApiException.bad("담당업무는 워크스페이스당 최대 " + MAX_NODES + "개까지 만들 수 있습니다.");
        if (req.parentId() != null && all.stream().noneMatch(d -> d.getId().equals(req.parentId())))
            throw ApiException.bad("상위 담당업무가 이 워크스페이스에 없습니다.");
        checkDuplicateName(all, req.parentId(), req.name().trim(), null);
        BoardDuty d = new BoardDuty();
        d.setBoard(board);
        d.setParentId(req.parentId());
        d.setName(req.name().trim());
        d.setSortOrder(all.stream().filter(x -> Objects.equals(x.getParentId(), req.parentId()))
                .mapToInt(BoardDuty::getSortOrder).max().orElse(0) + 1);
        d.setPrimaryUser(user(req.primaryId()));
        d.setSecondaryUser(user(req.secondaryId()));
        dutyDao.insert(d);
        return toDto(d);
    }

    public DutyDto updateDuty(Long dutyId, DutyRequest req, UserPrincipal me) {
        BoardDuty d = getDuty(dutyId);
        checkManage(d.getBoard(), me);
        checkDuplicateName(dutyDao.selectByBoard(d.getBoard().getId()), d.getParentId(), req.name().trim(), d.getId());
        d.setName(req.name().trim());
        d.setPrimaryUser(user(req.primaryId()));
        d.setSecondaryUser(user(req.secondaryId()));
        dutyDao.update(d);
        return toDto(d);
    }

    /** 다른 상위 아래로 옮기거나 같은 상위 안에서 순서 변경. position 은 0부터 */
    public void updateDutyMove(Long dutyId, Long newParentId, int position, UserPrincipal me) {
        BoardDuty d = getDuty(dutyId);
        checkManage(d.getBoard(), me);
        List<BoardDuty> all = dutyDao.selectByBoard(d.getBoard().getId());
        if (newParentId != null) {
            if (all.stream().noneMatch(x -> x.getId().equals(newParentId))) throw ApiException.bad("상위 담당업무가 이 워크스페이스에 없습니다.");
            if (subtreeIds(all, d.getId()).contains(newParentId)) throw ApiException.bad("자기 자신이나 하위 항목 아래로는 옮길 수 없습니다.");
        }
        if (!Objects.equals(d.getParentId(), newParentId)) checkDuplicateName(all, newParentId, d.getName(), d.getId());
        d.setParentId(newParentId);
        List<BoardDuty> siblings = all.stream()
                .filter(x -> Objects.equals(x.getParentId(), newParentId) && !x.getId().equals(d.getId()))
                .sorted(Comparator.comparingInt(BoardDuty::getSortOrder).thenComparing(BoardDuty::getId))
                .collect(Collectors.toCollection(ArrayList::new));
        siblings.add(Math.max(0, Math.min(position, siblings.size())), d);
        for (int i = 0; i < siblings.size(); i++) {
            BoardDuty s = siblings.get(i);
            if (s.getSortOrder() != i + 1 || s == d) { s.setSortOrder(i + 1); dutyDao.update(s); }
        }
    }

    /** 하위 트리까지 삭제. 연결된 업무의 담당업무는 비워짐. 삭제된 노드 수 반환 */
    public int deleteDuty(Long dutyId, UserPrincipal me) {
        BoardDuty d = getDuty(dutyId);
        checkManage(d.getBoard(), me);
        List<BoardDuty> all = dutyDao.selectByBoard(d.getBoard().getId());
        Set<Long> ids = subtreeIds(all, d.getId());
        itemDao.updateClearDuty(ids);
        dutyDao.deleteByIds(ids);
        return ids.size();
    }

    // ---------- 업무/엑셀에서 쓰는 도우미 ----------

    /** 자신 또는 가장 가까운 상위의 정 담당자 */
    @Transactional(readOnly = true)
    public AppUser getInheritedPrimary(BoardDuty duty) {
        if (duty == null) return null;
        Map<Long, BoardDuty> byId = dutyDao.selectByBoard(duty.getBoard().getId()).stream()
                .collect(Collectors.toMap(BoardDuty::getId, Function.identity()));
        BoardDuty cur = byId.get(duty.getId());
        int guardCount = 0;
        while (cur != null && guardCount++ < 200) {
            if (cur.getPrimaryUser() != null && cur.getPrimaryUser().isActive()) return cur.getPrimaryUser();
            cur = cur.getParentId() == null ? null : byId.get(cur.getParentId());
        }
        return null;
    }

    /** 노드 ID → '대 > 중 > 소' 경로 */
    public static Map<Long, String> getPaths(List<BoardDuty> all) {
        Map<Long, BoardDuty> byId = all.stream().collect(Collectors.toMap(BoardDuty::getId, Function.identity()));
        Map<Long, String> out = new LinkedHashMap<>();
        for (BoardDuty d : getSortedDepthFirst(all)) {
            Deque<String> names = new ArrayDeque<>();
            BoardDuty cur = d;
            int guardCount = 0;
            while (cur != null && guardCount++ < 200) {
                names.addFirst(cur.getName());
                cur = cur.getParentId() == null ? null : byId.get(cur.getParentId());
            }
            out.put(d.getId(), String.join(PATH_SEP, names));
        }
        return out;
    }

    /** 트리 순서(깊이 우선)로 정렬 */
    public static List<BoardDuty> getSortedDepthFirst(List<BoardDuty> all) {
        Map<Long, List<BoardDuty>> children = new HashMap<>();
        Set<Long> ids = all.stream().map(BoardDuty::getId).collect(Collectors.toSet());
        for (BoardDuty d : all) {
            Long key = d.getParentId() != null && ids.contains(d.getParentId()) ? d.getParentId() : -1L;
            children.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
        }
        children.values().forEach(l -> l.sort(Comparator.comparingInt(BoardDuty::getSortOrder).thenComparing(BoardDuty::getId)));
        List<BoardDuty> out = new ArrayList<>();
        Deque<BoardDuty> stack = new ArrayDeque<>();
        List<BoardDuty> roots = children.getOrDefault(-1L, List.of());
        for (int i = roots.size() - 1; i >= 0; i--) stack.push(roots.get(i));
        while (!stack.isEmpty()) {
            BoardDuty d = stack.pop();
            out.add(d);
            List<BoardDuty> ch = children.getOrDefault(d.getId(), List.of());
            for (int i = ch.size() - 1; i >= 0; i--) stack.push(ch.get(i));
        }
        return out;
    }

    public BoardDuty getDuty(Long id) {
        BoardDuty d = dutyDao.selectById(id);
        if (d == null) throw ApiException.notFound("담당업무");
        return d;
    }

    private static Set<Long> subtreeIds(List<BoardDuty> all, Long rootId) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(rootId);
        boolean added = true;
        while (added) {
            added = false;
            for (BoardDuty x : all)
                if (x.getParentId() != null && ids.contains(x.getParentId()) && ids.add(x.getId())) added = true;
        }
        return ids;
    }

    private static void checkDuplicateName(List<BoardDuty> all, Long parentId, String name, Long selfId) {
        boolean dup = all.stream().anyMatch(x -> Objects.equals(x.getParentId(), parentId) && !x.getId().equals(selfId)
                && x.getName().equalsIgnoreCase(name));
        if (dup) throw ApiException.bad("같은 위치에 '" + name + "' 담당업무가 이미 있습니다.");
    }

    private AppUser user(Long id) {
        if (id == null) return null;
        AppUser u = userDao.selectById(id);
        if (u == null || !u.isActive()) throw ApiException.notFound("사용자");
        return u;
    }

    private void checkManage(Board b, UserPrincipal me) {
        if (!guard.getCanManage(b))
            throw new ApiException(HttpStatus.FORBIDDEN, "담당업무 구성은 워크스페이스 관리자만 변경할 수 있습니다.");
    }

    private DutyDto toDto(BoardDuty d) {
        return new DutyDto(d.getId(), d.getParentId(), d.getName(), d.getSortOrder(),
                UserSummary.of(d.getPrimaryUser()), UserSummary.of(d.getSecondaryUser()), 0);
    }
}
