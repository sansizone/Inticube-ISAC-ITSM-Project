package com.inticube.teamboard.service;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.domain.*;
import com.inticube.teamboard.dao.*;
import com.inticube.teamboard.web.ApiException;
import com.inticube.teamboard.web.dto.Dtos.BoardRequest;
import com.inticube.teamboard.web.dto.Dtos.BoardSummary;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * monday.com 보드 엑셀 내보내기(.xlsx) → 새 TeamBoard 워크스페이스로 이관. <b>원본 값은 바꾸지 않고 그대로 보존</b>한다.
 * <ul>
 *   <li>보드 시트: 제목 행 → (그룹명 행 → 헤더 행(Name …) → 아이템 행 … [Subitems 헤더 → 하위 아이템 행]) 반복</li>
 *   <li>Updates 시트: Item ID / Item Name / Content Type / User / Created At(UTC) / Update Content / Asset IDs / Post ID / Parent Post ID</li>
 *   <li>Name·상태·담당자·마감일·등록일·설명 등은 TeamBoard 기본 필드로, 나머지 열은 워크스페이스 사용자 정의 컬럼으로 만든다.</li>
 *   <li>하위 아이템은 실제 하위 업무(PARENT_ID), 업데이트 답글은 원 댓글 아래 답글(PARENT_ID)로 저장한다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MondayImportService {
    private static final int MAX_ITEMS = 20000;
    private static final long MAX_BYTES = 30L * 1024 * 1024;

    /**
     * 열 매핑. 앞쪽은 TeamBoard 기본 필드, 뒤쪽(TEXT~LINK)은 사용자 정의 컬럼 유형.
     * ASSIGNEE 는 첫 번째로 연결된 사람을 담당자로 지정하고, 원래 값(여러 명)은 사람 컬럼으로도 보존한다.
     */
    public enum Field {
        NAME, ITEM_ID, STATUS, ASSIGNEE, START, DUE, TIMELINE, PRIORITY, CREATED, DESCRIPTION, IGNORE,
        TEXT, LONG_TEXT, NUMBER, DATE, DATETIME, PEOPLE, DROPDOWN, LINK, PHONE;
        boolean custom() { return ordinal() >= TEXT.ordinal(); }
    }

    private static final Set<String> NAME_KEYS = Set.of("name", "item", "item name", "items", "이름", "항목", "항목 이름", "아이템", "아이템 이름", "업무", "업무명", "task", "제목");
    private static final Set<String> SUB_KEYS = Set.of("subitems", "subitem", "sub items", "하위 아이템", "하위아이템", "하위 항목", "서브아이템", "서브 아이템");
    private static final Set<String> ITEM_ID_KEYS = Set.of("item id", "item id (auto generated)", "pulse id", "항목 id", "아이템 id");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})[-./]\\s?(\\d{1,2})[-./]\\s?(\\d{1,2})");
    private static final Pattern ISO_DATE_FULL = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern ISO_DATETIME_FULL = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}(:\\d{2})?");
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,15}(\\.\\d+)?");
    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("M/d/yyyy"), DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH), DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
    };
    private static final Pattern DATE_TIME = Pattern.compile("(\\d{4})[-./](\\d{1,2})[-./](\\d{1,2})[ T]+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
    /** 업데이트 시트의 작성일: 07/October/2024  01:54:01 AM (UTC) */
    private static final Pattern MONDAY_TS = Pattern.compile("(\\d{1,2})/([A-Za-z]+)/(\\d{4})\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*([AaPp][Mm])?");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String[] COLORS = {"#6900C7", "#F4A100", "#00CFD5", "#F76400", "#E30059", "#5A6270", "#0061F2", "#00AC69"};

    private final BoardService boardService;
    private final UserService userService;
    private final BoardDao boardDao;
    private final BoardPartsDao partsDao;
    private final ItemDao itemDao;
    private final ItemExtraDao extraDao;
    private final AppUserDao userDao;
    private final ColumnConvertService convertService;
    private final AccessGuard guard;
    private final RelationService relations;

    // ───────────────────────── 결과/요청 타입 ─────────────────────────

    public record ColumnInfo(String header, Field field, List<String> samples, int filled) {}
    public record GroupInfo(String name, int items, int subitems) {}
    public record StatusInfo(String label, int count, String target) {}
    /** userId: 이름으로 찾은 기존 계정 (없으면 null) */
    public record PersonInfo(String name, int count, Long userId, boolean active) {}
    public record Preview(String boardName, List<GroupInfo> groups, List<ColumnInfo> columns, List<ColumnInfo> subColumns,
                          List<StatusInfo> statuses, List<PersonInfo> people, int itemCount, int subitemCount,
                          int updateCount, int replyCount, int unmatchedUpdates, int updateFiles, List<String> warnings) {}
    /** people 값: 사용자 ID = 그 계정에 연결, 0 = 비활성 계정 새로 만들어 연결, null = 연결 안 함(이름만 보존) */
    /**
     * views: 만들 보기 탭 유형(list·kanban…). 비우면 기본 탭 전부, 표(table)는 항상 포함. showProgress: false 면 진행률(%) 숨김.
     * targetBoardId: 있으면 새로 만들지 않고 그 워크스페이스를 엑셀 내용으로 갱신 (설정·보기·연결은 유지). deleteMissing: 갱신 때 엑셀에 없는 항목 삭제
     * links: 업무 열 이름 → 연결할 워크스페이스 ID. 그 열은 이름으로 대상 업무를 찾아 고르는 연결 컬럼이 된다 (못 찾은 값은 따로 보관)
     */
    public record Options(String boardName, Map<String, Field> columns, Map<String, Field> subColumns, Map<String, Long> people, Boolean isPublic, List<String> views, Boolean showProgress,
                          Long targetBoardId, Boolean deleteMissing, Map<String, Long> links,
                          /** 업무 열 이름 → 사람 컬럼 후보 워크스페이스 ID (예: 서비스요청자 → 고객사직원정보). 이름은 그대로 보존 */
                          Map<String, Long> peopleSources) {}
    /** 갱신(targetBoardId)일 때 items·subitems 는 새로 만든 수, updatedItems 는 기존 항목을 고친 수, deletedItems 는 삭제한 수 */
    public record Result(Long boardId, String boardName, int groups, int items, int subitems, int updates, int columns,
                         List<String> newStatuses, List<String> createdUsers, List<String> warnings,
                         boolean replaced, int updatedItems, int deletedItems) {}

    // ───────────────────────── 파싱 결과 (내부) ─────────────────────────

    private static final class PItem {
        final Map<String, String> values = new LinkedHashMap<>();
        String name;
        String mondayId;
        final List<PItem> subitems = new ArrayList<>();
        final List<PUpdate> updates = new ArrayList<>();
    }
    private static final class PGroup {
        final String name;
        final List<PItem> items = new ArrayList<>();
        PGroup(String name) { this.name = name; }
    }
    private record PUpdate(String itemId, String itemName, String author, LocalDateTime createdAt, String content,
                           boolean reply, String postId, String parentPostId, boolean hasFiles) {}
    private static final class Parsed {
        String title;
        /** 업무명으로 쓰는 열의 원래 제목 (예: '솔루션명') → 워크스페이스의 업무명 컬럼 이름으로 */
        String nameHeader;
        final List<PGroup> groups = new ArrayList<>();
        final List<String> headers = new ArrayList<>();
        final List<String> subHeaders = new ArrayList<>();
        final List<PUpdate> updates = new ArrayList<>();
        int unmatchedUpdates;
        final List<String> warnings = new ArrayList<>();
        int itemCount() { return groups.stream().mapToInt(g -> g.items.size()).sum(); }
        int subCount() { return groups.stream().flatMap(g -> g.items.stream()).mapToInt(i -> i.subitems.size()).sum(); }
    }

    // ───────────────────────── 미리보기 ─────────────────────────

    @Transactional(readOnly = true)
    public Preview getPreview(MultipartFile file) {
        Parsed p = parse(file);
        List<AppUser> all = userDao.selectAllOrderByName();
        Map<String, List<String>> itemVals = valuesOf(p, false), subVals = valuesOf(p, true);
        Map<String, Field> map = guessAll(p.headers, itemVals);
        Map<String, Field> subMap = guessAll(p.subHeaders, subVals);
        List<ColumnInfo> cols = columnInfos(p.headers, map, itemVals);
        List<ColumnInfo> subCols = columnInfos(p.subHeaders, subMap, subVals);

        Map<String, Integer> statusCount = new LinkedHashMap<>();
        Map<String, Integer> personCount = new LinkedHashMap<>();
        for (PGroup g : p.groups) for (PItem it : g.items) {
            count(it, map, statusCount, personCount);
            for (PItem s : it.subitems) count(s, subMap, statusCount, personCount);
        }
        for (PUpdate u : p.updates) if (u.author() != null && !u.author().isBlank()) personCount.merge(u.author().trim(), 0, Integer::sum);

        List<StatusInfo> st = statusCount.entrySet().stream()
                .map(e -> new StatusInfo(e.getKey(), e.getValue(), defaultStatusFor(e.getKey()))).toList();
        List<PersonInfo> people = personCount.entrySet().stream()
                .map(e -> {
                    AppUser u = matchUser(e.getKey(), all);
                    return new PersonInfo(e.getKey(), e.getValue(), u == null ? null : u.getId(), u != null && u.isActive());
                })
                .sorted(Comparator.comparing((PersonInfo x) -> x.userId() != null).thenComparing(x -> -x.count()).thenComparing(PersonInfo::name))
                .toList();
        List<GroupInfo> gi = p.groups.stream()
                .map(g -> new GroupInfo(g.name, g.items.size(), g.items.stream().mapToInt(i -> i.subitems.size()).sum())).toList();
        int matched = p.updates.size() - p.unmatchedUpdates;
        int replies = (int) p.updates.stream().filter(PUpdate::reply).count();
        int files = (int) p.updates.stream().filter(PUpdate::hasFiles).count();
        return new Preview(p.title, gi, cols, subCols, st, people, p.itemCount(), p.subCount(), matched, replies,
                p.unmatchedUpdates, files, p.warnings);
    }

    private List<ColumnInfo> columnInfos(List<String> headers, Map<String, Field> map, Map<String, List<String>> vals) {
        List<ColumnInfo> out = new ArrayList<>();
        for (String h : headers) {
            List<String> samples = new ArrayList<>();
            int filled = 0;
            for (String v : vals.getOrDefault(h, List.of())) {
                if (v.isBlank()) continue;
                filled++;
                String one = v.replaceAll("\\s+", " ");
                if (samples.size() < 3 && !samples.contains(one)) samples.add(one.length() > 60 ? one.substring(0, 60) + "…" : one);
            }
            out.add(new ColumnInfo(h, map.get(h), samples, filled));
        }
        return out;
    }

    private void count(PItem it, Map<String, Field> map, Map<String, Integer> statusCount, Map<String, Integer> personCount) {
        for (var e : it.values.entrySet()) {
            Field f = map.get(e.getKey());
            if (f == Field.STATUS && !e.getValue().isBlank()) statusCount.merge(e.getValue().trim(), 1, Integer::sum);
            if (f == Field.ASSIGNEE) for (String n : splitPeople(e.getValue())) personCount.merge(n, 1, Integer::sum);
        }
    }

    // ───────────────────────── 적용 ─────────────────────────

    public Result insertWorkspace(MultipartFile file, Options opt, UserPrincipal me) {
        if (opt.targetBoardId() != null) return updateWorkspace(file, opt, me);
        Parsed p = parse(file);
        String boardName = Optional.ofNullable(opt.boardName()).map(String::trim).filter(s -> !s.isEmpty())
                .orElse(Optional.ofNullable(p.title).orElse("엑셀 가져오기"));
        if (boardName.length() > 100) boardName = boardName.substring(0, 100);

        Map<String, List<String>> itemVals = valuesOf(p, false), subVals = valuesOf(p, true);
        Map<String, Field> colMap = merge(guessAll(p.headers, itemVals), opt.columns());
        Map<String, Field> subMap = merge(guessAll(p.subHeaders, subVals), opt.subColumns());

        // 1) 사람: 기존 계정 연결 또는 비활성 계정 생성
        Map<String, AppUser> people = new HashMap<>();
        List<String> createdUsers = new ArrayList<>();
        resolvePeople(opt, people, createdUsers);
        AppUser importer = userDao.selectById(me.id());

        // 2) 보드 (기본 상태 4개 + 그룹 1개가 생기므로 그룹은 지우고 먼데이 그룹으로 대체)
        BoardSummary bs = boardService.insertBoard(new BoardRequest(boardName, null, opt.isPublic() == null ? Boolean.TRUE : opt.isPublic()), me);
        Board board = boardDao.selectById(bs.id());
        // 선택한 보기 탭만 남김 (표는 필수)
        if (opt.views() != null && !opt.views().isEmpty()) {
            Set<String> keep = new HashSet<>(opt.views());
            keep.add("table");
            for (BoardView v : partsDao.selectViewsByBoard(board.getId()))
                if (!keep.contains(v.getType())) partsDao.deleteView(v.getId());
        }
        // 새로 만든 계정은 이 워크스페이스의 워크스페이스 멤버로 (나중에 활성화하면 바로 보이도록)
        for (AppUser u : people.values()) boardService.insertMemberIfAbsent(board.getId(), u.getId());
        partsDao.deleteGroupsByBoard(board.getId());

        // 3) 사용자 정의 컬럼
        Map<String, BoardColumn> itemCols = createColumns(board, p.headers, colMap, itemVals, "ITEM", 1);
        Map<String, BoardColumn> subCols = createColumns(board, p.subHeaders, subMap, subVals, "SUBITEM", itemCols.size() + 1);
        // 표·목록에는 엑셀에 있던 열만: 매핑되지 않은 기본 컬럼은 숨기고, 순서는 엑셀 열 순서대로
        board.setColumnConfig(getImportColumnConfig(p.headers, colMap, itemCols, p.nameHeader, !Boolean.FALSE.equals(opt.showProgress())));
        List<String> peopleNotes = applyPeopleSources(board, itemCols, opt.peopleSources());
        boardDao.update(board);

        List<BoardStatus> boardStatuses = new ArrayList<>(partsDao.selectStatusesByBoard(board.getId()));
        Map<String, BoardStatus> statusByLabel = new HashMap<>();
        for (BoardStatus s : boardStatuses) statusByLabel.put(norm(s.getLabel()), s);
        List<String> newStatuses = new ArrayList<>();

        List<String> warnings = new ArrayList<>(p.warnings);
        int[] badDates = {0};
        int itemCount = 0, subCount = 0, updCount = 0;
        List<Object[]> valueRows = new ArrayList<>();
        Ctx ctx = new Ctx(board, statusByLabel, boardStatuses, newStatuses, people, importer, badDates);
        List<PGroup> gs = p.groups;
        for (int gi = 0; gi < gs.size(); gi++) {
            PGroup pg = gs.get(gi);
            BoardGroup g = new BoardGroup();
            g.setBoard(board);
            g.setName(cut(pg.name, 100));
            g.setColor(gi == 0 ? "#0061F2" : COLORS[(gi - 1) % COLORS.length]);
            g.setSortOrder(gi + 1);
            partsDao.insertGroup(g);
            int order = 0;
            for (PItem pi : pg.items) {
                Item it = build(ctx, g, pi, colMap, itemCols, valueRows, ++order, null);
                itemCount++;
                updCount += saveUpdates(it, pi.updates, people, importer);
                int subOrder = 0;
                for (PItem si : pi.subitems) {
                    Item sub = build(ctx, g, si, subMap, subCols, valueRows, ++subOrder, it);
                    subCount++;
                    updCount += saveUpdates(sub, si.updates, people, importer);
                }
            }
        }
        if (gs.isEmpty()) {
            BoardGroup g = new BoardGroup();
            g.setBoard(board); g.setName("할 일"); g.setColor("#0061F2"); g.setSortOrder(1);
            partsDao.insertGroup(g);
        }
        insertValues(valueRows);
        boardService.batchAssignKeys(board);
        // 매핑에서 고른 워크스페이스 연결 먼저, 그다음 이름이 같은 워크스페이스 자동 연결(예: '솔루션' ↔ '솔루션관리')
        warnings.addAll(peopleNotes);
        warnings.addAll(linkChosen(board, itemCols, opt.links(), me));
        warnings.addAll(batchLinkColumns(board, itemCols, me));

        if (badDates[0] > 0) warnings.add("날짜로 읽지 못한 값 " + badDates[0] + "건이 있습니다 (기본 필드는 비워 두었습니다).");
        int files = (int) p.updates.stream().filter(PUpdate::hasFiles).count();
        if (files > 0) warnings.add("첨부파일이 있던 업데이트 " + files + "건: 엑셀에는 파일이 들어 있지 않아 파일은 옮겨지지 않았습니다(본문은 이관됨).");
        return new Result(board.getId(), board.getName(), Math.max(1, gs.size()), itemCount, subCount, updCount,
                itemCols.size() + subCols.size(), newStatuses, createdUsers, warnings, false, 0, 0);
    }

    private void resolvePeople(Options opt, Map<String, AppUser> people, List<String> createdUsers) {
        if (opt.people() == null) return;
        Map<Long, AppUser> byId = userDao.selectAllOrderByName().stream().collect(Collectors.toMap(AppUser::getId, u -> u));
        for (var e : opt.people().entrySet()) {
            Long id = e.getValue();
            String name = e.getKey() == null ? "" : e.getKey().trim();
            if (id == null || name.isEmpty()) continue;
            if (id == 0L) {
                AppUser u = userService.insertInactiveUser(name);
                people.put(norm(name), u);
                createdUsers.add(u.getName() + " (" + u.getUsername() + ")");
            } else if (byId.containsKey(id)) people.put(norm(name), byId.get(id));
        }
    }

    /**
     * 기존 워크스페이스를 엑셀 내용으로 갱신 (다시 이관).
     * 워크스페이스 이름·설정·보기 탭·컬럼 설정·연결 컬럼·멤버는 그대로 두고 업무만 맞춘다.
     * - 같은 업무 찾기: 먼데이 항목 ID(엑셀 'Item ID' 열) → 없으면 같은 이름(그룹 무관, 위에서부터 하나씩)
     * - 찾은 업무: 엑셀에 있는 열의 값으로 덮어씀(빈 칸이면 비움). 엑셀에 없는 컬럼 값·댓글·첨부·연결은 유지
     * - 못 찾은 업무: 새로 만들고 업데이트(댓글)도 옮김. 기존 업무의 업데이트는 중복을 막으려고 다시 옮기지 않음
     * - 같은 이름의 컬럼은 그대로 쓰고(연결 컬럼이면 이름으로 대상 업무를 찾아 연결), 없는 열만 새 컬럼으로
     * - deleteMissing 이면 엑셀에 없는 업무는 삭제
     * 업무 ID 가 바뀌지 않으므로 다른 워크스페이스에서 이 업무를 가리키던 연결이 끊어지지 않는다.
     */
    private Result updateWorkspace(MultipartFile file, Options opt, UserPrincipal me) {
        Board board = guard.getBoardChecked(opt.targetBoardId());
        guard.setManageChecked(board);
        Parsed p = parse(file);
        Map<String, List<String>> itemVals = valuesOf(p, false), subVals = valuesOf(p, true);
        Map<String, Field> colMap = merge(guessAll(p.headers, itemVals), opt.columns());
        Map<String, Field> subMap = merge(guessAll(p.subHeaders, subVals), opt.subColumns());

        Map<String, AppUser> people = new HashMap<>();
        List<String> createdUsers = new ArrayList<>();
        resolvePeople(opt, people, createdUsers);
        AppUser importer = userDao.selectById(me.id());
        for (AppUser u : people.values()) boardService.insertMemberIfAbsent(board.getId(), u.getId());

        // 컬럼: 같은 이름·범위의 기존 컬럼을 쓰고, 없는 열만 새로 만든다
        List<BoardColumn> existingCols = partsDao.selectColumnsByBoard(board.getId());
        int nextOrder = existingCols.stream().mapToInt(BoardColumn::getSortOrder).max().orElse(0) + 1;
        Map<String, BoardColumn> itemCols = reuseColumns(board, p.headers, colMap, itemVals, "ITEM", existingCols, nextOrder);
        nextOrder += itemCols.size();
        // 사람(후보 워크스페이스)으로 고른 열: 기존 컬럼이 연결·텍스트여도 사람 컬럼으로 바꾼 뒤 값을 넣음 (값을 쓰기 전에 해야 이름이 지워지지 않음)
        List<String> peopleNotes = applyPeopleSources(board, itemCols, opt.peopleSources());
        if (!peopleNotes.isEmpty()) boardDao.update(board);
        Map<String, BoardColumn> subCols = reuseColumns(board, p.subHeaders, subMap, subVals, "SUBITEM", existingCols, nextOrder);
        int newCols = (int) (itemCols.values().stream().filter(c -> existingCols.stream().noneMatch(x -> x.getId().equals(c.getId()))).count()
                + subCols.values().stream().filter(c -> existingCols.stream().noneMatch(x -> x.getId().equals(c.getId()))).count());

        List<BoardStatus> boardStatuses = new ArrayList<>(partsDao.selectStatusesByBoard(board.getId()));
        Map<String, BoardStatus> statusByLabel = new HashMap<>();
        for (BoardStatus s : boardStatuses) statusByLabel.putIfAbsent(norm(s.getLabel()), s);
        List<String> newStatuses = new ArrayList<>();
        List<String> warnings = new ArrayList<>(p.warnings);
        int[] badDates = {0};
        RelResolver rel = new RelResolver();
        Ctx ctx = new Ctx(board, statusByLabel, boardStatuses, newStatuses, people, importer, badDates, rel);

        // 그룹: 이름이 같으면 그대로, 없으면 뒤에 추가
        List<BoardGroup> groups = new ArrayList<>(partsDao.selectGroupsByBoard(board.getId()));
        Map<String, BoardGroup> groupByName = new HashMap<>();
        for (BoardGroup g : groups) groupByName.putIfAbsent(norm(g.getName()), g);
        int groupOrder = groups.stream().mapToInt(BoardGroup::getSortOrder).max().orElse(0);

        // 기존 업무 찾기용 색인
        List<Item> existing = itemDao.selectByBoard(board.getId());
        Map<String, Item> byExt = new HashMap<>();
        Map<String, Deque<Item>> topByName = new HashMap<>();
        Map<Long, Map<String, Deque<Item>>> subByName = new HashMap<>();
        for (Item i : existing) {
            if (i.getExternalId() != null && !i.getExternalId().isBlank()) byExt.putIfAbsent(i.getExternalId(), i);
            if (i.getParent() == null) topByName.computeIfAbsent(norm(i.getName()), k -> new ArrayDeque<>()).add(i);
            else subByName.computeIfAbsent(i.getParent().getId(), k -> new HashMap<>()).computeIfAbsent(norm(i.getName()), k -> new ArrayDeque<>()).add(i);
        }
        Set<Long> matched = new HashSet<>();
        Set<Field> itemFields = coveredFields(p.headers, colMap), subFields = coveredFields(p.subHeaders, subMap);

        int created = 0, createdSubs = 0, updated = 0, updCount = 0;
        List<Object[]> valueRows = new ArrayList<>();
        for (PGroup pg : p.groups) {
            BoardGroup g = groupByName.get(norm(pg.name));
            if (g == null) {
                g = new BoardGroup();
                g.setBoard(board); g.setName(cut(pg.name, 100));
                g.setColor(COLORS[groupOrder % COLORS.length]); g.setSortOrder(++groupOrder);
                partsDao.insertGroup(g);
                groupByName.put(norm(pg.name), g);
            }
            int order = 0;
            for (PItem pi : pg.items) {
                order++;
                Item found = find(pi, byExt, topByName.get(norm(pi.name)), matched);
                Item it;
                if (found == null) {
                    it = build(ctx, g, pi, colMap, itemCols, valueRows, order, null);
                    created++;
                    updCount += saveUpdates(it, pi.updates, people, importer);
                } else {
                    it = applyUpdate(ctx, found, g, pi, colMap, itemCols, itemFields, order, null);
                    matched.add(it.getId());
                    updated++;
                }
                Map<String, Deque<Item>> kids = subByName.getOrDefault(it.getId(), Map.of());
                int subOrder = 0;
                for (PItem si : pi.subitems) {
                    subOrder++;
                    Item sf = find(si, byExt, kids.get(norm(si.name)), matched);
                    if (sf != null && sf.getParent() != null && sf.getParent().getId().equals(it.getId())) {
                        applyUpdate(ctx, sf, g, si, subMap, subCols, subFields, subOrder, it);
                        matched.add(sf.getId());
                        updated++;
                    } else {
                        Item sub = build(ctx, g, si, subMap, subCols, valueRows, subOrder, it);
                        createdSubs++;
                        updCount += saveUpdates(sub, si.updates, people, importer);
                    }
                }
            }
        }
        insertValues(valueRows);
        boardService.batchAssignKeys(board);
        warnings.addAll(peopleNotes);
        warnings.addAll(linkChosen(board, itemCols, opt.links(), me));

        int deleted = 0;
        if (Boolean.TRUE.equals(opt.deleteMissing())) {
            List<Item> gone = existing.stream().filter(i -> !matched.contains(i.getId())).toList();
            Set<Long> goneIds = gone.stream().map(Item::getId).collect(Collectors.toSet());
            // 상위가 지워지면 하위도 같이 지워지므로, 상위가 남는 하위만 따로 넘긴다
            List<Item> roots = gone.stream().filter(i -> i.getParent() == null || !goneIds.contains(i.getParent().getId())).toList();
            deleted = gone.size();
            boardService.softDeleteItems(roots, "엑셀 재이관: 엑셀에 없는 항목 정리");
        } else {
            long missing = existing.stream().filter(i -> !matched.contains(i.getId())).count();
            if (missing > 0) warnings.add("엑셀에 없는 기존 항목 " + missing + "건은 그대로 두었습니다.");
        }
        if (rel.missed > 0) warnings.add("연결 컬럼에서 대상 워크스페이스에 없는 이름 " + rel.missed + "건은 연결하지 못했습니다.");
        if (badDates[0] > 0) warnings.add("날짜로 읽지 못한 값 " + badDates[0] + "건이 있습니다 (기본 필드는 비워 두었습니다).");
        if (updated > 0 && !p.updates.isEmpty()) warnings.add("이미 있던 항목의 업데이트(댓글)는 중복을 막기 위해 다시 옮기지 않았습니다.");
        return new Result(board.getId(), board.getName(), p.groups.size(), created, createdSubs, updCount, newCols,
                newStatuses, createdUsers, warnings, true, updated, deleted);
    }

    /** 먼데이 항목 ID 로 먼저, 없으면 같은 이름의 아직 안 쓴 항목 */
    private static Item find(PItem pi, Map<String, Item> byExt, Deque<Item> sameName, Set<Long> used) {
        if (pi.mondayId != null && !pi.mondayId.isBlank()) {
            Item i = byExt.get(cut(pi.mondayId, 50));
            if (i != null && !used.contains(i.getId())) return i;
        }
        if (sameName != null) while (!sameName.isEmpty()) {
            Item i = sameName.poll();
            if (!used.contains(i.getId())) return i;
        }
        return null;
    }

    /** 엑셀에 열이 있는 기본 필드 */
    private static Set<Field> coveredFields(List<String> headers, Map<String, Field> map) {
        Set<Field> out = EnumSet.noneOf(Field.class);
        for (String h : headers) { Field f = map.get(h); if (f != null) out.add(f); }
        return out;
    }

    /** 기존 업무에 엑셀 값 반영: 엑셀에 열이 있는 필드만 덮어쓰고(빈 칸은 비움) 나머지는 유지 */
    private Item applyUpdate(Ctx ctx, Item cur, BoardGroup g, PItem pi, Map<String, Field> map, Map<String, BoardColumn> cols,
                             Set<Field> covered, int order, Item parent) {
        Map<BoardColumn, String> custom = new LinkedHashMap<>();
        Item n = parseItem(ctx, g, pi, map, cols, order, parent, custom);
        cur.setName(n.getName());
        if (parent == null) cur.setGroup(g);
        cur.setSortOrder(order);
        if (n.getExternalId() != null) cur.setExternalId(n.getExternalId());
        if (covered.contains(Field.STATUS)) cur.setStatus(n.getStatus());
        if (covered.contains(Field.ASSIGNEE)) cur.setAssignee(n.getAssignee());
        if (covered.contains(Field.START) || covered.contains(Field.TIMELINE) || covered.contains(Field.CREATED)) cur.setStartDate(n.getStartDate());
        if (covered.contains(Field.DUE) || covered.contains(Field.TIMELINE)) cur.setDueDate(n.getDueDate());
        if (covered.contains(Field.PRIORITY)) cur.setPriority(n.getPriority());
        if (covered.contains(Field.DESCRIPTION)) cur.setDescription(n.getDescription());
        cur.setUpdatedAt(LocalDateTime.now());
        itemDao.update(cur);
        // 엑셀에 있는 컬럼만: 값이 있으면 넣고, 빈 칸이면 지움
        Map<Long, ItemValue> have = new HashMap<>();
        for (ItemValue v : itemDao.selectValuesByItem(cur.getId())) have.putIfAbsent(v.getColumnId(), v);
        for (BoardColumn c : new LinkedHashSet<>(cols.values())) {
            String v = custom.getOrDefault(c, "");
            ItemValue old = have.get(c.getId());
            if (v.isEmpty()) { if (old != null) itemDao.deleteValue(old.getId()); }
            else if (old == null) { ItemValue nv = new ItemValue(); nv.setItemId(cur.getId()); nv.setColumnId(c.getId()); nv.setValue(v); itemDao.insertValue(nv); }
            else if (!v.equals(old.getValue())) { old.setValue(v); itemDao.updateValue(old); }
        }
        return cur;
    }

    /** 같은 이름·범위의 기존 컬럼은 그대로 쓰고 없는 열만 새 컬럼으로 (새 컬럼은 맨 뒤) */
    private Map<String, BoardColumn> reuseColumns(Board board, List<String> headers, Map<String, Field> map,
                                                  Map<String, List<String>> vals, String scope, List<BoardColumn> existing, int startOrder) {
        Map<String, BoardColumn> out = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String h : headers) {
            Field f = map.get(h);
            if (!(f == Field.ASSIGNEE || (f != null && f.custom()))) continue;
            BoardColumn c = existing.stream().filter(x -> x.getScope().equals(scope) && norm(x.getName()).equals(norm(cut(h, 100)))).findFirst().orElse(null);
            if (c != null) out.put(h, c); else missing.add(h);
        }
        out.putAll(createColumns(board, missing, map, vals, scope, startOrder));
        return out;
    }

    /**
     * 매핑에서 '사람 (후보: 워크스페이스)'를 고른 열: 사람 컬럼으로 두고 후보 워크스페이스(column_config.peopleSources)를 지정.
     * 갱신 때 기존 컬럼이 연결 컬럼이면 저장된 업무 ID 를 그 업무 이름으로 바꿔 사람 컬럼으로 전환한다 (값이 사라지지 않도록).
     * board 의 columnConfig 만 고치고 저장은 호출하는 쪽에서.
     */
    private List<String> applyPeopleSources(Board board, Map<String, BoardColumn> itemCols, Map<String, Long> sources) {
        List<String> notes = new ArrayList<>();
        if (sources == null || sources.isEmpty()) return notes;
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode cfg;
        try {
            cfg = board.getColumnConfig() == null || board.getColumnConfig().isBlank() ? om.createObjectNode()
                    : (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(board.getColumnConfig());
        } catch (Exception e) { cfg = om.createObjectNode(); }
        com.fasterxml.jackson.databind.node.ObjectNode ps = cfg.has("peopleSources") && cfg.get("peopleSources").isObject()
                ? (com.fasterxml.jackson.databind.node.ObjectNode) cfg.get("peopleSources") : cfg.putObject("peopleSources");
        for (var e : sources.entrySet()) {
            BoardColumn c = itemCols.get(e.getKey());
            Board src = e.getValue() == null ? null : boardDao.selectById(e.getValue());
            if (c == null || src == null || src.getId().equals(board.getId())) continue;
            if (!"PEOPLE".equals(c.getType())) {
                if (RelationService.TYPE.equals(c.getType())) {
                    // 연결 값(업무 ID 목록) → 이름 목록
                    List<ItemValue> vals = itemDao.selectValuesByColumns(List.of(c.getId()));
                    Set<Long> ids = new HashSet<>();
                    for (ItemValue v : vals) ids.addAll(RelationService.getIds(v.getValue()));
                    Map<Long, String> names = new HashMap<>();
                    if (!ids.isEmpty()) for (Item t : itemDao.selectByIds(new ArrayList<>(ids))) names.put(t.getId(), t.getName());
                    for (ItemValue v : vals) {
                        String joined = String.join(", ", RelationService.getIds(v.getValue()).stream().map(names::get).filter(Objects::nonNull).toList());
                        if (joined.isEmpty()) itemDao.deleteValue(v.getId()); else { v.setValue(joined); itemDao.updateValue(v); }
                    }
                    c.setRefBoardId(null); c.setRefColumnId(null);
                }
                c.setType("PEOPLE");
                partsDao.updateColumn(c);
            }
            ps.put(String.valueOf(c.getId()), src.getId());
            notes.add("'" + e.getKey() + "' 열은 사람 컬럼으로 두고, 후보를 '" + src.getName() + "' 워크스페이스에서 고르도록 했습니다 (이름은 그대로 보존).");
        }
        try { board.setColumnConfig(om.writeValueAsString(cfg)); } catch (Exception ignore) { /* 그대로 */ }
        return notes;
    }

    /** 매핑에서 '연결 → 워크스페이스'를 고른 열: 텍스트로 넣은 값을 이름으로 대상 업무와 맞춰 연결 컬럼으로 바꾼다 */
    private List<String> linkChosen(Board board, Map<String, BoardColumn> itemCols, Map<String, Long> links, UserPrincipal me) {
        List<String> notes = new ArrayList<>();
        if (links == null || links.isEmpty()) return notes;
        for (var e : links.entrySet()) {
            BoardColumn c = itemCols.get(e.getKey());
            Long target = e.getValue();
            if (c == null || target == null || target.equals(board.getId())) continue;
            if (RelationService.TYPE.equals(c.getType())) continue; // 이미 연결 컬럼(갱신 때)은 위에서 이름으로 연결됨
            Board tb = boardDao.selectById(target);
            if (tb == null) continue;
            try {
                var r = convertService.updateConvert(c.getId(), target, null, false, me);
                c.setType(RelationService.TYPE); c.setRefBoardId(target); // 메모리 객체도 맞춰 둬야 아래 자동 연결이 다시 건드리지 않음
                notes.add("'" + e.getKey() + "' 열을 '" + tb.getName() + "' 연결 컬럼으로 만들었습니다 (" + r.linkedRows() + "건 연결"
                        + (r.keptColumnName() != null ? ", 못 찾은 값은 '" + r.keptColumnName() + "' 컬럼에 보관" : "") + ").");
            } catch (ApiException ex) {
                notes.add("'" + e.getKey() + "' 열을 '" + tb.getName() + "'에 연결하지 못했습니다: " + ex.getMessage());
            }
        }
        return notes;
    }

    /** 워크스페이스 이름 끝의 흔한 접미어 / 열 이름 끝의 흔한 접미어 (비교용 줄기만 남김) */
    private static final Pattern BOARD_SUFFIX = Pattern.compile("(관리|목록|리스트|현황|정보|대장)$");
    private static final Pattern HEADER_SUFFIX = Pattern.compile("(명칭|명|이름|코드|구분)$");

    private static String stem(String s, Pattern suffix) {
        String t = s == null ? "" : s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String u = suffix.matcher(t).replaceAll("");
        return u.length() >= 2 ? u : t;
    }

    /**
     * 텍스트·드롭다운 열 이름이 다른 워크스페이스 이름과 같으면(접미어 무시) 그 워크스페이스 업무를 고르는 연결 컬럼으로 바꾼다.
     * 값의 절반 이상이 대상 워크스페이스의 업무명(또는 표시 컬럼)과 일치할 때만 바꾸고, 결과는 안내 문구로 돌려준다.
     */
    private List<String> batchLinkColumns(Board board, Map<String, BoardColumn> itemCols, UserPrincipal me) {
        List<String> notes = new ArrayList<>();
        Set<Long> visible = guard.getVisibleBoardIds();
        List<Board> others = boardDao.selectAllOrderByName().stream()
                .filter(b -> !b.getId().equals(board.getId()) && AccessGuard.isVisible(b, visible)).toList();
        for (var e : itemCols.entrySet()) {
            BoardColumn c = e.getValue();
            if (!"TEXT".equals(c.getType()) && !"DROPDOWN".equals(c.getType())) continue;
            String hs = stem(e.getKey(), HEADER_SUFFIX);
            List<Board> cands = others.stream().filter(b -> stem(b.getName(), BOARD_SUFFIX).equals(hs)).limit(5).toList();
            Board best = null;
            long bestHit = 0, rows = 0;
            for (Board b : cands) {
                try {
                    var pv = convertService.getPreview(c.getId(), b.getId(), null);
                    long hit = pv.matchedRows() + pv.partialRows();
                    if (hit > bestHit) { best = b; bestHit = hit; rows = pv.rows(); }
                } catch (ApiException ex) { /* 권한 없음 등은 후보에서 제외 */ }
            }
            if (best == null || rows == 0 || bestHit * 2 < rows) continue;
            convertService.updateConvert(c.getId(), best.getId(), null, false, me);
            notes.add("'" + e.getKey() + "' 열은 '" + best.getName() + "' 워크스페이스에서 고르는 연결 컬럼으로 만들었습니다 (" + bestHit + "/" + rows + "건 일치"
                    + (bestHit < rows ? ", 못 찾은 값은 원래 글자로 따로 보관" : "") + ").");
        }
        return notes;
    }

    /** 화면의 기본 컬럼 키 (frontend Fields.jsx BUILTIN_COLUMNS 와 같은 목록) */
    private static final List<String> BUILTIN_KEYS = List.of("type", "status", "assignee", "sla", "due", "start", "priority", "duty",
            "version", "progress", "estimate", "spent", "group", "created", "resolved", "updated");

    /** 엑셀 열 매핑 → 기본 컬럼 키 (이름·설명·무시 열은 컬럼이 아님) */
    private static List<String> builtinKeysOf(Field f) {
        if (f == null) return List.of();
        return switch (f) {
            case STATUS -> List.of("status");
            case ASSIGNEE -> List.of("assignee");
            case START -> List.of("start");
            case DUE -> List.of("due");
            case TIMELINE -> List.of("start", "due");
            case PRIORITY -> List.of("priority");
            case CREATED -> List.of("created");
            default -> List.of();
        };
    }

    /**
     * 가져온 워크스페이스의 컬럼 설정(JSON): 엑셀에 있던 기본 필드·사용자 정의 컬럼만 보이고 엑셀 열 순서를 따른다.
     * { "hidden": [엑셀에 없던 기본 컬럼], "order": [엑셀 열 순서의 키], "known": [모든 기본 컬럼] }
     */
    private static String getImportColumnConfig(List<String> headers, Map<String, Field> colMap, Map<String, BoardColumn> itemCols, String nameHeader, boolean showProgress) {
        LinkedHashSet<String> order = new LinkedHashSet<>();
        for (String h : headers) {
            Field f = colMap.get(h);
            order.addAll(builtinKeysOf(f));
            BoardColumn c = itemCols.get(h);
            if (c != null) order.add("c:" + c.getId());
        }
        List<String> hidden = BUILTIN_KEYS.stream().filter(k -> !order.contains(k)).toList();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("hidden", hidden);
        cfg.put("order", new ArrayList<>(order));
        cfg.put("known", BUILTIN_KEYS);
        cfg.put("showProgress", showProgress);
        // 업무명 열 제목이 '이름·Name·업무명' 같은 일반 이름이 아니면 그대로 업무명 컬럼 이름으로 (예: 솔루션명)
        if (nameHeader != null && !nameHeader.isBlank() && !NAME_KEYS.contains(norm(nameHeader)))
            cfg.put("labels", Map.of("name", nameHeader.trim().length() > 30 ? nameHeader.trim().substring(0, 30) : nameHeader.trim()));
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(cfg);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;   // 설정 없이 기본 표시 (가져오기 자체는 계속)
        }
    }

    private record Ctx(Board board, Map<String, BoardStatus> statusByLabel, List<BoardStatus> boardStatuses, List<String> newStatuses,
                       Map<String, AppUser> people, AppUser importer, int[] badDates, RelResolver rel) {
        Ctx(Board board, Map<String, BoardStatus> statusByLabel, List<BoardStatus> boardStatuses, List<String> newStatuses,
            Map<String, AppUser> people, AppUser importer, int[] badDates) {
            this(board, statusByLabel, boardStatuses, newStatuses, people, importer, badDates, null);
        }
        /** 연결 컬럼에 들어갈 엑셀 글자(이름, 쉼표 구분) → 대상 워크스페이스 업무 ID. 못 찾은 이름은 빼고 개수만 센다 */
        String relValue(BoardColumn c, String v) { return rel == null ? "" : rel.resolve(c, v); }
    }

    /** 갱신 가져오기용: 연결 컬럼 값(이름) → 대상 업무 ID. 컬럼별 후보는 한 번만 읽는다 */
    private final class RelResolver {
        final Map<Long, Map<String, Long>> byCol = new HashMap<>();
        int missed = 0;
        String resolve(BoardColumn c, String v) {
            Map<String, Long> m = byCol.computeIfAbsent(c.getId(), k -> {
                Map<String, Long> out = new HashMap<>();
                for (var o : relations.getOptions(c.getId(), "", 100_000)) {
                    out.putIfAbsent(norm(o.label()), o.id());
                    String sub = o.sub() == null ? "" : o.sub();
                    int dot = sub.lastIndexOf(" · ");
                    if (dot > 0) out.putIfAbsent(norm(sub.substring(0, dot)), o.id()); // 표시 컬럼이 따로 있으면 업무명도
                }
                return out;
            });
            List<String> ids = new ArrayList<>();
            for (String n : splitPeople(v)) {
                if (n.matches("\\d+") && m.containsValue(Long.parseLong(n))) { ids.add(n); continue; }
                Long id = m.get(norm(n));
                if (id == null) missed++; else if (!ids.contains(String.valueOf(id))) ids.add(String.valueOf(id));
            }
            return String.join(",", ids);
        }
    }

    private static Map<String, Field> merge(Map<String, Field> guessed, Map<String, Field> chosen) {
        if (chosen != null) chosen.forEach((h, f) -> { if (f != null && guessed.containsKey(h)) guessed.put(h, f); });
        // 이름 열은 파서가 정하므로 사용자가 바꿔도 무시
        guessed.replaceAll((h, f) -> (f == Field.NAME) && !isNameHeader(h) ? Field.TEXT : f);
        return guessed;
    }

    /** 사용자 정의 컬럼 생성. ASSIGNEE 로 쓰는 열도 원래 값(여러 명) 보존을 위해 사람 컬럼으로 만든다. */
    private Map<String, BoardColumn> createColumns(Board board, List<String> headers, Map<String, Field> map,
                                                   Map<String, List<String>> vals, String scope, int startOrder) {
        Map<String, BoardColumn> out = new LinkedHashMap<>();
        int order = startOrder;
        for (String h : headers) {
            Field f = map.get(h);
            String type;
            boolean visible = true;
            if (f == Field.ASSIGNEE) {
                type = "PEOPLE";
                // 여러 명이 들어 있는 행이 있을 때만 목록에 표시 (한 명뿐이면 담당자 칸과 같음)
                visible = vals.getOrDefault(h, List.of()).stream().anyMatch(v -> splitPeople(v).size() > 1);
            } else if (f != null && f.custom()) {
                type = f.name();
                visible = f != Field.LONG_TEXT;
            } else continue;
            BoardColumn c = new BoardColumn();
            c.setBoard(board);
            c.setName(cut(h, 100));
            c.setType(type);
            c.setScope(scope);
            c.setVisible(visible);
            c.setSortOrder(order++);
            partsDao.insertColumn(c);
            out.put(h, c);
        }
        return out;
    }

    private Item build(Ctx ctx, BoardGroup g, PItem pi, Map<String, Field> map, Map<String, BoardColumn> cols,
                       List<Object[]> valueRows, int order, Item parent) {
        Map<BoardColumn, String> custom = new LinkedHashMap<>();
        Item it = parseItem(ctx, g, pi, map, cols, order, parent, custom);
        itemDao.insert(it);
        for (var e : custom.entrySet()) if (!e.getValue().isEmpty()) valueRows.add(new Object[]{it.getId(), e.getKey().getId(), e.getValue()});
        return it;
    }

    /** 엑셀 한 행 → 저장 전 업무 객체. custom 에 사용자 정의 컬럼 값(정리된 값)을 채운다 */
    private Item parseItem(Ctx ctx, BoardGroup g, PItem pi, Map<String, Field> map, Map<String, BoardColumn> cols,
                           int order, Item parent, Map<BoardColumn, String> custom) {
        Item it = new Item();
        it.setBoard(ctx.board());
        it.setGroup(g);
        it.setParent(parent);
        it.setExternalId(pi.mondayId == null || pi.mondayId.isBlank() ? null : cut(pi.mondayId, 50));
        it.setName(cut(pi.name == null || pi.name.isBlank() ? "(이름 없음)" : pi.name, 300));
        it.setSortOrder(order);
        it.setCreatedBy(ctx.importer());
        String desc = null;
        LocalDateTime created = null;
        for (var e : pi.values.entrySet()) {
            String h = e.getKey(), raw = e.getValue() == null ? "" : e.getValue();
            String v = raw.trim();
            if (v.isEmpty()) continue;
            Field f = map.getOrDefault(h, Field.TEXT);
            switch (f) {
                case STATUS -> it.setStatus(status(v, ctx));
                case ASSIGNEE -> {
                    for (String n : splitPeople(v)) {
                        AppUser u = ctx.people().get(norm(n));
                        if (u != null) { it.setAssignee(u); break; }
                    }
                }
                case START -> { LocalDate d = date(v); if (d != null) it.setStartDate(d); else ctx.badDates()[0]++; }
                case DUE -> { LocalDate d = date(v); if (d != null) it.setDueDate(d); else ctx.badDates()[0]++; }
                case TIMELINE -> {
                    List<LocalDate> ds = dates(v);
                    if (ds.isEmpty()) { ctx.badDates()[0]++; break; }
                    it.setStartDate(ds.get(0));
                    it.setDueDate(ds.get(ds.size() - 1));
                }
                case PRIORITY -> it.setPriority(priority(v));
                case CREATED -> { created = parseDateTime(v); if (created == null) ctx.badDates()[0]++; }
                case DESCRIPTION -> desc = raw.strip();
                default -> { }
            }
            BoardColumn col = cols.get(h);
            if (col != null) custom.put(col, RelationService.TYPE.equals(col.getType()) ? ctx.relValue(col, v) : customValue(col, raw));
        }
        if (it.getStartDate() != null && it.getDueDate() != null && it.getStartDate().isAfter(it.getDueDate())) it.setStartDate(null);
        if (created != null) {
            it.setCreatedAt(created);
            // 시작일 열이 없으면 등록일을 시작일로 (타임라인 표시용). 마감일보다 늦으면 넣지 않음
            if (it.getStartDate() == null && (it.getDueDate() == null || !created.toLocalDate().isAfter(it.getDueDate())))
                it.setStartDate(created.toLocalDate());
        } else if (parent != null) {
            it.setCreatedAt(parent.getCreatedAt());
        }
        LocalDateTime touched = it.getCreatedAt();
        for (PUpdate u : pi.updates) if (u.createdAt() != null && u.createdAt().isAfter(touched)) touched = u.createdAt();
        it.setUpdatedAt(touched);
        it.setDescription(desc == null ? "" : cut(desc, 100_000));
        return it;
    }

    /** 원본 값 보존. 시각이 00:00 인 날짜만 날짜로 줄이고, 숫자의 천 단위 쉼표만 뺀다. 나머지는 원래 글자 그대로 */
    private static String customValue(BoardColumn c, String raw) {
        String v = "LONG_TEXT".equals(c.getType()) ? raw.strip() : raw.trim();
        if ("DATE".equals(c.getType()) && ISO_DATETIME_FULL.matcher(v).matches() && v.substring(11).matches("00:00(:00)?")) return v.substring(0, 10);
        if ("NUMBER".equals(c.getType())) return v.replace(",", "");
        if ("PHONE".equals(c.getType())) return PhoneFormat.format(v);
        return v;
    }

    private void insertValues(List<Object[]> rows) {
        if (rows.isEmpty()) return;
        List<ItemValue> list = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            ItemValue v = new ItemValue();
            v.setItemId((Long) r[0]); v.setColumnId((Long) r[1]); v.setValue((String) r[2]);
            list.add(v);
        }
        for (int i = 0; i < list.size(); i += 500)
            itemDao.batchInsertValues(list.subList(i, Math.min(list.size(), i + 500)));
    }

    private BoardStatus status(String label, Ctx ctx) {
        String target = defaultStatusFor(label);
        BoardStatus s = ctx.statusByLabel().get(norm(target != null ? target : label));
        if (s != null) return s;
        s = new BoardStatus();
        s.setBoard(ctx.board());
        s.setLabel(cut(label.trim(), 50));
        s.setColor(COLORS[ctx.newStatuses().size() % COLORS.length]);
        s.setDone(false);
        s.setSortOrder(ctx.boardStatuses().size());
        partsDao.insertStatus(s);
        ctx.boardStatuses().add(s);
        ctx.statusByLabel().put(norm(label), s);
        ctx.newStatuses().add(s.getLabel());
        return s;
    }

    /** 먼데이 기본 상태 라벨 → 같은 의미의 TeamBoard 기본 상태. 없으면 null (원래 라벨 그대로 새 상태 추가) */
    static String defaultStatusFor(String label) {
        String l = norm(label);
        if (l.matches("done|completed?|완료")) return "완료";
        if (l.matches("working on it|in progress|진행 ?중|처리 ?중")) return "처리중";
        if (l.matches("stuck|blocked|막힘")) return "막힘";
        if (l.matches("not started|대기")) return "대기";
        return null;
    }

    static Priority priority(String v) {
        String l = norm(v);
        if (l.matches("critical.*|urgent|긴급")) return Priority.CRITICAL;
        if (l.matches("high|높음")) return Priority.HIGH;
        if (l.matches("medium|normal|보통")) return Priority.MEDIUM;
        if (l.matches("low|낮음")) return Priority.LOW;
        return null;
    }

    /** 업데이트 저장: 원글 먼저, 답글은 원글 ID 로 연결. 본문은 원본 그대로 (HTML 일 때만 태그 정리) */
    private int saveUpdates(Item it, List<PUpdate> list, Map<String, AppUser> people, AppUser importer) {
        if (list.isEmpty()) return 0;
        Map<String, Long> byPost = new HashMap<>();
        List<PUpdate> ordered = new ArrayList<>(list.stream().filter(u -> !u.reply()).toList());
        ordered.addAll(list.stream().filter(PUpdate::reply).toList());
        int n = 0;
        for (PUpdate u : ordered) {
            String content = clean(u.content());
            if (content.isEmpty()) continue;
            AppUser author = u.author() == null ? null : people.get(norm(u.author()));
            ItemUpdate up = new ItemUpdate();
            up.setItem(it);
            up.setAuthor(author != null ? author : importer);
            up.setAuthorName(u.author() == null || u.author().isBlank() ? null : cut(u.author().trim(), 100));
            up.setContent(content);
            up.setSystem(false);
            up.setExternalId(u.postId() == null || u.postId().isBlank() ? null : cut(u.postId(), 50));
            if (u.reply() && u.parentPostId() != null) up.setParentId(byPost.get(u.parentPostId()));
            if (u.createdAt() != null) up.setCreatedAt(u.createdAt());
            extraDao.insertUpdate(up);
            if (u.postId() != null && !u.postId().isBlank()) byPost.put(u.postId(), up.getId());
            n++;
        }
        return n;
    }

    // ───────────────────────── 엑셀 파싱 ─────────────────────────

    private Parsed parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.bad("파일을 선택하세요.");
        String fname = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (!fname.endsWith(".xlsx")) throw ApiException.bad(".xlsx 엑셀 파일만 가져올 수 있습니다.");
        if (file.getSize() > MAX_BYTES) throw ApiException.bad("30MB 이하 파일만 가져올 수 있습니다.");
        Parsed p = new Parsed();
        try (InputStream in = file.getInputStream(); Workbook wb = new XSSFWorkbook(in)) {
            DataFormatter fmt = new DataFormatter(Locale.KOREA);
            Sheet updateSheet = null;
            Sheet main = null;
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet s = wb.getSheetAt(i);
                if (wb.isSheetHidden(i) || wb.isSheetVeryHidden(i)) continue;
                if (findUpdateHeader(s, fmt) != null) { if (updateSheet == null) updateSheet = s; }
                else if (main == null) main = s;
            }
            if (main == null) throw ApiException.bad("보드 시트를 찾지 못했습니다. 보드를 엑셀로 내보낸 파일인지 확인하세요.");
            parseMain(main, fmt, p);
            if (p.headers.isEmpty()) throw ApiException.bad("'Name'(이름) 헤더 행을 찾지 못했습니다. 보드를 엑셀로 내보낸 파일인지 확인하세요.");
            if (p.itemCount() + p.subCount() > MAX_ITEMS) throw ApiException.bad("한 번에 " + MAX_ITEMS + "건까지 가져올 수 있습니다. 그룹별로 나눠 내보내세요.");
            if (updateSheet != null) parseUpdates(updateSheet, fmt, p);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("엑셀 파일을 읽을 수 없습니다. .xlsx 엑셀 파일인지 확인하세요.");
        }
        if (p.title == null) {
            String n = Optional.ofNullable(file.getOriginalFilename()).orElse("엑셀 가져오기");
            p.title = n.replaceAll("(?i)\\.xlsx$", "").replaceAll("_\\d{10,}$", "").trim();
        }
        return p;
    }

    private void parseMain(Sheet s, DataFormatter fmt, Parsed p) {
        List<List<String>> rows = new ArrayList<>();
        for (int r = 0; r <= s.getLastRowNum(); r++) rows.add(cells(s.getRow(r), fmt));

        List<String> header = null, subHeader = null;
        int subName = 1, nameCol = 0;
        PGroup group = null;
        PItem last = null;
        for (int r = 0; r < rows.size(); r++) {
            List<String> c = rows.get(r);
            List<Integer> filled = filled(c);
            if (filled.isEmpty()) { subHeader = null; continue; }
            String first = c.get(filled.get(0));
            if (filled.size() == 1 && first.toLowerCase(Locale.ROOT).contains("monday.com")) continue; // Powered by monday.com

            if (filled.get(0) <= 1 && SUB_KEYS.contains(norm(c.get(filled.get(0)))) && filled.size() >= 2) {   // 하위 아이템 헤더
                subHeader = c;
                subName = filled.get(0) + 1;
                for (int i = 1; i < c.size(); i++) if (NAME_KEYS.contains(norm(c.get(i)))) { subName = i; break; }
                for (int i = 1; i < c.size(); i++) addHeader(p.subHeaders, c.get(i));
                continue;
            }
            if (isMainHeader(c, filled)) {
                header = c;
                subHeader = null;
                nameCol = 0;
                for (int i = 0; i < c.size(); i++) if (NAME_KEYS.contains(norm(c.get(i)))) { nameCol = i; break; }
                if (p.nameHeader == null && nameCol < c.size()) p.nameHeader = c.get(nameCol);
                for (String h : c) addHeader(p.headers, h);
                if (group == null) { group = new PGroup("할 일"); p.groups.add(group); }
                continue;
            }
            if (filled.size() == 1 && filled.get(0) == 0) {
                List<String> next = nextFilled(rows, r);
                if (next != null && isMainHeader(next, filled(next))) {
                    group = new PGroup(first);
                    p.groups.add(group);
                    last = null; subHeader = null;
                    continue;
                }
                if (header == null) { if (p.title == null) p.title = first; continue; }
            }
            if (header == null) {
                if (p.title == null && filled.size() == 1) p.title = first;
                continue;
            }
            if (subHeader != null && c.get(0).isEmpty()) {  // 하위 아이템
                if (last == null) continue;
                PItem si = new PItem();
                for (int i = 1; i < subHeader.size() && i < c.size(); i++) {
                    String h = subHeader.get(i);
                    if (h.isEmpty()) continue;
                    if (i == subName) si.name = c.get(i);
                    else {
                        if (ITEM_ID_KEYS.contains(norm(h))) si.mondayId = c.get(i);
                        si.values.merge(h, c.get(i), (a, b) -> a.isEmpty() ? b : a);
                    }
                }
                if (si.name == null || si.name.isBlank()) continue;   // 합계 행 등
                last.subitems.add(si);
                continue;
            }
            subHeader = null;
            String name = nameCol < c.size() ? c.get(nameCol) : "";
            if (name.isEmpty()) continue; // 합계 행 등
            PItem it = new PItem();
            it.name = name;
            for (int i = 0; i < header.size() && i < c.size(); i++) {
                String h = header.get(i);
                if (h.isEmpty() || i == nameCol) continue;
                if (ITEM_ID_KEYS.contains(norm(h))) it.mondayId = c.get(i);
                if (NAME_KEYS.contains(norm(h)) || SUB_KEYS.contains(norm(h))) continue;
                it.values.merge(h, c.get(i), (a, b) -> a.isEmpty() ? b : a);
            }
            group.items.add(it);
            last = it;
        }
        p.subHeaders.removeIf(h -> NAME_KEYS.contains(norm(h)) || SUB_KEYS.contains(norm(h)));
        p.headers.removeIf(h -> NAME_KEYS.contains(norm(h)) || SUB_KEYS.contains(norm(h)));
    }

    private static boolean isMainHeader(List<String> c, List<Integer> filled) {
        if (filled.size() < 2) return false;
        return NAME_KEYS.contains(norm(c.get(filled.get(0)))) && filled.get(0) <= 1;
    }

    private static boolean isNameHeader(String h) { return NAME_KEYS.contains(norm(h)); }

    private static List<String> nextFilled(List<List<String>> rows, int r) {
        for (int i = r + 1; i < rows.size(); i++) if (!filled(rows.get(i)).isEmpty()) return rows.get(i);
        return null;
    }

    private static void addHeader(List<String> list, String h) {
        if (h != null && !h.isBlank() && !list.contains(h)) list.add(h);
    }

    /** 업데이트 시트 헤더 위치. 키: row,id,name,content,user,created,type,type2,post,parent,assets */
    private static Map<String, Integer> findUpdateHeader(Sheet s, DataFormatter fmt) {
        for (int r = 0; r <= Math.min(s.getLastRowNum(), 10); r++) {
            List<String> c = cells(s.getRow(r), fmt);
            Map<String, Integer> m = new HashMap<>();
            for (int i = 0; i < c.size(); i++) {
                String h = norm(c.get(i));
                if (h.isEmpty()) continue;
                if (ITEM_ID_KEYS.contains(h)) m.putIfAbsent("id", i);
                else if (Set.of("item name", "item", "항목 이름", "아이템 이름", "항목", "name", "이름").contains(h)) m.putIfAbsent("name", i);
                else if (h.contains("update content") || Set.of("content", "text", "body", "update", "업데이트 내용", "업데이트", "내용", "본문").contains(h)) m.putIfAbsent("content", i);
                else if (Set.of("user", "created by", "author", "creator", "작성자", "사용자", "by").contains(h)) m.putIfAbsent("user", i);
                else if (Set.of("created at", "created", "date", "작성일", "생성일", "날짜", "작성 시간").contains(h)) m.putIfAbsent("created", i);
                else if (Set.of("content type", "type", "유형").contains(h)) { if (m.containsKey("type")) m.putIfAbsent("type2", i); else m.put("type", i); }
                else if (Set.of("post id", "update id", "게시물 id").contains(h)) m.putIfAbsent("post", i);
                else if (Set.of("parent post id", "parent id", "상위 게시물 id").contains(h)) m.putIfAbsent("parent", i);
                else if (Set.of("asset ids", "assets", "files", "첨부파일").contains(h)) m.putIfAbsent("assets", i);
            }
            if (m.containsKey("content") && (m.containsKey("id") || m.containsKey("name"))) { m.put("row", r); return m; }
        }
        return null;
    }

    private void parseUpdates(Sheet s, DataFormatter fmt, Parsed p) {
        Map<String, Integer> h = findUpdateHeader(s, fmt);
        if (h == null) return;
        Map<String, PItem> byId = new HashMap<>();
        Map<String, PItem> byName = new HashMap<>();
        for (PItem it : allItems(p, true)) if (it.mondayId != null && !it.mondayId.isBlank()) byId.putIfAbsent(it.mondayId, it);
        for (PItem it : allItems(p, false)) byName.putIfAbsent(norm(it.name), it);
        for (int r = h.get("row") + 1; r <= s.getLastRowNum(); r++) {
            Row row = s.getRow(r);
            List<String> c = cells(row, fmt);
            String content = col(c, h, "content");
            if (content.isBlank()) continue;
            String id = col(c, h, "id"), name = col(c, h, "name");
            String type = norm(col(c, h, "type") + " " + col(c, h, "type2"));
            String parent = col(c, h, "parent");
            boolean reply = type.contains("reply") || type.contains("답글") || !parent.isBlank();
            LocalDateTime created = h.containsKey("created") ? dateTime(row, h.get("created"), col(c, h, "created")) : null;
            PUpdate u = new PUpdate(id, name, col(c, h, "user"), created, content, reply,
                    col(c, h, "post"), parent.isBlank() ? null : parent, !col(c, h, "assets").isBlank());
            PItem target = !id.isEmpty() ? byId.get(id) : null;
            if (target == null && !name.isEmpty() && id.isEmpty()) target = byName.get(norm(name));
            p.updates.add(u);
            if (target != null) target.updates.add(u); else p.unmatchedUpdates++;
        }
        if (p.unmatchedUpdates > 0)
            p.warnings.add("보드에 없는 아이템의 업데이트 " + p.unmatchedUpdates + "건은 가져오지 않습니다.");
        for (PItem it : allItems(p, true))
            it.updates.sort(Comparator.comparing(PUpdate::createdAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private static String col(List<String> c, Map<String, Integer> h, String key) {
        Integer i = h.get(key);
        return i == null ? "" : at(c, i);
    }

    private static List<PItem> allItems(Parsed p, boolean withSub) {
        List<PItem> out = new ArrayList<>();
        for (PGroup g : p.groups) for (PItem it : g.items) { out.add(it); if (withSub) out.addAll(it.subitems); }
        return out;
    }

    /** 열별 값 목록 (sub=false: 업무, true: 하위 아이템) */
    private static Map<String, List<String>> valuesOf(Parsed p, boolean sub) {
        Map<String, List<String>> m = new HashMap<>();
        for (PGroup g : p.groups) for (PItem it : g.items) {
            List<PItem> src = sub ? it.subitems : List.of(it);
            for (PItem x : src) x.values.forEach((k, v) -> m.computeIfAbsent(k, kk -> new ArrayList<>()).add(v));
        }
        return m;
    }

    private static List<String> cells(Row row, DataFormatter fmt) {
        List<String> out = new ArrayList<>();
        if (row == null) return out;
        for (int i = 0; i < Math.max(0, row.getLastCellNum()); i++) out.add(cellText(row.getCell(i), fmt));
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    /** 셀 값을 원본 그대로 문자열로 (날짜는 yyyy-MM-dd HH:mm[:ss], 숫자는 지수 표기 없이) */
    private static String cellText(Cell cell, DataFormatter fmt) {
        if (cell == null) return "";
        try {
            CellType t = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
            if (t == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                LocalDateTime dt = cell.getLocalDateTimeCellValue();
                return dt.format(DateTimeFormatter.ofPattern(dt.getSecond() == 0 ? "yyyy-MM-dd HH:mm" : "yyyy-MM-dd HH:mm:ss"));
            }
            if (t == CellType.NUMERIC) return new java.math.BigDecimal(String.valueOf(cell.getNumericCellValue())).stripTrailingZeros().toPlainString();
            if (t == CellType.STRING) return cell.getStringCellValue().strip();
        } catch (Exception ignored) { /* 아래 기본 서식 */ }
        return fmt.formatCellValue(cell).strip();
    }

    private static List<Integer> filled(List<String> c) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) if (!c.get(i).isEmpty()) out.add(i);
        return out;
    }

    private static String at(List<String> c, int i) { return i >= 0 && i < c.size() ? c.get(i) : ""; }

    // ───────────────────────── 열 종류 추정 ─────────────────────────

    private static final Set<Field> SINGLE = EnumSet.of(Field.STATUS, Field.ASSIGNEE, Field.START, Field.DUE, Field.TIMELINE,
            Field.PRIORITY, Field.CREATED, Field.DESCRIPTION, Field.ITEM_ID);

    /**
     * 헤더·값으로 열 종류 추정. 기본 필드는 가장 그럴듯한 열 하나만 쓰고(점수 2 이상),
     * 나머지는 값 모양에 맞는 사용자 정의 컬럼 유형으로.
     */
    private static Map<String, Field> guessAll(List<String> headers, Map<String, List<String>> vals) {
        Map<String, Field> m = new LinkedHashMap<>();
        Map<Field, String> best = new EnumMap<>(Field.class);
        for (String h : headers) {
            Field f = guess(h);
            m.put(h, f);
            if (SINGLE.contains(f) && score(h, f) >= 2 && (!best.containsKey(f) || score(h, f) > score(best.get(f), f))) best.put(f, h);
        }
        m.replaceAll((h, f) -> {
            if (f == Field.IGNORE || f == Field.NAME) return f;
            if (SINGLE.contains(f) && h.equals(best.get(f))) return f;
            return inferType(h, vals.getOrDefault(h, List.of()));
        });
        return m;
    }

    private static int score(String header, Field f) {
        String h = norm(header).replaceAll("\\s+", "");
        return switch (f) {
            case ASSIGNEE -> h.matches("담당자|처리담당자|작업담당자|담당|person|people|owner|assignee|assignees") ? 3 : h.endsWith("담당자") ? 2 : 1;
            case STATUS -> h.matches("status|상태|작업상태|진행상태|처리상태") ? 3 : 2;
            case DUE -> h.matches(".*(납기|due|deadline|마감|기한).*") ? 3 : h.matches("date|날짜") ? 2 : 1;
            case CREATED -> h.matches("등록일|등록일시|요청일|접수일|생성일") ? 3 : 2;
            case DESCRIPTION -> h.matches("요청내용|요청사항|description|설명|내용") ? 3 : 1;
            case ITEM_ID, TIMELINE, PRIORITY, START -> 2;
            default -> 1;
        };
    }

    static Field guess(String header) {
        String h = norm(header);
        if (NAME_KEYS.contains(h)) return Field.NAME;
        if (ITEM_ID_KEYS.contains(h)) return Field.ITEM_ID;
        if (h.matches(".*(creation log|last updated|생성 로그|최근 업데이트|마지막 업데이트|자동 번호|auto number).*")) return Field.IGNORE;
        if (h.matches("(등록|요청|접수|생성)(일|일시|일자)|created( at)?|creation date|date created")) return Field.CREATED;
        if (h.matches(".*(timeline|타임라인).*")) return Field.TIMELINE;
        if (h.matches(".*(priority|우선 ?순위).*")) return Field.PRIORITY;
        if (h.matches(".*(status|상태).*")) return Field.STATUS;
        if (h.matches(".*(person|people|owner|assignee|담당자).*") || h.equals("담당")) return Field.ASSIGNEE;
        if (h.matches(".*(start date|시작일).*")) return Field.START;
        if (h.matches(".*(due|deadline|마감|기한|납기).*") || h.equals("date") || h.equals("날짜")) return Field.DUE;
        if (h.matches(".*(description|설명|요청 ?내용|요청 ?사항).*") || h.equals("내용")) return Field.DESCRIPTION;
        return Field.TEXT;
    }

    /** 사용자 정의 컬럼 유형 추정 (값 모양 기준) */
    static Field inferType(String header, List<String> vals) {
        String h = norm(header);
        List<String> filled = vals.stream().map(String::trim).filter(v -> !v.isEmpty()).toList();
        boolean linkName = h.matches(".*(link|url|링크).*");
        // 전화번호: 엑셀이 숫자로 바꿔 앞자리 0 이 빠지는 일이 많아 이름으로 먼저 판단
        // 번호가 아닌 값(내선 123 등)은 PHONE 이어도 원래 글자 그대로 저장되므로 번호가 하나라도 있으면 PHONE
        if (h.matches(PhoneFormat.HEADER_PATTERN) && filled.stream().noneMatch(v -> v.length() > 60)
                && (filled.isEmpty() || filled.stream().anyMatch(v -> v.replaceAll("\\D", "").length() >= 7))) return Field.PHONE;
        if (filled.isEmpty()) return linkName ? Field.LINK : h.matches(".*(날짜|일자|date|일)$") ? Field.DATE : Field.TEXT;
        if (filled.stream().anyMatch(v -> v.contains("\n") || v.length() > 120)) return Field.LONG_TEXT;
        if (filled.stream().allMatch(v -> ISO_DATE_FULL.matcher(v).matches() || ISO_DATETIME_FULL.matcher(v).matches())) {
            return filled.stream().anyMatch(v -> v.length() > 10 && !v.substring(11).matches("00:00(:00)?")) ? Field.DATETIME : Field.DATE;
        }
        if (linkName) return Field.LINK;
        if (!h.matches(".*\\bid\\b.*|.*번호.*|.*코드.*") && filled.stream().allMatch(v -> NUMBER.matcher(v.replace(",", "")).matches())) return Field.NUMBER;
        if (h.matches(".*(담당|요청자|접수자|작성자|처리자|person|owner|\\bam\\b).*")) return Field.PEOPLE;
        long distinct = filled.stream().distinct().count();
        if (distinct <= 200 && distinct * 8 <= filled.size()) return Field.DROPDOWN;
        return Field.TEXT;
    }

    static List<String> splitPeople(String v) {
        if (v == null || v.isBlank()) return List.of();
        return Arrays.stream(v.split("[,;\\n]")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    /** 이름(또는 아이디)이 정확히 같은 계정. 동명이인이면 활성 계정 우선 */
    static AppUser matchUser(String name, List<AppUser> list) {
        String n = norm(name);
        AppUser found = null;
        for (AppUser u : list) {
            if (norm(u.getName()).equals(n) || norm(u.getUsername()).equals(n)) {
                if (found == null || (!found.isActive() && u.isActive())) found = u;
            }
        }
        return found;
    }

    static LocalDate date(String v) {
        if (v == null || v.isBlank()) return null;
        Matcher m = ISO_DATE.matcher(v);
        if (m.find()) {
            try { return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))); }
            catch (Exception ignored) { return null; }
        }
        String t = v.trim();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try { return LocalDate.parse(t, f); } catch (DateTimeParseException ignored) { /* 다음 */ }
        }
        return null;
    }

    static List<LocalDate> dates(String v) {
        List<LocalDate> out = new ArrayList<>();
        Matcher m = ISO_DATE.matcher(v);
        while (m.find()) {
            try { out.add(LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)))); }
            catch (Exception ignored) { /* 잘못된 날짜 */ }
        }
        if (out.isEmpty()) {
            for (String part : v.split("\\s+[-~–]\\s+|\\s*~\\s*")) { LocalDate d = date(part); if (d != null) out.add(d); }
        }
        return out;
    }

    private static LocalDateTime dateTime(Row row, int col, String text) {
        try {
            Cell cell = row == null ? null : row.getCell(col);
            if (cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) return cell.getLocalDateTimeCellValue();
        } catch (Exception ignored) { /* 문자열로 */ }
        LocalDateTime mts = mondayTimestamp(text);
        return mts != null ? mts : parseDateTime(text);
    }

    /** 07/October/2024  01:54:01 AM — 먼데이 업데이트 시트는 UTC 기준이라 한국 시간으로 변환 */
    static LocalDateTime mondayTimestamp(String text) {
        if (text == null) return null;
        Matcher m = MONDAY_TS.matcher(text);
        if (!m.find()) return null;
        try {
            String mon = m.group(2).toUpperCase(Locale.ROOT);
            if (mon.length() < 3) return null;
            Month month = Arrays.stream(Month.values()).filter(x -> x.name().startsWith(mon.substring(0, 3))).findFirst().orElse(null);
            if (month == null) return null;
            int hour = Integer.parseInt(m.group(4));
            String ap = m.group(7);
            if (ap != null) { boolean pm = ap.equalsIgnoreCase("pm"); if (hour == 12) hour = pm ? 12 : 0; else if (pm) hour += 12; }
            LocalDateTime utc = LocalDateTime.of(Integer.parseInt(m.group(3)), month, Integer.parseInt(m.group(1)), hour,
                    Integer.parseInt(m.group(5)), m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
            return utc.atZone(ZoneId.of("UTC")).withZoneSameInstant(KST).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    static LocalDateTime parseDateTime(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = DATE_TIME.matcher(text);
        if (m.find()) {
            try {
                return LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                        Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
            } catch (Exception ignored) { /* 아래 */ }
        }
        LocalDate d = date(text);
        return d == null ? null : d.atStartOfDay();
    }

    /** 업데이트 본문: 일반 텍스트는 그대로, HTML 로 내보낸 경우에만 태그 정리 */
    static String clean(String s) {
        if (s == null) return "";
        if (!s.matches("(?is).*(</\\w+>|<br\\s*/?>|<p[ >]|<div[ >]|&nbsp;).*")) return s.strip();
        String t = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>|</div>|</li>", "\n").replaceAll("(?i)<li>", "- ")
                .replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        return t.replaceAll("\n{3,}", "\n\n").strip();
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
