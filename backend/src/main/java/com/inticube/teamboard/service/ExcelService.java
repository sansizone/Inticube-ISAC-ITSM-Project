package com.inticube.teamboard.service;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.domain.*;
import com.inticube.teamboard.dao.*;
import com.inticube.teamboard.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 워크스페이스 업무 엑셀 내보내기/가져오기.
 * <ul>
 *   <li>내보내기: 빈 양식 또는 현재 업무 목록. 그룹·상태·담당자·우선순위는 드롭다운으로 선택</li>
 *   <li>가져오기: '번호'가 있으면 해당 업무 수정, 비어 있으면 새 업무 생성. 없는 그룹명은 새 그룹으로 생성</li>
 *   <li>한 행이라도 오류가 있으면 아무것도 저장하지 않음 (미리보기에서 오류 목록 확인)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ExcelService {
    public static final String SHEET_TASKS = "업무";
    private static final String SHEET_LISTS = "목록";
    private static final String SHEET_GUIDE = "작성 안내";
    private static final String SHEET_META = "_meta";
    private static final String[] HEADERS = {"번호", "그룹", "담당업무", "업무명", "상태", "담당자", "우선순위", "시작일", "마감일", "설명"};
    private static final int[] WIDTHS = {8, 16, 28, 42, 12, 20, 10, 13, 13, 50};
    private static final int TEMPLATE_ROWS = 500;
    private static final int MAX_ROWS = 3000;
    private static final Map<String, Priority> PRIORITY_BY_LABEL = new LinkedHashMap<>();
    private static final Pattern USER_IN_PAREN = Pattern.compile("\\(([^()]+)\\)\\s*$");
    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-M-d"), DateTimeFormatter.ofPattern("yyyy.M.d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"), DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yyyy. M. d"), DateTimeFormatter.ofPattern("yyyy. M. d.")
    };

    static {
        PRIORITY_BY_LABEL.put("긴급", Priority.CRITICAL);
        PRIORITY_BY_LABEL.put("높음", Priority.HIGH);
        PRIORITY_BY_LABEL.put("보통", Priority.MEDIUM);
        PRIORITY_BY_LABEL.put("낮음", Priority.LOW);
    }

    private final BoardService boardService;
    private final BoardPartsDao partsDao;
    private final ItemDao itemDao;
    private final ItemExtraDao extraDao;
    private final AppUserDao userDao;
    private final BoardDutyDao dutyDao;
    private final ItemService itemService;
    private final NotificationService notifier;
    private final DutyService dutyService;
    private final RelationService relations;

    // =========================================================
    // 내보내기
    // =========================================================
    @Transactional(readOnly = true)
    public byte[] getExport(Long boardId, boolean template) { return getExport(boardId, template, null); }

    /** onlyIds: 지정하면 그 업무(와 그 하위 업무)만 내보냄 */
    public byte[] getExport(Long boardId, boolean template, Set<Long> onlyIds) {
        Board board = boardService.getBoard(boardId);
        List<BoardGroup> gs = partsDao.selectGroupsByBoard(boardId);
        List<BoardStatus> ss = partsDao.selectStatusesByBoard(boardId);
        List<AppUser> us = userDao.selectActiveOrderByName();
        Map<Long, String> dutyPaths = DutyService.getPaths(dutyDao.selectByBoard(boardId));

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            Sheet sheet = wb.createSheet(SHEET_TASKS);
            Row head = sheet.createRow(0);
            head.setHeightInPoints(22);
            for (int c = 0; c < HEADERS.length; c++) {
                Cell cell = head.createCell(c);
                cell.setCellValue(HEADERS[c]);
                cell.setCellStyle(c == 3 ? st.headerRequired : (c == 0 ? st.headerKey : st.header));
                sheet.setColumnWidth(c, WIDTHS[c] * 256);
            }
            // 사용자 정의 컬럼 (내보내기 전용 — 가져오기에서는 읽지 않음). 같은 이름이면 업무/하위 업무 컬럼을 한 열로
            Map<String, List<Long>> extraCols = new LinkedHashMap<>();
            List<BoardColumn> relCols = new ArrayList<>();
            for (BoardColumn bc : partsDao.selectColumnsByBoard(boardId)) {
                extraCols.computeIfAbsent(bc.getName(), k -> new ArrayList<>()).add(bc.getId());
                if (RelationService.TYPE.equals(bc.getType())) relCols.add(bc);
            }
            List<String> extraNames = new ArrayList<>(extraCols.keySet());
            int extraStart = HEADERS.length;
            boolean hasSub = !template && itemDao.selectByBoard(boardId).stream().anyMatch(x -> x.getParent() != null);
            if (hasSub) extraNames.add(0, "상위 번호");
            if (!template) { extraNames.add(0, "업무 키"); extraNames.add(1, "유형"); extraNames.add(2, "버전"); }
            int fixed = (template ? 0 : 3) + (hasSub ? 1 : 0);
            for (int c = 0; c < extraNames.size(); c++) {
                Cell cell = head.createCell(extraStart + c);
                cell.setCellValue(extraNames.get(c));
                cell.setCellStyle(st.headerKey);
                sheet.setColumnWidth(extraStart + c, 18 * 256);
            }
            Map<Long, Map<Long, String>> vals = new HashMap<>();
            if (!template && !extraCols.isEmpty())
                for (ItemValue v : itemDao.selectValuesByColumns(extraCols.values().stream().flatMap(List::stream).toList()))
                    vals.computeIfAbsent(v.getItemId(), k -> new HashMap<>()).put(v.getColumnId(), v.getValue());
            // 전화번호 컬럼은 엑셀에도 마스킹
            Set<Long> phoneCols = partsDao.selectColumnsByBoard(board.getId()).stream().filter(c -> "PHONE".equals(c.getType())).map(BoardColumn::getId).collect(java.util.stream.Collectors.toSet());
            if (!phoneCols.isEmpty())
                for (Map<Long, String> m : vals.values()) m.replaceAll((cid, v) -> phoneCols.contains(cid) ? PhoneFormat.mask(v) : v);
            // 연결 컬럼은 ID 대신 이름으로
            if (!relCols.isEmpty() && !vals.isEmpty()) {
                Map<Long, List<String>> colValues = new HashMap<>();
                for (Map<Long, String> m : vals.values())
                    for (BoardColumn rc : relCols) if (m.get(rc.getId()) != null) colValues.computeIfAbsent(rc.getId(), k -> new ArrayList<>()).add(m.get(rc.getId()));
                Map<Long, Map<Long, String>> labels = relations.getLabels(relCols, colValues);
                for (Map<Long, String> m : vals.values())
                    for (BoardColumn rc : relCols) {
                        String raw = m.get(rc.getId());
                        if (raw == null) continue;
                        Map<Long, String> lab = labels.getOrDefault(rc.getId(), Map.of());
                        List<String> names = new ArrayList<>();
                        for (String t : raw.split(",")) {
                            t = t.trim();
                            if (!t.isEmpty()) { try { names.add(lab.getOrDefault(Long.parseLong(t), "#" + t)); } catch (NumberFormatException e) { names.add(t); } }
                        }
                        m.put(rc.getId(), String.join(", ", names));
                    }
            }
            sheet.createFreezePane(0, 1);

            int r = 1;
            if (!template) {
                Map<Long, Integer> groupOrder = new HashMap<>();
                for (int i = 0; i < gs.size(); i++) groupOrder.put(gs.get(i).getId(), i);
                List<Item> list = new ArrayList<>(itemDao.selectByBoard(boardId));
                if (onlyIds != null) list.removeIf(i -> !onlyIds.contains(i.getId()) && (i.getParent() == null || !onlyIds.contains(i.getParent().getId())));
                list.sort(Comparator.comparing((Item i) -> groupOrder.getOrDefault(i.getGroup().getId(), 999))
                        .thenComparingInt(Item::getSortOrder).thenComparing(Item::getId));
                // 하위 업무는 상위 업무 바로 아래
                Map<Long, List<Item>> kids = new HashMap<>();
                for (Item i : list) if (i.getParent() != null) kids.computeIfAbsent(i.getParent().getId(), k -> new ArrayList<>()).add(i);
                List<Item> ordered = new ArrayList<>();
                Set<Long> present = new HashSet<>();
                for (Item i : list) present.add(i.getId());
                for (Item i : list) if (i.getParent() == null || !present.contains(i.getParent().getId())) { ordered.add(i); if (i.getParent() == null) ordered.addAll(kids.getOrDefault(i.getId(), List.of())); }
                list = ordered;
                for (Item i : list) {
                    Row row = sheet.createRow(r++);
                    text(row, 0, null, st.key).setCellValue(i.getId());
                    text(row, 1, i.getGroup().getName(), st.body);
                    text(row, 2, i.getDuty() == null ? null : dutyPaths.get(i.getDuty().getId()), st.body);
                    text(row, 3, i.getName(), st.body);
                    text(row, 4, i.getStatus() == null ? null : i.getStatus().getLabel(), st.body);
                    text(row, 5, i.getAssignee() == null ? null : userLabel(i.getAssignee()), st.body);
                    text(row, 6, priorityLabel(i.getPriority()), st.body);
                    date(row, 7, i.getStartDate(), st.date);
                    date(row, 8, i.getDueDate(), st.date);
                    text(row, 9, i.getDescription(), st.wrap);
                    Map<Long, String> iv = vals.getOrDefault(i.getId(), Map.of());
                    for (int c = 0; c < extraNames.size(); c++) {
                        String n = extraNames.get(c);
                        String v;
                        if (!template && c == 0) v = com.inticube.teamboard.web.dto.Dtos.keyOf(i);
                        else if (!template && c == 1) v = i.getType() == null ? null : i.getType().getName();
                        else if (!template && c == 2) v = i.getVersion() == null ? null : i.getVersion().getName();
                        else if (hasSub && c == fixed - 1) v = i.getParent() == null ? null : String.valueOf(i.getParent().getId());
                        else v = extraCols.get(n).stream().map(iv::get).filter(Objects::nonNull).findFirst().orElse(null);
                        if (v != null) text(row, extraStart + c, v, st.body);
                    }
                }
            }
            int lastRow = Math.max(r + TEMPLATE_ROWS, TEMPLATE_ROWS);
            // 빈 행에도 서식 적용 (날짜 칸은 날짜 서식)
            for (int i = r; i <= Math.min(lastRow, r + 200); i++) {
                Row row = sheet.createRow(i);
                for (int c = 0; c < HEADERS.length; c++) row.createCell(c).setCellStyle(c == 7 || c == 8 ? st.date : (c == 0 ? st.key : st.body));
            }
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(r - 1, 1), 0, HEADERS.length - 1 + extraNames.size()));

            // 드롭다운 목록 시트 (숨김)
            Sheet lists = wb.createSheet(SHEET_LISTS);
            List<List<String>> cols = List.of(
                    gs.stream().map(BoardGroup::getName).toList(),
                    ss.stream().map(BoardStatus::getLabel).toList(),
                    us.stream().map(ExcelService::userLabel).toList(),
                    new ArrayList<>(PRIORITY_BY_LABEL.keySet()),
                    new ArrayList<>(dutyPaths.values()));
            String[] listHeads = {"그룹", "상태", "담당자", "우선순위", "담당업무"};
            for (int c = 0; c < cols.size(); c++) {
                getRow(lists, 0).createCell(c).setCellValue(listHeads[c]);
                for (int i = 0; i < cols.get(c).size(); i++) getRow(lists, i + 1).createCell(c).setCellValue(cols.get(c).get(i));
            }
            wb.setSheetHidden(wb.getSheetIndex(lists), true);

            DataValidationHelper dv = sheet.getDataValidationHelper();
            addList(sheet, dv, 1, "목록!$A$2:$A$" + Math.max(2, cols.get(0).size() + 1), lastRow, false,
                    "그룹", "목록에서 고르거나 새 그룹 이름을 입력하세요. 새 이름이면 그룹이 만들어집니다.");
            if (!dutyPaths.isEmpty())
                addList(sheet, dv, 2, "목록!$E$2:$E$" + (dutyPaths.size() + 1), lastRow, true, "담당업무",
                        "워크스페이스 담당업무 중에서 고르세요. 담당자가 비어 있으면 정 담당자가 자동 지정됩니다.");
            addList(sheet, dv, 4, "목록!$B$2:$B$" + Math.max(2, cols.get(1).size() + 1), lastRow, true, "상태", "이 보드의 상태 중에서 고르세요.");
            addList(sheet, dv, 5, "목록!$C$2:$C$" + Math.max(2, cols.get(2).size() + 1), lastRow, true, "담당자", "목록에서 고르세요. 아이디만 입력해도 됩니다.");
            addList(sheet, dv, 6, "목록!$D$2:$D$5", lastRow, true, "우선순위", "긴급 / 높음 / 보통 / 낮음");

            // 작성 안내
            Sheet guide = wb.createSheet(SHEET_GUIDE);
            guide.setColumnWidth(0, 16 * 256);
            guide.setColumnWidth(1, 90 * 256);
            String[][] lines = {
                    {"TeamBoard 업무 양식", ""},
                    {"보드", board.getName() + " (보드 번호 " + board.getId() + ")"},
                    {"내보낸 시각", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))},
                    {"", ""},
                    {"작성 방법", "'업무' 시트에 한 줄에 업무 하나씩 작성한 뒤 TeamBoard 보드 화면 > 엑셀 > 가져오기로 올리세요."},
                    {"번호", "비워 두면 새 업무로 추가됩니다. 내보낸 파일의 번호를 그대로 두면 해당 업무가 수정됩니다. 번호는 바꾸지 마세요."},
                    {"그룹", "목록에서 고르거나 새 이름을 입력하면 그룹이 새로 만들어집니다. 비우면 새 업무는 첫 번째 그룹, 기존 업무는 현재 그룹을 유지합니다."},
                    {"담당업무", "워크스페이스 담당업무 경로(예: 개발 > 백엔드 > 인증). 목록에서 고르세요. 이름이 하나뿐이면 마지막 이름만 써도 됩니다. 담당자가 비어 있으면 정 담당자가 자동 지정됩니다."},
                    {"업무명 (필수)", "최대 300자"},
                    {"상태", "이 보드의 상태 이름. 새 업무에서 비우면 첫 번째 상태(" + (ss.isEmpty() ? "-" : ss.get(0).getLabel()) + ")가 됩니다."},
                    {"담당자", "목록의 '이름 (아이디)' 또는 아이디만 입력. 동명이인이 없으면 이름만 입력해도 됩니다."},
                    {"우선순위", "긴급 / 높음 / 보통 / 낮음"},
                    {"시작일·마감일", "2026-11-05 형식 또는 엑셀 날짜. 시작일이 마감일보다 늦으면 오류입니다."},
                    {"설명", "업무 설명 (마크다운 가능)"},
                    {"", ""},
                    {"수정 시 주의", "기존 업무 행에서 칸을 비우면 그 값이 지워집니다(그룹 제외). 엑셀에 없는 업무는 삭제되지 않습니다."},
                    {"오류 처리", "한 줄이라도 오류가 있으면 아무것도 저장되지 않습니다. 가져오기 화면에서 오류 행을 확인하고 고친 뒤 다시 올리세요."},
                    {"", ""},
                    {"예시 (번호 없이)", "그룹: 1차 스프린트 | 담당업무: 개발 > 백엔드 | 업무명: 로그인 화면 개발 | 상태: 대기 | 담당자: 홍길동 (hong) | 우선순위: 높음 | 시작일: 2026-11-03 | 마감일: 2026-11-07"},
            };
            for (int i = 0; i < lines.length; i++) {
                Row row = guide.createRow(i);
                Cell a = row.createCell(0);
                a.setCellValue(lines[i][0]);
                a.setCellStyle(i == 0 ? st.title : st.guideKey);
                Cell b = row.createCell(1);
                b.setCellValue(lines[i][1]);
                b.setCellStyle(st.wrap);
            }

            // 메타 (숨김) — 다른 보드 파일 업로드 감지용
            Sheet meta = wb.createSheet(SHEET_META);
            Row m = meta.createRow(0);
            m.createCell(0).setCellValue("TEAMBOARD_TASKS");
            m.createCell(1).setCellValue(board.getId());
            wb.setSheetHidden(wb.getSheetIndex(meta), true);

            wb.setActiveSheet(0);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("엑셀 생성 실패", e);
        }
    }

    private static Row getRow(Sheet s, int r) {
        Row row = s.getRow(r);
        return row != null ? row : s.createRow(r);
    }

    private static Cell text(Row row, int c, String v, CellStyle style) {
        Cell cell = row.createCell(c);
        if (v != null) cell.setCellValue(v); // setCellValue(String) 은 수식으로 해석되지 않음 (수식 주입 방지)
        cell.setCellStyle(style);
        return cell;
    }

    private static void date(Row row, int c, LocalDate d, CellStyle style) {
        Cell cell = row.createCell(c);
        if (d != null) cell.setCellValue(d);
        cell.setCellStyle(style);
    }

    private static void addList(Sheet sheet, DataValidationHelper dv, int col, String ref, int lastRow,
                                boolean strict, String title, String prompt) {
        DataValidationConstraint cons = dv.createFormulaListConstraint(ref);
        DataValidation v = dv.createValidation(cons, new CellRangeAddressList(1, lastRow, col, col));
        v.setShowErrorBox(strict);
        if (strict) {
            v.setErrorStyle(DataValidation.ErrorStyle.STOP);
            v.createErrorBox(title, "목록에 있는 값만 입력할 수 있습니다.");
        }
        v.setShowPromptBox(true);
        v.createPromptBox(title, prompt);
        v.setSuppressDropDownArrow(true);
        sheet.addValidationData(v);
    }

    private static class Styles {
        final CellStyle header, headerRequired, headerKey, body, key, date, wrap, title, guideKey;

        Styles(Workbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            Font boldRed = wb.createFont();
            boldRed.setBold(true);
            boldRed.setColor(IndexedColors.DARK_RED.getIndex());
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            Font gray = wb.createFont();
            gray.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

            header = headerStyle(wb, bold, IndexedColors.GREY_25_PERCENT);
            headerRequired = headerStyle(wb, boldRed, IndexedColors.GREY_25_PERCENT);
            headerKey = headerStyle(wb, bold, IndexedColors.PALE_BLUE);
            body = wb.createCellStyle();
            body.setVerticalAlignment(VerticalAlignment.TOP);
            key = wb.createCellStyle();
            key.setVerticalAlignment(VerticalAlignment.TOP);
            key.setAlignment(HorizontalAlignment.CENTER);
            key.setFont(gray);
            date = wb.createCellStyle();
            date.setVerticalAlignment(VerticalAlignment.TOP);
            date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            wrap.setVerticalAlignment(VerticalAlignment.TOP);
            title = wb.createCellStyle();
            title.setFont(big);
            guideKey = wb.createCellStyle();
            guideKey.setFont(bold);
            guideKey.setVerticalAlignment(VerticalAlignment.TOP);
        }

        private static CellStyle headerStyle(Workbook wb, Font f, IndexedColors bg) {
            CellStyle s = wb.createCellStyle();
            s.setFont(f);
            s.setFillForegroundColor(bg.getIndex());
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            s.setAlignment(HorizontalAlignment.CENTER);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            s.setBorderBottom(BorderStyle.THIN);
            return s;
        }
    }

    // =========================================================
    // 가져오기
    // =========================================================
    public record RowError(int row, String column, String message) {}

    public record ImportResult(boolean applied, int totalRows, int created, int updated, int unchanged,
                               List<String> newGroups, List<RowError> errors, List<String> warnings,
                               List<PreviewRow> preview) {}

    public record PreviewRow(int row, String action, Long id, String group, String duty, String name, String status,
                             String assignee, String priority, LocalDate startDate, LocalDate dueDate) {}

    /** 검증된 한 행 */
    private record Parsed(int row, Long id, String groupName, Long dutyId, String name, Long statusId, boolean statusBlank,
                          Long assigneeId, Priority priority, LocalDate start, LocalDate due, String description,
                          Set<String> present) {}

    @Transactional
    public ImportResult batchImport(Long boardId, MultipartFile file, boolean dryRun, UserPrincipal me) {
        Board board = boardService.getBoard(boardId);
        if (file == null || file.isEmpty()) throw ApiException.bad("파일을 선택하세요.");
        String fname = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (!fname.endsWith(".xlsx")) throw ApiException.bad("엑셀(.xlsx) 파일만 가져올 수 있습니다. (.xls 는 다른 이름으로 저장 > .xlsx 로 변환하세요)");
        if (file.getSize() > 10L * 1024 * 1024) throw ApiException.bad("10MB 이하 파일만 가져올 수 있습니다.");

        List<BoardGroup> gs = partsDao.selectGroupsByBoard(boardId);
        List<BoardStatus> ss = partsDao.selectStatusesByBoard(boardId);
        List<AppUser> allUsers = userDao.selectAllOrderByName();
        List<AppUser> activeUsers = allUsers.stream().filter(AppUser::isActive).toList();
        Map<Long, Item> boardItems = itemDao.selectByBoard(boardId).stream()
                .collect(Collectors.toMap(Item::getId, i -> i));
        Map<Long, String> dutyPaths = DutyService.getPaths(dutyDao.selectByBoard(boardId));
        Map<String, Long> dutyByPath = new HashMap<>();
        Map<String, List<Long>> dutyByLeaf = new HashMap<>();
        dutyPaths.forEach((id, path) -> {
            dutyByPath.put(normPath(path), id);
            String leaf = path.contains(">") ? path.substring(path.lastIndexOf('>') + 1).trim() : path;
            dutyByLeaf.computeIfAbsent(leaf.toLowerCase(), k -> new ArrayList<>()).add(id);
        });

        List<RowError> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Parsed> rows = new ArrayList<>();

        try (InputStream in = file.getInputStream(); Workbook wb = new XSSFWorkbook(in)) {
            Sheet meta = wb.getSheet(SHEET_META);
            if (meta != null && meta.getRow(0) != null && meta.getRow(0).getCell(1) != null) {
                try {
                    long metaBoard = (long) meta.getRow(0).getCell(1).getNumericCellValue();
                    if (metaBoard != boardId)
                        warnings.add("다른 보드(번호 " + metaBoard + ")에서 내보낸 파일입니다. 번호가 있는 행은 이 보드의 업무가 아니면 오류가 됩니다. 새 업무로 옮기려면 번호 칸을 비우세요.");
                } catch (IllegalStateException ignored) { /* 형식 다름 */ }
            }
            Sheet sheet = wb.getSheet(SHEET_TASKS);
            if (sheet == null) sheet = wb.getSheetAt(0);
            Row head = sheet.getRow(sheet.getFirstRowNum());
            if (head == null) throw ApiException.bad("첫 줄에 제목 행(번호, 그룹, 업무명 …)이 필요합니다.");
            Map<String, Integer> col = new HashMap<>();
            DataFormatter fmt = new DataFormatter(Locale.KOREA);
            for (Cell c : head) {
                String h = fmt.formatCellValue(c).replace("*", "").replaceAll("\\s+", "");
                for (String known : HEADERS) if (known.equals(h)) col.putIfAbsent(known, c.getColumnIndex());
            }
            if (!col.containsKey("업무명"))
                throw ApiException.bad("'업무명' 열을 찾을 수 없습니다. TeamBoard 에서 내려받은 양식을 사용하세요.");

            Set<Long> seenIds = new HashSet<>();
            int dataRows = 0;
            for (int r = head.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isBlankRow(row, col.values(), fmt)) continue;
                if (++dataRows > MAX_ROWS) throw ApiException.bad("한 번에 최대 " + MAX_ROWS + "행까지 가져올 수 있습니다.");
                int excelRow = r + 1;
                int before = errors.size();
                Set<String> present = col.keySet();

                // 번호
                Long id = null;
                if (col.containsKey("번호")) {
                    String v = str(row, col.get("번호"), fmt);
                    if (!v.isEmpty()) {
                        try {
                            id = (long) Double.parseDouble(v.replace(",", ""));
                        } catch (NumberFormatException e) {
                            errors.add(new RowError(excelRow, "번호", "숫자가 아닙니다: " + v));
                        }
                        if (id != null && !boardItems.containsKey(id))
                            errors.add(new RowError(excelRow, "번호", "이 보드에 #" + id + " 업무가 없습니다. 새 업무라면 번호를 비우세요."));
                        if (id != null && !seenIds.add(id))
                            errors.add(new RowError(excelRow, "번호", "#" + id + " 가 파일에 두 번 이상 있습니다."));
                    }
                }
                // 업무명
                String name = str(row, col.get("업무명"), fmt);
                if (name.isEmpty()) errors.add(new RowError(excelRow, "업무명", "업무명은 필수입니다."));
                else if (name.length() > 300) errors.add(new RowError(excelRow, "업무명", "300자를 넘을 수 없습니다."));
                // 그룹
                String groupName = col.containsKey("그룹") ? str(row, col.get("그룹"), fmt) : "";
                if (groupName.length() > 100) errors.add(new RowError(excelRow, "그룹", "그룹 이름은 100자 이하여야 합니다."));
                // 담당업무
                Long dutyId = null;
                String dutyText = col.containsKey("담당업무") ? str(row, col.get("담당업무"), fmt) : "";
                if (!dutyText.isEmpty()) {
                    int beforeDuty = errors.size();
                    dutyId = dutyByPath.get(normPath(dutyText));
                    if (dutyId == null && !dutyText.contains(">")) {
                        List<Long> cands = dutyByLeaf.getOrDefault(dutyText.trim().toLowerCase(), List.of());
                        if (cands.size() == 1) dutyId = cands.get(0);
                        else if (cands.size() > 1)
                            errors.add(new RowError(excelRow, "담당업무", "'" + dutyText + "' 이름이 여러 곳에 있습니다. '상위 > 하위' 경로로 입력하세요."));
                    }
                    if (dutyId == null && errors.size() == beforeDuty)
                        errors.add(new RowError(excelRow, "담당업무", "'" + dutyText + "' 담당업무가 없습니다. 워크스페이스 > 담당업무 탭에서 먼저 추가하거나 목록에서 고르세요."));
                }
                // 상태
                Long statusId = null;
                String statusText = col.containsKey("상태") ? str(row, col.get("상태"), fmt) : "";
                if (!statusText.isEmpty()) {
                    Optional<BoardStatus> s = ss.stream().filter(x -> x.getLabel().equalsIgnoreCase(statusText)).findFirst();
                    if (s.isPresent()) statusId = s.get().getId();
                    else errors.add(new RowError(excelRow, "상태", "'" + statusText + "' 상태가 없습니다. 가능: " +
                            ss.stream().map(BoardStatus::getLabel).collect(Collectors.joining(", "))));
                }
                // 담당자
                Long assigneeId = null;
                String who = col.containsKey("담당자") ? str(row, col.get("담당자"), fmt) : "";
                if (!who.isEmpty()) {
                    String err = null;
                    Matcher m = USER_IN_PAREN.matcher(who);
                    String username = m.find() ? m.group(1).trim() : who;
                    Optional<AppUser> byId = activeUsers.stream().filter(u -> u.getUsername().equalsIgnoreCase(username)).findFirst();
                    if (byId.isPresent()) assigneeId = byId.get().getId();
                    else {
                        List<AppUser> byName = activeUsers.stream().filter(u -> u.getName().equals(who)).toList();
                        if (byName.size() == 1) assigneeId = byName.get(0).getId();
                        else if (byName.size() > 1) err = "'" + who + "' 이름이 여러 명입니다. '이름 (아이디)' 형식으로 입력하세요.";
                        else {
                            // 비활성 계정: 기존 업무의 현재 담당자 그대로면 허용 (내보낸 파일을 고치지 않고 다시 올리는 경우)
                            Item cur = id == null ? null : boardItems.get(id);
                            Optional<AppUser> inactive = allUsers.stream().filter(u -> u.getUsername().equalsIgnoreCase(username)).findFirst();
                            if (inactive.isPresent() && cur != null && cur.getAssignee() != null && cur.getAssignee().getId().equals(inactive.get().getId()))
                                assigneeId = inactive.get().getId();
                            else err = inactive.isPresent() ? "'" + who + "' 은(는) 비활성 계정입니다. 사용자 관리에서 활성화한 뒤 지정하세요."
                                    : "'" + who + "' 사용자를 찾을 수 없습니다.";
                        }
                    }
                    if (err != null) errors.add(new RowError(excelRow, "담당자", err));
                }
                // 우선순위
                Priority priority = null;
                String pr = col.containsKey("우선순위") ? str(row, col.get("우선순위"), fmt) : "";
                if (!pr.isEmpty()) {
                    priority = PRIORITY_BY_LABEL.get(pr);
                    if (priority == null) {
                        try { priority = Priority.valueOf(pr.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) {
                            errors.add(new RowError(excelRow, "우선순위", "'" + pr + "' 은(는) 사용할 수 없습니다. 긴급/높음/보통/낮음 중 하나"));
                        }
                    }
                }
                // 날짜
                LocalDate start = col.containsKey("시작일") ? dateValue(row, col.get("시작일"), fmt, excelRow, "시작일", errors) : null;
                LocalDate due = col.containsKey("마감일") ? dateValue(row, col.get("마감일"), fmt, excelRow, "마감일", errors) : null;
                if (start != null && due != null && start.isAfter(due))
                    errors.add(new RowError(excelRow, "시작일", "시작일(" + start + ")이 마감일(" + due + ")보다 늦습니다."));
                // 설명
                String desc = col.containsKey("설명") ? str(row, col.get("설명"), fmt) : null;
                if (desc != null && desc.length() > 50000) errors.add(new RowError(excelRow, "설명", "설명은 50,000자 이하여야 합니다."));

                if (errors.size() == before)
                    rows.add(new Parsed(excelRow, id, groupName, dutyId, name, statusId, statusText.isEmpty(), assigneeId, priority, start, due, desc, present));
            }
            if (dataRows == 0) throw ApiException.bad("가져올 업무가 없습니다. '업무' 시트 2행부터 작성하세요.");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("엑셀 파일을 읽을 수 없습니다. 파일이 손상되었거나 암호가 걸려 있는지 확인하세요.");
        }

        // 새 그룹 계산
        Map<String, BoardGroup> groupByName = new LinkedHashMap<>();
        for (BoardGroup g : gs) groupByName.putIfAbsent(g.getName().trim().toLowerCase(), g);
        List<String> newGroups = new ArrayList<>();
        for (Parsed p : rows) {
            if (!p.groupName().isEmpty() && !groupByName.containsKey(p.groupName().toLowerCase())
                    && newGroups.stream().noneMatch(n -> n.equalsIgnoreCase(p.groupName())))
                newGroups.add(p.groupName());
        }

        // 생성/수정/변경없음 분류 + 미리보기
        Map<Long, String> statusLabel = ss.stream().collect(Collectors.toMap(BoardStatus::getId, BoardStatus::getLabel));
        Map<Long, String> userName = allUsers.stream().collect(Collectors.toMap(AppUser::getId, AppUser::getName));
        int created = 0, updated = 0, unchanged = 0;
        List<PreviewRow> preview = new ArrayList<>();
        List<Map.Entry<Parsed, Map<String, Object>>> updatePlans = new ArrayList<>();
        for (Parsed p : rows) {
            String action;
            if (p.id() == null) {
                created++;
                action = "생성";
            } else {
                Map<String, Object> changes = diff(boardItems.get(p.id()), p, groupByName);
                if (changes.isEmpty()) { unchanged++; action = "변경 없음"; }
                else { updated++; action = "수정"; updatePlans.add(Map.entry(p, changes)); }
            }
            Long sid = p.id() == null && p.statusBlank() && !ss.isEmpty() ? ss.get(0).getId() : p.statusId();
            preview.add(new PreviewRow(p.row(), action, p.id(),
                    p.groupName().isEmpty() ? (p.id() != null ? boardItems.get(p.id()).getGroup().getName() : (gs.isEmpty() ? "" : gs.get(0).getName())) : p.groupName(),
                    p.dutyId() == null ? null : dutyPaths.get(p.dutyId()),
                    p.name(), sid == null ? null : statusLabel.get(sid), assigneePreview(p, userName),
                    priorityLabel(p.priority()), p.start(), p.due()));
        }

        if (!errors.isEmpty() || dryRun) {
            return new ImportResult(false, rows.size() + countErrorRows(errors), created, updated, unchanged, newGroups, errors, warnings,
                    preview.size() > 200 ? preview.subList(0, 200) : preview);
        }

        // ---------- 적용 ----------
        AppUser actor = userDao.selectById(me.id());
        int nextOrder = partsDao.selectGroupMaxSort(boardId);
        for (String gname : newGroups) {
            BoardGroup g = new BoardGroup();
            g.setBoard(board);
            g.setName(gname);
            g.setColor(nextColor(groupByName.size()));
            g.setSortOrder(++nextOrder);
            partsDao.insertGroup(g);
            groupByName.put(gname.toLowerCase(), g);
        }
        Map<Long, Integer> orderByGroup = new HashMap<>();
        for (Parsed p : rows) {
            if (p.id() != null) continue;
            BoardGroup g = p.groupName().isEmpty() ? gs.get(0) : groupByName.get(p.groupName().toLowerCase());
            int order = orderByGroup.computeIfAbsent(g.getId(), itemDao::selectMaxSort) + 1;
            orderByGroup.put(g.getId(), order);
            Item i = new Item();
            i.setBoard(board);
            i.setGroup(g);
            i.setName(p.name().trim());
            i.setStatus(p.statusId() != null ? partsDao.selectStatusById(p.statusId()) : (p.statusBlank() && !ss.isEmpty() ? ss.get(0) : null));
            BoardDuty duty = p.dutyId() == null ? null : dutyService.getDuty(p.dutyId());
            i.setDuty(duty);
            AppUser assignee = p.assigneeId() == null ? null : userDao.selectById(p.assigneeId());
            boolean autoAssigned = false;
            if (assignee == null && duty != null) {
                assignee = dutyService.getInheritedPrimary(duty);
                autoAssigned = assignee != null;
            }
            i.setAssignee(assignee);
            i.setPriority(p.priority());
            i.setStartDate(p.start());
            i.setDueDate(p.due());
            i.setDescription(p.description() == null ? "" : p.description());
            i.setCreatedBy(actor);
            i.setSortOrder(order);
            i.setKeyNum(boardService.getNextKey(board));
            i.setType(boardService.getDefaultType(boardId));
            itemDao.insert(i);
            ItemUpdate log = new ItemUpdate();
            log.setItem(i);
            log.setAuthor(actor);
            log.setContent("엑셀 가져오기로 생성" + (autoAssigned ? " (담당업무 정 담당자 자동 지정)" : ""));
            log.setSystem(true);
            extraDao.insertUpdate(log);
            if (assignee != null) {
                AppUser target = assignee;
                notifier.insertNotify(target, actor, NotificationType.ASSIGNED,
                        actor.getName() + "님이 [" + board.getName() + "] '" + i.getName() + "'의 담당자로 지정했습니다. (엑셀 가져오기)", i);
            }
        }
        for (Map.Entry<Parsed, Map<String, Object>> e : updatePlans) {
            Map<String, Object> changes = e.getValue();
            if (changes.containsKey("groupName")) {
                changes.put("groupId", groupByName.get(((String) changes.remove("groupName")).toLowerCase()).getId());
            }
            itemService.updateItem(e.getKey().id(), changes, me);
        }
        return new ImportResult(true, rows.size(), created, updated, unchanged, newGroups, List.of(), warnings,
                preview.size() > 200 ? preview.subList(0, 200) : preview);
    }

    /** 기존 업무와 비교해 바뀐 항목만 patch 형식으로 반환. 빈 칸은 값 삭제(그룹 제외) */
    private Map<String, Object> diff(Item cur, Parsed p, Map<String, BoardGroup> groupByName) {
        Map<String, Object> m = new LinkedHashMap<>();
        Set<String> has = p.present();
        if (!cur.getName().equals(p.name().trim())) m.put("name", p.name().trim());
        if (has.contains("그룹") && !p.groupName().isEmpty()) {
            BoardGroup g = groupByName.get(p.groupName().toLowerCase());
            if (g == null || !g.getId().equals(cur.getGroup().getId())) m.put("groupName", p.groupName());
        }
        if (has.contains("담당업무") && !Objects.equals(cur.getDuty() == null ? null : cur.getDuty().getId(), p.dutyId()))
            m.put("dutyId", p.dutyId());
        if (has.contains("상태") && !Objects.equals(cur.getStatus() == null ? null : cur.getStatus().getId(), p.statusId()))
            m.put("statusId", p.statusId());
        if (has.contains("담당자") && !Objects.equals(cur.getAssignee() == null ? null : cur.getAssignee().getId(), p.assigneeId()))
            m.put("assigneeId", p.assigneeId());
        if (has.contains("우선순위") && cur.getPriority() != p.priority())
            m.put("priority", p.priority() == null ? null : p.priority().name());
        if (has.contains("시작일") && !Objects.equals(cur.getStartDate(), p.start()))
            m.put("startDate", p.start() == null ? null : p.start().toString());
        if (has.contains("마감일") && !Objects.equals(cur.getDueDate(), p.due()))
            m.put("dueDate", p.due() == null ? null : p.due().toString());
        if (has.contains("설명")) {
            String curDesc = cur.getDescription() == null ? "" : cur.getDescription();
            String newDesc = p.description() == null ? "" : p.description();
            if (!curDesc.equals(newDesc)) m.put("description", newDesc);
        }
        return m;
    }

    private static String normPath(String path) {
        return Arrays.stream(path.split(">")).map(String::trim).filter(x -> !x.isEmpty())
                .collect(Collectors.joining(">")).toLowerCase();
    }

    private String assigneePreview(Parsed p, Map<Long, String> userName) {
        if (p.assigneeId() != null) return userName.get(p.assigneeId());
        if (p.id() == null && p.dutyId() != null) {
            AppUser u = dutyService.getInheritedPrimary(dutyService.getDuty(p.dutyId()));
            if (u != null) return u.getName() + " (자동)";
        }
        return null;
    }

    private static int countErrorRows(List<RowError> errors) {
        return (int) errors.stream().map(RowError::row).distinct().count();
    }

    private static boolean isBlankRow(Row row, Collection<Integer> cols, DataFormatter fmt) {
        for (int c : cols) if (!str(row, c, fmt).isEmpty()) return false;
        return true;
    }

    private static String str(Row row, Integer c, DataFormatter fmt) {
        if (c == null) return "";
        Cell cell = row.getCell(c);
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA) {
            // 수식은 계산된 값(캐시)만 사용
            return switch (cell.getCachedFormulaResultType()) {
                case STRING -> cell.getStringCellValue().trim();
                case NUMERIC -> fmt.formatRawCellContents(cell.getNumericCellValue(), -1, "General").trim();
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                default -> "";
            };
        }
        return fmt.formatCellValue(cell).trim();
    }

    private static LocalDate dateValue(Row row, int c, DataFormatter fmt, int excelRow, String colName, List<RowError> errors) {
        Cell cell = row.getCell(c);
        if (cell == null) return null;
        try {
            CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
            if (type == CellType.NUMERIC) {
                double v = cell.getNumericCellValue();
                if (DateUtil.isCellDateFormatted(cell) || (v > 20000 && v < 80000)) {
                    return DateUtil.getJavaDate(v).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                }
                String digits = String.valueOf((long) v);
                if (digits.length() == 8) return LocalDate.parse(digits, DateTimeFormatter.ofPattern("yyyyMMdd"));
            }
        } catch (Exception ignored) { /* 아래 문자열 파싱으로 */ }
        String s = str(row, c, fmt);
        if (s.isEmpty()) return null;
        String t = s.replaceAll("\\s*\\(.*\\)\\s*$", "").trim(); // '2026-11-05 (수)' 형태 허용
        for (DateTimeFormatter f : DATE_FORMATS) {
            try { return LocalDate.parse(t, f); } catch (DateTimeParseException ignored) { /* 다음 형식 */ }
        }
        errors.add(new RowError(excelRow, colName, "날짜 형식이 아닙니다: " + s + " (예: 2026-11-05)"));
        return null;
    }

    static String userLabel(AppUser u) {
        return u.getName() + " (" + u.getUsername() + ")";
    }

    static String priorityLabel(Priority p) {
        if (p == null) return null;
        for (Map.Entry<String, Priority> e : PRIORITY_BY_LABEL.entrySet()) if (e.getValue() == p) return e.getKey();
        return p.name();
    }

    private static String nextColor(int i) {
        String[] c = {"#0061F2", "#6900C7", "#00AC69", "#F4A100", "#E81500", "#00CFD5", "#F76400"};
        return c[i % c.length];
    }
}
