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
        TEXT, LONG_TEXT, NUMBER, DATE, DATETIME, PEOPLE, DROPDOWN, LINK;
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
    public record Options(String boardName, Map<String, Field> columns, Map<String, Field> subColumns, Map<String, Long> people, Boolean isPublic) {}
    public record Result(Long boardId, String boardName, int groups, int items, int subitems, int updates, int columns,
                         List<String> newStatuses, List<String> createdUsers, List<String> warnings) {}

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
        if (opt.people() != null) {
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
        AppUser importer = userDao.selectById(me.id());

        // 2) 보드 (기본 상태 4개 + 그룹 1개가 생기므로 그룹은 지우고 먼데이 그룹으로 대체)
        BoardSummary bs = boardService.insertBoard(new BoardRequest(boardName, null, opt.isPublic() == null ? Boolean.TRUE : opt.isPublic()), me);
        Board board = boardDao.selectById(bs.id());
        // 새로 만든 계정은 이 워크스페이스의 워크스페이스 멤버로 (나중에 활성화하면 바로 보이도록)
        for (AppUser u : people.values()) boardService.insertMemberIfAbsent(board.getId(), u.getId());
        partsDao.deleteGroupsByBoard(board.getId());

        // 3) 사용자 정의 컬럼
        Map<String, BoardColumn> itemCols = createColumns(board, p.headers, colMap, itemVals, "ITEM", 1);
        Map<String, BoardColumn> subCols = createColumns(board, p.subHeaders, subMap, subVals, "SUBITEM", itemCols.size() + 1);

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

        if (badDates[0] > 0) warnings.add("날짜로 읽지 못한 값 " + badDates[0] + "건이 있습니다 (기본 필드는 비워 두었습니다).");
        int files = (int) p.updates.stream().filter(PUpdate::hasFiles).count();
        if (files > 0) warnings.add("첨부파일이 있던 업데이트 " + files + "건: 엑셀에는 파일이 들어 있지 않아 파일은 옮겨지지 않았습니다(본문은 이관됨).");
        return new Result(board.getId(), board.getName(), Math.max(1, gs.size()), itemCount, subCount, updCount,
                itemCols.size() + subCols.size(), newStatuses, createdUsers, warnings);
    }

    private record Ctx(Board board, Map<String, BoardStatus> statusByLabel, List<BoardStatus> boardStatuses, List<String> newStatuses,
                       Map<String, AppUser> people, AppUser importer, int[] badDates) {}

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
        Map<BoardColumn, String> custom = new LinkedHashMap<>();
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
            if (col != null) custom.put(col, customValue(col, raw));
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
        itemDao.insert(it);
        for (var e : custom.entrySet()) if (!e.getValue().isEmpty()) valueRows.add(new Object[]{it.getId(), e.getKey().getId(), e.getValue()});
        return it;
    }

    /** 원본 값 보존. 시각이 00:00 인 날짜만 날짜로 줄이고, 숫자의 천 단위 쉼표만 뺀다. 나머지는 원래 글자 그대로 */
    private static String customValue(BoardColumn c, String raw) {
        String v = "LONG_TEXT".equals(c.getType()) ? raw.strip() : raw.trim();
        if ("DATE".equals(c.getType()) && ISO_DATETIME_FULL.matcher(v).matches() && v.substring(11).matches("00:00(:00)?")) return v.substring(0, 10);
        if ("NUMBER".equals(c.getType())) return v.replace(",", "");
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
        if (l.matches("working on it|in progress|진행 ?중")) return "진행중";
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
