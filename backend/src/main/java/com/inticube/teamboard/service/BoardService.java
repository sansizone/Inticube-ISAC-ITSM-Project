package com.inticube.teamboard.service;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.dao.*;
import com.inticube.teamboard.domain.*;
import com.inticube.teamboard.web.ApiException;
import com.inticube.teamboard.web.dto.Dtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.inticube.teamboard.dao.Rows.*;

/** 워크스페이스(보드): 목록·상세·설정·상태·그룹·컬럼·유형·버전·보기 탭·멤버 */
@Service
@RequiredArgsConstructor
@Transactional
public class BoardService {

    private final BoardDao boardDao;
    private final BoardPartsDao partsDao;
    private final ItemDao itemDao;
    private final ItemExtraDao extraDao;
    private final NotificationDao notiDao;
    private final BoardDutyDao dutyDao;
    private final WikiPageDao wikiDao;
    private final AppUserDao userDao;
    private final FileStorageService storage;
    private final RelationService relations;
    private final AccessGuard guard;
    private final WsGroupService wsGroups;
    private final AuditService audit;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private DutyService dutyService;

    private static final String[][] DEFAULT_STATUSES = {
            {"대기", "#A7AEB8", "false"},
            {"처리중", "#0061F2", "false"},
            {"막힘", "#E81500", "false"},
            {"완료", "#00AC69", "true"},
    };
    /** 새 워크스페이스의 기본 보기 탭 */
    public static final String[][] DEFAULT_VIEWS = {
            {"목록", "list"}, {"표", "table"}, {"칸반", "kanban"}, {"타임라인", "timeline"}, {"로드맵", "roadmap"},
            {"인사이트", "dashboard"}, {"담당업무", "duties"}, {"위키", "wiki"},
    };
    /** 유지보수용 기본 업무 유형: 이름, 색, 아이콘, 응답 목표(h), 처리 목표(h), 기본 여부 */
    public static final Object[][] DEFAULT_TYPES = {
            {"요청", "#0061F2", "inbox", 4, 72, true},
            {"장애", "#E81500", "alert", 1, 8, false},
            {"변경", "#6900C7", "edit", 8, 120, false},
            {"문의", "#00AC69", "chat", 4, 24, false},
            {"정기작업", "#F4A100", "repeat", null, null, false},
    };

    public Board getBoard(Long id) {
        Board b = boardDao.selectById(id);
        if (b == null) throw ApiException.notFound("워크스페이스");
        return b;
    }

    @Transactional(readOnly = true)
    public List<BoardSummary> getBoardList() {
        Map<Long, Map<String, Object>> counts = new HashMap<>();
        for (Map<String, Object> r : itemDao.selectCountByBoard()) counts.put(lng(r, "board_id"), r);
        Map<Long, Long> memberCount = new HashMap<>();
        for (Map<String, Object> r : partsDao.selectMemberCountByBoard()) memberCount.put(lng(r, "board_id"), lngOr0(r, "cnt"));
        UserPrincipal me = guard.getMe();
        Map<Long, String> myRoles = new HashMap<>();
        for (BoardMember m : partsDao.selectMembersByUser(me.id())) myRoles.put(m.getBoardId(), m.getRole());
        Set<Long> visible = guard.getVisibleBoardIds();
        Set<Long> favs = new HashSet<>(partsDao.selectFavoriteBoardIds(me.id()));
        Map<Long, Long> boardGroup = wsGroups.getBoardGroups();
        // 사용자별 정렬 순서: 저장된 순서 → 없는 워크스페이스는 이름순으로 뒤에
        Map<Long, Long> order = new HashMap<>();
        for (Map<String, Object> r : partsDao.selectBoardOrders(me.id())) order.put(lng(r, "board_id"), lngOr0(r, "sort_order"));
        return boardDao.selectAllOrderByName().stream()
                .filter(b -> AccessGuard.isVisible(b, visible))
                .sorted(Comparator.comparingLong(b -> order.getOrDefault(b.getId(), Long.MAX_VALUE)))
                .map(b -> {
                    Map<String, Object> c = counts.get(b.getId());
                    long total = c == null ? 0 : lngOr0(c, "total");
                    long done = c == null ? 0 : lngOr0(c, "done");
                    String role = myRoles.get(b.getId());
                    if (role == null && b.getOwner().getId().equals(me.id())) role = "OWNER";
                    return new BoardSummary(b.getId(), b.getName(), b.getDescription(), b.getOwner().getName(), total, done,
                            b.isPublic(), memberCount.getOrDefault(b.getId(), 0L), role, favs.contains(b.getId()), isShowProgress(b), boardGroup.get(b.getId()));
                }).toList();
    }

    public BoardSummary insertBoard(BoardRequest req, UserPrincipal me) {
        Long groupId = wsGroups.getGroupForNewBoard(req.groupId(), me);
        Board b = new Board();
        b.setName(req.name().trim());
        b.setDescription(req.description());
        AppUser owner = new AppUser(); owner.setId(me.id());
        b.setOwner(owner);
        b.setPublicBoard(Boolean.TRUE.equals(req.isPublic()));
        b.setNextSeq(1);
        boardDao.insert(b);
        b.setKeyPrefix(getUniquePrefix(getSuggestedPrefix(b.getName(), b.getId())));
        boardDao.update(b);
        wsGroups.setBoardGroupRaw(b.getId(), groupId);
        for (int i = 0; i < DEFAULT_STATUSES.length; i++) {
            BoardStatus s = new BoardStatus();
            s.setBoard(b);
            s.setLabel(DEFAULT_STATUSES[i][0]);
            s.setColor(DEFAULT_STATUSES[i][1]);
            s.setDone(Boolean.parseBoolean(DEFAULT_STATUSES[i][2]));
            s.setSortOrder(i);
            partsDao.insertStatus(s);
        }
        BoardGroup g = new BoardGroup();
        g.setBoard(b);
        g.setName("할 일");
        g.setColor("#0061F2");
        g.setSortOrder(1);
        partsDao.insertGroup(g);
        insertMember(b.getId(), me.id(), "OWNER");
        insertDefaultTypes(b);
        insertDefaultViews(b.getId());
        audit.record("BOARD_CREATE", b.getId(), b.getName(), "BOARD", b.getId(), b.getName(), b.isPublic() ? "공개" : "멤버 전용");
        return new BoardSummary(b.getId(), b.getName(), b.getDescription(), me.name(), 0, 0, b.isPublic(), 1, "OWNER", false, true, groupId);
    }

    /** 진행률 표시 여부: 컬럼 설정 JSON 에 "showProgress": false 가 있으면 끔 (기본 표시) */
    private static final java.util.regex.Pattern NO_PROGRESS = java.util.regex.Pattern.compile("\"showProgress\"\\s*:\\s*false");
    static boolean isShowProgress(Board b) {
        return b.getColumnConfig() == null || !NO_PROGRESS.matcher(b.getColumnConfig()).find();
    }

    /** 이름에서 키 접두어 추천: 영문·숫자만 추려 대문자 6자, 없으면 TB+번호 */
    public static String getSuggestedPrefix(String name, Long id) {
        String p = (name == null ? "" : name).replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (p.isEmpty() || !Character.isLetter(p.charAt(0))) p = "TB" + id;
        return p.length() > 6 ? p.substring(0, 6) : p;
    }

    public String getUniquePrefix(String base) {
        Set<String> used = new HashSet<>();
        for (Board x : boardDao.selectAll()) if (x.getKeyPrefix() != null) used.add(x.getKeyPrefix());
        if (!used.contains(base)) return base;
        for (int i = 2; i < 1000; i++) { String c = base + i; if (!used.contains(c)) return c; }
        return base + System.currentTimeMillis() % 10000;
    }

    public void insertDefaultTypes(Board b) {
        for (int i = 0; i < DEFAULT_TYPES.length; i++) {
            Object[] d = DEFAULT_TYPES[i];
            BoardType t = new BoardType();
            t.setBoard(b);
            t.setName((String) d[0]); t.setColor((String) d[1]); t.setIcon((String) d[2]);
            t.setResponseHours((Integer) d[3]); t.setResolveHours((Integer) d[4]);
            t.setDefaultType((Boolean) d[5]);
            t.setSortOrder(i + 1);
            partsDao.insertType(t);
        }
    }

    /** 키 번호가 없는 업무에 순서대로 번호 부여 (가져오기·업그레이드 후) */
    public int batchAssignKeys(Board b) {
        List<Item> list = itemDao.selectWithoutKey(b.getId());
        if (list.isEmpty()) return 0;
        int next = Math.max(b.getNextSeq() == null ? 1 : b.getNextSeq(), itemDao.selectMaxKeyNum(b.getId()) + 1);
        for (Item i : list) itemDao.updateKeyNum(i.getId(), next++);
        b.setNextSeq(next);
        boardDao.updateNextSeq(b.getId(), next);
        return list.size();
    }

    /** 다음 업무 번호를 발급하고 워크스페이스의 next_seq 를 올린다 */
    public int getNextKey(Board b) {
        int next = Math.max(b.getNextSeq() == null ? 1 : b.getNextSeq(), itemDao.selectMaxKeyNum(b.getId()) + 1);
        b.setNextSeq(next + 1);
        boardDao.updateNextSeq(b.getId(), next + 1);
        return next;
    }

    public BoardType getDefaultType(Long boardId) {
        List<BoardType> ts = partsDao.selectTypesByBoard(boardId);
        return ts.stream().filter(BoardType::isDefaultType).findFirst().orElse(ts.isEmpty() ? null : ts.get(0));
    }

    public void insertDefaultViews(Long boardId) {
        for (int i = 0; i < DEFAULT_VIEWS.length; i++) {
            BoardView v = new BoardView();
            v.setBoardId(boardId);
            v.setName(DEFAULT_VIEWS[i][0]);
            v.setType(DEFAULT_VIEWS[i][1]);
            v.setSortOrder(i + 1);
            partsDao.insertView(v);
        }
    }

    public void insertMember(Long boardId, Long userId, String role) {
        BoardMember m = new BoardMember();
        m.setBoardId(boardId);
        AppUser u = new AppUser(); u.setId(userId);
        m.setUser(u);
        m.setRole(role);
        partsDao.insertMember(m);
    }

    /** 즐겨찾기 추가/해제 (사용자별). 반환: 변경 후 즐겨찾기 여부 */
    public boolean setFavorite(Long boardId, Long userId, boolean on) {
        boolean cur = partsDao.selectFavoriteExists(boardId, userId);
        if (on && !cur) partsDao.insertFavorite(boardId, userId);
        if (!on && cur) partsDao.deleteFavorite(boardId, userId);
        return on;
    }

    /** 사이드바 정렬 순서 저장 (사용자별). ids = 화면에 보이는 순서대로의 워크스페이스 id 목록 */
    public void setBoardOrder(Long userId, List<Long> ids, Set<Long> visible) {
        List<Long> clean = new ArrayList<>();
        for (Board b : boardDao.selectAllOrderByName()) if (AccessGuard.isVisible(b, visible)) clean.add(b.getId());
        // 볼 수 있는 워크스페이스만, 중복 없이, 요청 순서대로
        LinkedHashSet<Long> ordered = new LinkedHashSet<>();
        for (Long id : ids) if (id != null && clean.contains(id)) ordered.add(id);
        partsDao.deleteBoardOrdersByUser(userId);
        if (!ordered.isEmpty()) partsDao.batchInsertBoardOrders(userId, new ArrayList<>(ordered));
    }

    /** 멤버로 추가 (이미 있으면 무시) */
    public void insertMemberIfAbsent(Long boardId, Long userId) {
        if (!partsDao.selectMemberExists(boardId, userId)) insertMember(boardId, userId, "MEMBER");
    }

    @Transactional(readOnly = true)
    public BoardDetail getBoardDetail(Long id) {
        Board b = getBoard(id);
        Map<Long, Long> comments = toCountMap(extraDao.selectCommentCountByBoard(id), "item_id", "cnt");
        Map<Long, Long> files = toCountMap(extraDao.selectFileCountByBoard(id), "item_id", "cnt");
        List<BoardColumn> cols = partsDao.selectColumnsByBoard(id);
        // 목록 응답에는 여러 줄 텍스트를 제외한 값만 (상세 화면에서 전체 조회)
        List<Long> shortCols = cols.stream().filter(c -> !"LONG_TEXT".equals(c.getType())).map(BoardColumn::getId).toList();
        Map<Long, Map<Long, String>> vals = new HashMap<>();
        if (!shortCols.isEmpty())
            for (Map<String, Object> v : itemDao.selectValueTuplesByColumns(shortCols))
                vals.computeIfAbsent(lng(v, "item_id"), k -> new HashMap<>()).put(lng(v, "column_id"), str(v, "val_text"));
        // 전화번호 컬럼은 마스킹해서 내려보냄 (원문은 관리자가 사유를 남기고 열람: ItemService.getPhoneRevealed)
        Set<Long> phoneCols = cols.stream().filter(c -> "PHONE".equals(c.getType())).map(BoardColumn::getId).collect(Collectors.toSet());
        if (!phoneCols.isEmpty())
            for (Map<Long, String> m : vals.values()) m.replaceAll((cid, v) -> phoneCols.contains(cid) ? PhoneFormat.mask(v) : v);
        Map<Long, BigDecimal> spent = new HashMap<>();
        for (Map<String, Object> t : extraDao.selectTimeSumByBoard(id)) spent.put(lng(t, "item_id"), dec(t, "total"));
        List<ItemDto> itemDtos = new ArrayList<>();
        for (Map<String, Object> r : itemDao.selectRowsByBoard(id)) {
            Long iid = lng(r, "id");
            Long aid = lng(r, "assignee_id");
            UserSummary assignee = aid == null ? null : new UserSummary(aid, str(r, "a_username"), str(r, "a_name"), str(r, "a_department"));
            String pr = str(r, "priority");
            itemDtos.add(new ItemDto(iid, lng(r, "group_id"), lng(r, "duty_id"), lng(r, "parent_id"), str(r, "item_name"), lng(r, "status_id"), assignee,
                    pr == null ? null : Priority.valueOf(pr), date(r, "start_date"), date(r, "due_date"), integer(r, "sort_order"),
                    comments.getOrDefault(iid, 0L), files.getOrDefault(iid, 0L),
                    dateTime(r, "created_at"), dateTime(r, "updated_at"), vals.getOrDefault(iid, Map.of()),
                    integer(r, "key_num"), lng(r, "type_id"), lng(r, "version_id"), dec(r, "estimate_hours"), spent.get(iid), integer(r, "progress"),
                    dateTime(r, "responded_at"), dateTime(r, "resolved_at"), str(r, "recur_rule"), lng(r, "created_by")));
        }
        List<BoardColumn> relCols = cols.stream().filter(c -> RelationService.TYPE.equals(c.getType())).toList();
        Map<Long, List<String>> relVals = new HashMap<>();
        if (!relCols.isEmpty()) {
            Set<Long> relIds = relCols.stream().map(BoardColumn::getId).collect(Collectors.toSet());
            for (Map<Long, String> m : vals.values())
                m.forEach((cid, v) -> { if (relIds.contains(cid)) relVals.computeIfAbsent(cid, k -> new ArrayList<>()).add(v); });
        }
        return new BoardDetail(b.getId(), b.getName(), b.getDescription(), b.getOwner().getId(), b.getOwner().getName(),
                b.isPublic(), guard.getCanManage(b), b.getColumnConfig(),
                b.getKeyPrefix(), b.isSlaBusiness(),
                partsDao.selectStatusesByBoard(id).stream().map(StatusDto::of).toList(),
                partsDao.selectGroupsByBoard(id).stream().map(GroupDto::of).toList(),
                dutyService.getDutyList(id),
                cols.stream().map(ColumnDto::of).toList(),
                partsDao.selectViewsByBoard(id).stream().map(ViewDto::of).toList(),
                partsDao.selectTypesByBoard(id).stream().map(TypeDto::of).toList(),
                partsDao.selectVersionsByBoard(id).stream().map(VersionDto::of).toList(),
                extraDao.selectLinksByBoard(id).stream().map(l -> new LinkEdge(l.getId(), l.getFromItemId(), l.getToItemId(), l.getType())).toList(),
                refBoardNames(b, cols, relCols), relations.getLabels(relCols, relVals),
                itemDtos);
    }

    /** 연결 컬럼 대상 + 사람 컬럼 후보 워크스페이스(peopleSources) 의 ID → 이름 */
    private Map<Long, String> refBoardNames(Board b, List<BoardColumn> cols, List<BoardColumn> relCols) {
        Map<Long, String> out = new HashMap<>(relations.getBoardNames(relCols));
        for (BoardColumn c : cols) {
            if (!"PEOPLE".equals(c.getType())) continue;
            Long src = RelationService.getPeopleSource(b, c.getId());
            if (src != null && !out.containsKey(src)) { Board sb = boardDao.selectById(src); if (sb != null) out.put(src, sb.getName()); }
        }
        return out;
    }

    public static ItemDto toDto(Item i, long commentCount, long fileCount) {
        return toDto(i, commentCount, fileCount, null);
    }

    public static ItemDto toDto(Item i, long commentCount, long fileCount, Map<Long, String> vals) {
        return toDto(i, commentCount, fileCount, vals, null);
    }

    public static ItemDto toDto(Item i, long commentCount, long fileCount, Map<Long, String> vals, BigDecimal spent) {
        return new ItemDto(i.getId(), i.getGroup().getId(), i.getDuty() == null ? null : i.getDuty().getId(),
                i.getParent() == null ? null : i.getParent().getId(), i.getName(),
                i.getStatus() == null ? null : i.getStatus().getId(),
                UserSummary.of(i.getAssignee()), i.getPriority(), i.getStartDate(), i.getDueDate(),
                i.getSortOrder(), commentCount, fileCount, i.getCreatedAt(), i.getUpdatedAt(), vals,
                i.getKeyNum(), i.getType() == null ? null : i.getType().getId(), i.getVersion() == null ? null : i.getVersion().getId(),
                i.getEstimateHours(), spent, i.getProgress(), i.getRespondedAt(), i.getResolvedAt(), i.getRecurRule(),
                i.getCreatedBy() == null ? null : i.getCreatedBy().getId());
    }

    static Map<Long, Long> toCountMap(List<Map<String, Object>> rows, String key, String val) {
        Map<Long, Long> m = new HashMap<>();
        for (Map<String, Object> r : rows) m.put(lng(r, key), lngOr0(r, val));
        return m;
    }

    public void updateBoard(Long id, BoardRequest req, UserPrincipal me) {
        Board b = getBoard(id);
        checkOwner(b, me);
        List<String> ch = new ArrayList<>();
        if (!Objects.equals(b.getName(), req.name().trim())) ch.add("이름: " + b.getName() + " → " + req.name().trim());
        if (!Objects.equals(nz(b.getDescription()), nz(req.description()))) ch.add("설명 수정");
        if (req.isPublic() != null && req.isPublic() != b.isPublic()) ch.add("공개 범위: " + (b.isPublic() ? "공개" : "멤버 전용") + " → " + (req.isPublic() ? "공개" : "멤버 전용"));
        b.setName(req.name().trim());
        b.setDescription(req.description());
        if (req.isPublic() != null) b.setPublicBoard(req.isPublic());
        boardDao.update(b);
        if (!ch.isEmpty()) audit.record("BOARD_UPDATE", b.getId(), b.getName(), "BOARD", b.getId(), b.getName(), String.join(" · ", ch));
    }

    /** 키 접두어·SLA 영업시간 설정 */
    public void setKeySettings(Long boardId, KeySettingsRequest req, UserPrincipal me) {
        Board b = getBoard(boardId);
        checkOwner(b, me);
        if (req.keyPrefix() != null) {
            String p = req.keyPrefix().trim().toUpperCase(Locale.ROOT);
            if (!p.matches("[A-Z][A-Z0-9]{1,9}")) throw ApiException.bad("키 접두어는 영문 대문자로 시작하는 영문·숫자 2~10자여야 합니다.");
            for (Board x : boardDao.selectAll())
                if (!x.getId().equals(boardId) && p.equals(x.getKeyPrefix())) throw ApiException.bad("'" + p + "' 접두어는 '" + x.getName() + "' 워크스페이스가 쓰고 있습니다.");
            b.setKeyPrefix(p);
        }
        if (req.slaBusinessHours() != null) b.setSlaBusinessHours(req.slaBusinessHours());
        boardDao.update(b);
    }

    // ---------- 업무 유형 ----------
    public List<TypeDto> setTypes(Long boardId, List<TypeRequest> reqs, UserPrincipal me) {
        Board b = getBoard(boardId);
        checkOwner(b, me);
        if (reqs == null) reqs = List.of();
        if (reqs.size() > 30) throw ApiException.bad("유형은 최대 30개까지 만들 수 있습니다.");
        Map<Long, BoardType> existing = partsDao.selectTypesByBoard(boardId).stream()
                .collect(Collectors.toMap(BoardType::getId, Function.identity()));
        Set<Long> kept = new HashSet<>();
        int order = 0;
        boolean hasDefault = false;
        for (TypeRequest r : reqs) {
            BoardType t;
            boolean isNew = r.id() == null;
            if (!isNew) {
                t = existing.get(r.id());
                if (t == null) throw ApiException.bad("잘못된 유형 ID");
                kept.add(t.getId());
            } else { t = new BoardType(); t.setBoard(b); }
            t.setName(r.name().trim());
            t.setColor(r.color() == null ? "#0061F2" : validColor(r.color()));
            t.setIcon(r.icon());
            t.setResponseHours(positiveOrNull(r.responseHours()));
            t.setResolveHours(positiveOrNull(r.resolveHours()));
            t.setDefaultType(r.isDefault() && !hasDefault);
            if (t.isDefaultType()) hasDefault = true;
            t.setSortOrder(order++);
            if (isNew) partsDao.insertType(t); else partsDao.updateType(t);
        }
        List<Long> removed = existing.keySet().stream().filter(id -> !kept.contains(id)).toList();
        if (!removed.isEmpty()) { itemDao.updateClearType(removed); partsDao.deleteTypes(removed); }
        return partsDao.selectTypesByBoard(boardId).stream().map(TypeDto::of).toList();
    }

    private static Integer positiveOrNull(Integer v) { return v == null || v <= 0 ? null : v; }

    // ---------- 버전·마일스톤 ----------
    public VersionDto insertVersion(Long boardId, VersionRequest req, UserPrincipal me) {
        Board b = getBoard(boardId);
        checkOwner(b, me);
        BoardVersion v = new BoardVersion();
        v.setBoard(b);
        applyVersion(v, req);
        v.setSortOrder(partsDao.selectVersionMaxSort(boardId) + 1);
        partsDao.insertVersion(v);
        return VersionDto.of(v);
    }

    public VersionDto updateVersion(Long versionId, VersionRequest req, UserPrincipal me) {
        BoardVersion v = partsDao.selectVersionById(versionId);
        if (v == null) throw ApiException.notFound("버전");
        checkOwner(v.getBoard(), me);
        applyVersion(v, req);
        partsDao.updateVersion(v);
        return VersionDto.of(v);
    }

    public void deleteVersion(Long versionId, UserPrincipal me) {
        BoardVersion v = partsDao.selectVersionById(versionId);
        if (v == null) throw ApiException.notFound("버전");
        checkOwner(v.getBoard(), me);
        itemDao.updateClearVersion(versionId);
        partsDao.deleteVersion(versionId);
    }

    public void setVersionOrder(Long boardId, List<Long> ids, UserPrincipal me) {
        checkOwner(getBoard(boardId), me);
        Map<Long, BoardVersion> map = partsDao.selectVersionsByBoard(boardId).stream()
                .collect(Collectors.toMap(BoardVersion::getId, Function.identity()));
        int order = 1;
        for (Long id : ids) {
            BoardVersion v = map.get(id);
            if (v == null) throw ApiException.bad("다른 워크스페이스의 버전입니다.");
            v.setSortOrder(order++);
            partsDao.updateVersion(v);
        }
    }

    private static void applyVersion(BoardVersion v, VersionRequest req) {
        v.setName(req.name().trim());
        v.setDescription(req.description());
        v.setStartDate(req.startDate());
        v.setDueDate(req.dueDate());
        if (v.getStartDate() != null && v.getDueDate() != null && v.getStartDate().isAfter(v.getDueDate())) throw ApiException.bad("시작일은 마감일보다 늦을 수 없습니다.");
        if (req.status() != null) {
            if (!Set.of("OPEN", "CLOSED").contains(req.status())) throw ApiException.bad("상태는 OPEN 또는 CLOSED 입니다.");
            v.setStatus(req.status());
        }
    }

    // ---------- 멤버 ----------
    @Transactional(readOnly = true)
    public List<BoardMemberDto> getMembers(Long boardId) {
        return partsDao.selectMembersByBoard(boardId).stream()
                .map(m -> new BoardMemberDto(m.getUser().getId(), m.getUser().getUsername(), m.getUser().getName(),
                        m.getUser().getDepartment(), m.getUser().isActive(), m.getRole()))
                .sorted(Comparator.comparing((BoardMemberDto x) -> !"OWNER".equals(x.role())).thenComparing(BoardMemberDto::name))
                .toList();
    }

    /** 멤버 목록 통째로 교체 + 공개 여부 */
    public List<BoardMemberDto> setMembers(Long boardId, BoardMembersRequest req, UserPrincipal me) {
        Board b = getBoard(boardId);
        checkOwner(b, me);
        List<String> ch = new ArrayList<>();
        if (req.isPublic() != null && req.isPublic() != b.isPublic()) ch.add("공개 범위: " + (b.isPublic() ? "공개" : "멤버 전용") + " → " + (req.isPublic() ? "공개" : "멤버 전용"));
        if (req.isPublic() != null) { b.setPublicBoard(req.isPublic()); boardDao.update(b); }
        List<BoardMemberItem> list = req.members() == null ? List.of() : req.members();
        Map<Long, BoardMember> cur = partsDao.selectMembersByBoard(boardId).stream()
                .collect(Collectors.toMap(m -> m.getUser().getId(), Function.identity()));
        Set<Long> keep = new HashSet<>();
        for (BoardMemberItem m : list) {
            if (m.userId() == null || !keep.add(m.userId())) continue;
            String role = "OWNER".equals(m.role()) ? "OWNER" : "MEMBER";
            BoardMember bm = cur.get(m.userId());
            if (bm != null) {
                if (!role.equals(bm.getRole())) { partsDao.updateMemberRole(bm.getId(), role); ch.add(userName(m.userId()) + " 역할 " + roleKo(bm.getRole()) + " → " + roleKo(role)); }
            } else {
                if (userDao.selectById(m.userId()) == null) throw ApiException.notFound("사용자");
                insertMember(boardId, m.userId(), role);
                ch.add(userName(m.userId()) + " 추가(" + roleKo(role) + ")");
            }
        }
        cur.forEach((uid, m) -> { if (!keep.contains(uid)) { partsDao.deleteMember(m.getId()); ch.add(userName(uid) + " 제외"); } });
        if (!ch.isEmpty()) audit.record("BOARD_MEMBERS", b.getId(), b.getName(), "BOARD", b.getId(), b.getName(), String.join(" · ", ch));
        return getMembers(boardId);
    }

    // ---------- 보기 탭 ----------
    public ViewDto insertView(Long boardId, ViewRequest req) {
        getBoard(boardId);
        BoardView v = new BoardView();
        v.setBoardId(boardId);
        applyView(v, req);
        if (v.getName() == null || v.getName().isBlank()) throw ApiException.bad("보기 이름을 입력하세요.");
        if (v.getType() == null) throw ApiException.bad("보기 유형을 선택하세요.");
        if (partsDao.selectViewCount(boardId) >= 30) throw ApiException.bad("보기는 최대 30개까지 만들 수 있습니다.");
        v.setSortOrder(partsDao.selectViewMaxSort(boardId) + 1);
        partsDao.insertView(v);
        return ViewDto.of(v);
    }

    public ViewDto updateView(Long viewId, ViewRequest req) {
        BoardView v = partsDao.selectViewById(viewId);
        if (v == null) throw ApiException.notFound("보기");
        applyView(v, req);
        partsDao.updateView(v);
        return ViewDto.of(v);
    }

    public void deleteView(Long viewId) {
        BoardView v = partsDao.selectViewById(viewId);
        if (v == null) throw ApiException.notFound("보기");
        if (partsDao.selectViewCount(v.getBoardId()) <= 1) throw ApiException.bad("보기는 최소 1개가 있어야 합니다.");
        partsDao.deleteView(viewId);
    }

    public void setViewOrder(Long boardId, List<Long> ids) {
        Map<Long, BoardView> map = partsDao.selectViewsByBoard(boardId).stream()
                .collect(Collectors.toMap(BoardView::getId, Function.identity()));
        int order = 1;
        for (Long id : ids) {
            BoardView v = map.get(id);
            if (v == null) throw ApiException.bad("다른 워크스페이스의 보기가 포함되어 있습니다.");
            v.setSortOrder(order++);
            partsDao.updateView(v);
        }
    }

    private void applyView(BoardView v, ViewRequest req) {
        if (req.name() != null && !req.name().isBlank()) v.setName(req.name().trim());
        if (req.type() != null) {
            if (!BoardView.TYPES.contains(req.type())) throw ApiException.bad("알 수 없는 보기 유형: " + req.type());
            v.setType(req.type());
        }
        if (req.config() != null) v.setConfig(validJson(req.config()));
    }

    /** 기본 컬럼 이름·숨김·전체 순서 */
    public void setColumnConfig(Long boardId, String config, UserPrincipal me) {
        Board b = getBoard(boardId);
        checkOwner(b, me);
        String next = config == null || config.isBlank() ? null : validJson(config);
        // SR 통제 규칙은 전용 API 로만 바꾼다: 컬럼 설정 저장 시 기존 값을 그대로 유지
        try {
            var old = b.getColumnConfig() == null || b.getColumnConfig().isBlank() ? null : json.readTree(b.getColumnConfig()).get(SrPolicyService.KEY);
            var root = next == null ? json.createObjectNode() : (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(next);
            root.remove(SrPolicyService.KEY);
            if (old != null) root.set(SrPolicyService.KEY, old);
            next = root.isEmpty() ? null : json.writeValueAsString(root);
        } catch (Exception e) { throw ApiException.bad("컬럼 설정 형식이 올바르지 않습니다."); }
        b.setColumnConfig(next);
        boardDao.update(b);
    }

    /**
     * 자동화 규칙 저장 (column_config.automations). 상태·그룹은 이 워크스페이스 것, 대상 워크스페이스는 저장하는 사람이 볼 수 있는 곳만.
     * 같은 상태에 규칙은 하나만 둔다.
     */
    public void setAutomations(Long boardId, List<AutomationRule> rules, UserPrincipal me) {
        Board b = getBoard(boardId);
        checkOwner(b, me);
        List<AutomationRule> in = rules == null ? List.of() : rules;
        if (in.size() > 30) throw ApiException.bad("자동화 규칙은 30개까지 만들 수 있습니다.");
        var arr = json.createArrayNode();
        Set<Long> seen = new HashSet<>();
        for (AutomationRule r : in) {
            BoardStatus st = r.statusId() == null ? null : partsDao.selectStatusById(r.statusId());
            if (st == null || !st.getBoard().getId().equals(boardId)) throw ApiException.bad("조건 상태를 선택하세요.");
            if (!seen.add(st.getId())) throw ApiException.bad("'" + st.getLabel() + "' 상태에 규칙이 이미 있습니다.");
            var n = arr.addObject();
            n.put("statusId", st.getId());
            if ("group".equals(r.action())) {
                BoardGroup g = r.groupId() == null ? null : partsDao.selectGroupById(r.groupId());
                if (g == null || !g.getBoard().getId().equals(boardId)) throw ApiException.bad("이동할 그룹을 선택하세요.");
                n.put("action", "group"); n.put("groupId", g.getId());
            } else if ("board".equals(r.action())) {
                if (r.boardId() == null || r.boardId().equals(boardId)) throw ApiException.bad("이동할 워크스페이스를 선택하세요.");
                Board to = guard.getBoardChecked(r.boardId());
                n.put("action", "board"); n.put("boardId", to.getId());
                if (r.groupId() != null) {
                    BoardGroup g = partsDao.selectGroupById(r.groupId());
                    if (g == null || !g.getBoard().getId().equals(to.getId())) throw ApiException.bad("대상 워크스페이스의 그룹이 아닙니다.");
                    n.put("groupId", g.getId());
                }
            } else throw ApiException.bad("실행할 동작을 선택하세요.");
        }
        try {
            var root = b.getColumnConfig() == null || b.getColumnConfig().isBlank() ? json.createObjectNode()
                    : (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(b.getColumnConfig());
            if (arr.isEmpty()) root.remove("automations"); else root.set("automations", arr);
            b.setColumnConfig(root.isEmpty() ? null : json.writeValueAsString(root));
        } catch (Exception e) { throw ApiException.bad("워크스페이스 설정을 읽을 수 없습니다."); }
        boardDao.update(b);
        audit.record("BOARD_UPDATE", b.getId(), b.getName(), "BOARD", b.getId(), b.getName(), "자동화 규칙 저장 (" + in.size() + "개)");
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String roleKo(String r) { return "OWNER".equals(r) ? "관리자" : "멤버"; }
    private String userName(Long id) { AppUser u = userDao.selectById(id); return u == null ? "#" + id : u.getName(); }

    private String validJson(String s) {
        if (s.isBlank()) return null;
        try { json.readTree(s); return s; } catch (Exception e) { throw ApiException.bad("설정 형식이 올바르지 않습니다."); }
    }

    public void deleteBoard(Long id, UserPrincipal me) {
        Board b = getBoard(id);
        checkOwner(b, me);
        List<Item> allItems = itemDao.selectByBoardAll(id);
        audit.record("BOARD_DELETE", b.getId(), b.getName(), "BOARD", b.getId(), b.getName(), "워크스페이스 삭제 (휴지통 포함 업무 " + allItems.size() + "건 영구 삭제)");
        deleteItems(allItems);
        partsDao.deleteColumnsByBoard(id);
        partsDao.deleteGroupsByBoard(id);
        partsDao.deleteStatusesByBoard(id);
        dutyDao.deleteByBoard(id);
        partsDao.deleteViewsByBoard(id);
        partsDao.deleteMembersByBoard(id);
        partsDao.deleteFavoritesByBoard(id);
        partsDao.deleteBoardOrdersByBoard(id);
        partsDao.deleteTypesByBoard(id);
        partsDao.deleteVersionsByBoard(id);
        wikiDao.deleteByBoard(id);
        boardDao.delete(id);
    }

    /** 아이템과 그 하위 데이터(댓글/첨부/알림/값/관계/감시/시간) 일괄 삭제 */
    /**
     * 휴지통으로 이동 (소프트 삭제). 하위 업무도 같은 시각으로 함께 표시하고, 삭제자·사유·시각을 남긴다.
     * 업무·댓글·첨부·활동 기록은 그대로 남아 관리자가 복원할 수 있다. 권한 확인은 호출하는 쪽에서
     */
    public int softDeleteItems(List<Item> roots, String reason) {
        if (roots.isEmpty()) return 0;
        Map<Long, Item> all = new LinkedHashMap<>();
        roots.forEach(i -> all.put(i.getId(), i));
        List<Long> frontier = new ArrayList<>(all.keySet());
        while (!frontier.isEmpty()) {
            List<Item> kids = itemDao.selectByParents(frontier);
            frontier = new ArrayList<>();
            for (Item k : kids) if (k.getDeletedAt() == null && all.putIfAbsent(k.getId(), k) == null) frontier.add(k.getId());
        }
        UserPrincipal me = AuditService.currentUser();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        String why = reason == null || reason.isBlank() ? "(사유 없음)" : reason.trim();
        int n = itemDao.updateSoftDelete(all.keySet(), me == null ? null : me.id(), why, now);
        AppUser actor = me == null ? null : userDao.selectById(me.id());
        for (Item i : roots) {
            int kids = (int) all.values().stream().filter(k -> k != i && isUnder(k, i, all)).count();
            ItemUpdate log = new ItemUpdate();
            log.setItem(i); log.setAuthor(actor); log.setSystem(true);
            log.setContent("휴지통으로 이동: " + why + (kids > 0 ? " (하위 업무 " + kids + "건 포함)" : ""));
            extraDao.insertUpdate(log);
            Board ib = i.getBoard() != null && i.getBoard().getName() == null ? boardDao.selectById(i.getBoard().getId()) : i.getBoard();
            audit.record("ITEM_DELETE", ib == null ? null : ib.getId(), ib == null ? null : ib.getName(), "ITEM", i.getId(), i.getName(),
                    "휴지통으로 이동 · 사유: " + why + (kids > 0 ? " (하위 업무 " + kids + "건 포함)" : ""));
        }
        return n;
    }

    private static boolean isUnder(Item k, Item root, Map<Long, Item> all) {
        Item cur = k;
        for (int d = 0; d < 10 && cur != null && cur.getParent() != null; d++) {
            if (cur.getParent().getId().equals(root.getId())) return true;
            cur = all.get(cur.getParent().getId());
        }
        return false;
    }

    /** 영구 삭제 (하위 업무·댓글·첨부·값 모두). 일반 삭제는 softDeleteItems — 휴지통 */
    void deleteItems(List<Item> list) {
        if (list.isEmpty()) return;
        // 하위 업무도 함께 (여러 단계)
        Map<Long, Item> all = new LinkedHashMap<>();
        list.forEach(i -> all.put(i.getId(), i));
        List<Long> frontier = new ArrayList<>(all.keySet());
        while (!frontier.isEmpty()) {
            List<Item> kids = itemDao.selectByParents(frontier);
            frontier = new ArrayList<>();
            for (Item k : kids) if (all.putIfAbsent(k.getId(), k) == null) frontier.add(k.getId());
        }
        List<Long> ids = new ArrayList<>(all.keySet());
        // 영구 삭제하면 활동 기록도 함께 지워지므로, 삭제 사실은 감사 로그에 남긴다
        for (Item i : list) {
            Board ib = i.getBoard() != null && i.getBoard().getName() == null ? boardDao.selectById(i.getBoard().getId()) : i.getBoard();
            audit.record("ITEM_PURGE", ib == null ? null : ib.getId(), ib == null ? null : ib.getName(), "ITEM", i.getId(), i.getName(),
                    "업무 영구 삭제" + (all.size() > list.size() && list.size() == 1 ? " (하위 업무 " + (all.size() - 1) + "건 포함)" : ""));
        }
        if (all.size() > list.size() && list.size() > 1)
            audit.record("ITEM_PURGE", null, null, "ITEM", null, null, "하위 업무 포함 총 " + all.size() + "건 영구 삭제: " + ids);
        itemDao.deleteValuesByItems(ids);
        extraDao.deleteLinksByItems(ids);
        extraDao.deleteWatchersByItems(ids);
        extraDao.deleteTimesByItems(ids);
        List<Attachment> files = extraDao.selectAttachmentsByItems(ids);
        files.forEach(a -> storage.delete(a.getStoredName()));
        extraDao.deleteAttachmentsByItems(ids);
        extraDao.deleteUpdatesByItems(ids);
        notiDao.deleteByItems(ids);
        itemDao.updateClearParent(ids); // 자식 → 부모 참조 먼저 해제
        itemDao.deleteByIds(ids);
    }

    public List<StatusDto> setStatuses(Long boardId, List<StatusRequest> reqs, UserPrincipal me) {
        Board b = getBoard(boardId);
        checkOwner(b, me);
        if (reqs == null || reqs.isEmpty()) throw ApiException.bad("상태는 최소 1개 이상이어야 합니다.");
        if (reqs.size() > 30) throw ApiException.bad("상태는 최대 30개까지 만들 수 있습니다.");
        Map<Long, BoardStatus> existing = partsDao.selectStatusesByBoard(boardId).stream()
                .collect(Collectors.toMap(BoardStatus::getId, Function.identity()));
        Set<Long> kept = new HashSet<>();
        int order = 0;
        for (StatusRequest r : reqs) {
            BoardStatus s;
            boolean isNew = r.id() == null;
            if (!isNew) {
                s = existing.get(r.id());
                if (s == null) throw ApiException.bad("잘못된 상태 ID");
                kept.add(s.getId());
            } else {
                s = new BoardStatus();
                s.setBoard(b);
            }
            s.setLabel(r.label().trim());
            s.setColor(validColor(r.color()));
            s.setDone(r.done());
            s.setSortOrder(order++);
            if (isNew) partsDao.insertStatus(s); else partsDao.updateStatus(s);
        }
        List<Long> removed = existing.keySet().stream().filter(id -> !kept.contains(id)).toList();
        if (!removed.isEmpty()) {
            itemDao.updateClearStatus(removed);
            partsDao.deleteStatuses(removed);
        }
        audit.record("BOARD_UPDATE", b.getId(), b.getName(), "BOARD", b.getId(), b.getName(),
                "상태 설정 저장: " + reqs.stream().map(StatusRequest::label).collect(Collectors.joining(", ")));
        return partsDao.selectStatusesByBoard(boardId).stream().map(StatusDto::of).toList();
    }

    // ---------- 그룹 ----------
    public GroupDto insertGroup(Long boardId, GroupRequest req) {
        Board b = getBoard(boardId);
        BoardGroup g = new BoardGroup();
        g.setBoard(b);
        g.setName(req.name().trim());
        g.setColor(req.color() == null ? "#0061F2" : validColor(req.color()));
        g.setSortOrder(partsDao.selectGroupMaxSort(boardId) + 1);
        partsDao.insertGroup(g);
        return GroupDto.of(g);
    }

    public GroupDto updateGroup(Long groupId, GroupRequest req) {
        BoardGroup g = partsDao.selectGroupById(groupId);
        if (g == null) throw ApiException.notFound("그룹");
        g.setName(req.name().trim());
        if (req.color() != null) g.setColor(validColor(req.color()));
        partsDao.updateGroup(g);
        return GroupDto.of(g);
    }

    /**
     * 그룹 삭제: 안의 업무는 휴지통으로. 일반 멤버는 남이 등록한 업무가 든 그룹을 지울 수 없다(403).
     * 이미 휴지통에 있던 이 그룹의 업무는 남은 첫 그룹으로 옮겨 복원할 수 있게 둔다
     */
    public void deleteGroup(Long groupId, String reason) {
        BoardGroup g = partsDao.selectGroupById(groupId);
        if (g == null) throw ApiException.notFound("그룹");
        List<BoardGroup> groups = partsDao.selectGroupsByBoard(g.getBoard().getId());
        if (groups.size() <= 1) throw ApiException.bad("보드에는 최소 1개의 그룹이 필요합니다.");
        List<Item> items = itemDao.selectByGroup(groupId);
        for (Item i : items) guard.setRemoveItemChecked(i, "삭제할");
        if (!items.isEmpty() && (reason == null || reason.isBlank())) throw ApiException.bad("업무가 든 그룹을 지우려면 삭제 사유를 입력하세요.");
        List<Item> roots = items.stream().filter(i -> i.getParent() == null).toList();
        softDeleteItems(roots.isEmpty() ? items : roots, "그룹 '" + g.getName() + "' 삭제" + (reason == null || reason.isBlank() ? "" : " · " + reason.trim()));
        BoardGroup fallback = groups.stream().filter(x -> !x.getId().equals(groupId)).findFirst().orElseThrow();
        itemDao.updateGroupOfTrashed(groupId, fallback.getId());
        partsDao.deleteGroup(groupId);
        Board b = getBoard(g.getBoard().getId());
        audit.record("BOARD_UPDATE", b.getId(), b.getName(), "GROUP", groupId, g.getName(),
                "그룹 삭제 (업무 " + items.size() + "건 휴지통 이동)" + (reason == null || reason.isBlank() ? "" : " · 사유: " + reason.trim()));
    }

    public void setGroupOrder(Long boardId, List<Long> ids) {
        Map<Long, BoardGroup> map = partsDao.selectGroupsByBoard(boardId).stream()
                .collect(Collectors.toMap(BoardGroup::getId, Function.identity()));
        int order = 1;
        for (Long id : ids) {
            BoardGroup g = map.get(id);
            if (g == null) throw ApiException.bad("다른 보드의 그룹이 포함되어 있습니다.");
            g.setSortOrder(order++);
            partsDao.updateGroup(g);
        }
    }

    // ---------- 사용자 정의 컬럼 ----------
    public ColumnDto insertColumn(Long boardId, ColumnRequest req, UserPrincipal me) {
        Board b = getBoard(boardId);
        checkOwner(b, me);
        BoardColumn c = new BoardColumn();
        c.setBoard(b);
        applyColumn(c, req);
        checkRelation(c);
        c.setSortOrder(partsDao.selectColumnMaxSort(boardId) + 1);
        partsDao.insertColumn(c);
        audit.record("BOARD_UPDATE", b.getId(), b.getName(), "COLUMN", c.getId(), c.getName(), "컬럼 추가 (" + c.getType() + ")");
        return ColumnDto.of(c);
    }

    public ColumnDto updateColumn(Long columnId, ColumnRequest req, UserPrincipal me) {
        BoardColumn c = partsDao.selectColumnById(columnId);
        if (c == null) throw ApiException.notFound("컬럼");
        checkOwner(c.getBoard(), me);
        Long beforeRef = c.getRefBoardId();
        String beforeType = c.getType();
        applyColumn(c, req);
        checkRelation(c);
        if (beforeRef != null && !beforeRef.equals(c.getRefBoardId())) itemDao.deleteValuesByColumn(c.getId());
        partsDao.updateColumn(c);
        // 전화번호로 바꾸면 기존 값(엑셀에서 숫자로 들어와 0 이 빠진 번호 등)을 번호 형식으로 정리
        if ("PHONE".equals(c.getType()) && !"PHONE".equals(beforeType)) {
            for (ItemValue v : itemDao.selectValuesByColumns(List.of(c.getId()))) {
                String f = PhoneFormat.format(v.getValue());
                if (!f.equals(v.getValue())) { v.setValue(f); itemDao.updateValue(v); }
            }
        }
        return ColumnDto.of(c);
    }

    public void deleteColumn(Long columnId, UserPrincipal me) {
        BoardColumn c = partsDao.selectColumnById(columnId);
        if (c == null) throw ApiException.notFound("컬럼");
        checkOwner(c.getBoard(), me);
        Board cb = getBoard(c.getBoard().getId());
        audit.record("BOARD_UPDATE", cb.getId(), cb.getName(), "COLUMN", c.getId(), c.getName(), "컬럼 삭제 (값 함께 삭제)");
        itemDao.deleteValuesByColumn(columnId);
        partsDao.deleteColumn(columnId);
    }

    public void setColumnOrder(Long boardId, List<Long> ids, UserPrincipal me) {
        checkOwner(getBoard(boardId), me);
        Map<Long, BoardColumn> map = partsDao.selectColumnsByBoard(boardId).stream()
                .collect(Collectors.toMap(BoardColumn::getId, Function.identity()));
        int order = 1;
        for (Long id : ids) {
            BoardColumn c = map.get(id);
            if (c == null) throw ApiException.bad("다른 보드의 컬럼이 포함되어 있습니다.");
            c.setSortOrder(order++);
            partsDao.updateColumn(c);
        }
    }

    private static void applyColumn(BoardColumn c, ColumnRequest req) {
        c.setName(req.name().trim());
        if (req.type() != null) {
            if (!BoardColumn.TYPES.contains(req.type())) throw ApiException.bad("알 수 없는 컬럼 유형: " + req.type());
            c.setType(req.type());
        }
        if (req.scope() != null) {
            if (!BoardColumn.SCOPES.contains(req.scope())) throw ApiException.bad("알 수 없는 컬럼 범위: " + req.scope());
            c.setScope(req.scope());
        }
        if (req.visible() != null) c.setVisible(req.visible());
        if (RelationService.TYPE.equals(c.getType())) {
            if (req.refBoardId() != null) c.setRefBoardId(req.refBoardId());
            if (req.refBoardId() != null || req.refColumnId() != null) c.setRefColumnId(req.refColumnId());
        } else {
            c.setRefBoardId(null);
            c.setRefColumnId(null);
        }
    }

    private void checkRelation(BoardColumn c) {
        if (!RelationService.TYPE.equals(c.getType())) return;
        if (c.getRefBoardId() == null) throw ApiException.bad("연결할 워크스페이스를 선택하세요.");
        guard.getBoardChecked(c.getRefBoardId());
        if (c.getRefColumnId() != null) {
            BoardColumn rc = partsDao.selectColumnById(c.getRefColumnId());
            if (rc == null) throw ApiException.notFound("표시 컬럼");
            if (!rc.getBoard().getId().equals(c.getRefBoardId())) throw ApiException.bad("표시 컬럼은 연결할 워크스페이스의 컬럼이어야 합니다.");
        }
    }

    private void checkOwner(Board b, UserPrincipal me) {
        guard.setManageChecked(b);
    }

    static String validColor(String c) {
        if (c == null || !c.matches("#[0-9a-fA-F]{6}")) throw ApiException.bad("색상은 #RRGGBB 형식이어야 합니다.");
        return c;
    }
}
