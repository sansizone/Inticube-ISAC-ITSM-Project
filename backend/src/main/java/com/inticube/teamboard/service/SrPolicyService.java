package com.inticube.teamboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inticube.teamboard.dao.*;
import com.inticube.teamboard.domain.*;
import com.inticube.teamboard.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * SR 통제 규칙 (워크스페이스별, tb_board.column_config 의 "srPolicy").
 * - 처리완료일: 완료로 바꾸는 순간 서버 시각으로 자동 입력, 사용자 수정 불가. 재개하면 비움
 * - 완료 SR 잠금: 완료된 업무는 '재개(사유 필수)' 외에는 수정 불가 (시간 기록·첨부 삭제 포함)
 * - 접수일시: 처리 시작·완료 전 필수, 미래 불가. 등록 지연(등록 시각 − 접수일시, 시간) 자동 계산
 * - 합의완료일: 이미 있는 값을 바꾸려면 변경 사유 필수. 최초 합의일은 숨은 컬럼에 따로 보존
 * - 완료 시 필수 항목 검증, 증빙(첨부) 1건 이상 필수
 * - 장애등급: 장애 유형이면 영향도 × 긴급도로 자동 산출 (직접 입력 불가)
 * - 작업시간: 시간 기록 합계로 자동 계산, 시간 기록 1건·1인 1일 상한
 * 규칙이 꺼져 있으면(srPolicy 없음) 아무 것도 하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SrPolicyService {
    private final BoardDao boardDao;
    private final BoardPartsDao partsDao;
    private final ItemDao itemDao;
    private final ItemExtraDao extraDao;
    private final ObjectMapper json;

    public static final String KEY = "srPolicy";
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** required 항목: "assignee"(담당자) 또는 컬럼 ID 문자열 */
    public record Policy(boolean enabled, boolean lockDone, Long completedAtCol, Long receivedAtCol, Long delayCol,
                         Long agreedCol, Long firstAgreedCol, Long gradeCol, Long impactCol, Long urgencyCol, String gradeType,
                         List<String> required, boolean requireEvidence, Long workHoursCol,
                         BigDecimal maxEntryHours, BigDecimal maxDayHours) {
        /** 사용자가 직접 바꿀 수 없는 자동 컬럼 */
        public Set<Long> autoCols() {
            Set<Long> s = new HashSet<>();
            for (Long c : new Long[]{completedAtCol, delayCol, firstAgreedCol, workHoursCol}) if (c != null) s.add(c);
            if (gradeCol != null && impactCol != null && urgencyCol != null) s.add(gradeCol);
            return s;
        }
    }

    // ───────────────────────── 읽기·저장

    @Transactional(readOnly = true)
    public Policy get(Long boardId) {
        Board b = boardDao.selectById(boardId);
        return b == null ? null : parse(b.getColumnConfig());
    }

    public Policy parse(String config) {
        if (config == null || config.isBlank()) return null;
        try {
            JsonNode n = json.readTree(config).path(KEY);
            if (n.isMissingNode() || !n.path("enabled").asBoolean(false)) return null;
            List<String> req = new ArrayList<>();
            n.path("required").forEach(x -> req.add(x.asText()));
            return new Policy(true, n.path("lockDone").asBoolean(true), id(n, "completedAtCol"), id(n, "receivedAtCol"), id(n, "delayCol"),
                    id(n, "agreedCol"), id(n, "firstAgreedCol"), id(n, "gradeCol"), id(n, "impactCol"), id(n, "urgencyCol"),
                    n.path("gradeType").asText("장애"), req, n.path("requireEvidence").asBoolean(true), id(n, "workHoursCol"),
                    dec(n, "maxEntryHours", "12"), dec(n, "maxDayHours", "16"));
        } catch (Exception e) {
            return null;
        }
    }

    /** 설정 화면에서 받은 값을 그대로 저장 (컬럼 ID 는 이 워크스페이스 것만 허용) */
    public void setPolicy(Long boardId, Map<String, Object> body) {
        Board b = boardDao.selectById(boardId);
        if (b == null) throw ApiException.notFound("워크스페이스");
        Set<Long> cols = new HashSet<>();
        partsDao.selectColumnsByBoard(boardId).forEach(c -> cols.add(c.getId()));
        ObjectNode p = json.createObjectNode();
        p.put("enabled", Boolean.TRUE.equals(body.get("enabled")));
        p.put("lockDone", !Boolean.FALSE.equals(body.get("lockDone")));
        p.put("requireEvidence", !Boolean.FALSE.equals(body.get("requireEvidence")));
        for (String k : List.of("completedAtCol", "receivedAtCol", "delayCol", "agreedCol", "firstAgreedCol", "gradeCol", "impactCol", "urgencyCol", "workHoursCol")) {
            Long v = toLong(body.get(k));
            if (v != null && !cols.contains(v)) throw ApiException.bad("이 워크스페이스의 컬럼이 아닙니다: " + k);
            if (v != null) p.put(k, v);
        }
        p.put("gradeType", body.get("gradeType") == null ? "장애" : body.get("gradeType").toString().trim());
        ArrayNode req = p.putArray("required");
        if (body.get("required") instanceof List<?> l) for (Object o : l) {
            String s = String.valueOf(o);
            if (s.equals("assignee") || (toLong(s) != null && cols.contains(toLong(s)))) req.add(s);
        }
        BigDecimal e = toDec(body.get("maxEntryHours"), "12"), d = toDec(body.get("maxDayHours"), "16");
        if (e.signum() <= 0 || e.compareTo(BigDecimal.valueOf(24)) > 0 || d.signum() <= 0 || d.compareTo(BigDecimal.valueOf(24)) > 0)
            throw ApiException.bad("시간 기록 상한은 0보다 크고 24시간 이하여야 합니다.");
        p.put("maxEntryHours", e); p.put("maxDayHours", d);
        writeConfig(b, p);
    }

    /**
     * 기본값 자동 설정: 이름으로 컬럼을 찾아 연결하고, 없는 컬럼은 만든다.
     * 처리완료일·합의완료일·장애등급·작업시간(H)·처리담당자 등은 찾기만, 접수일시·등록 지연(H)·최초 합의완료일·영향도·긴급도·처리내역은 없으면 생성
     */
    public void setupDefaults(Long boardId) {
        Board b = boardDao.selectById(boardId);
        if (b == null) throw ApiException.notFound("워크스페이스");
        List<BoardColumn> cols = new ArrayList<>(partsDao.selectColumnsByBoard(boardId));
        BoardColumn agreed = find(cols, "합의완료일");
        ObjectNode p = json.createObjectNode();
        p.put("enabled", true);
        p.put("lockDone", true);
        p.put("requireEvidence", true);
        putId(p, "completedAtCol", find(cols, "처리완료일"));
        putId(p, "receivedAtCol", ensure(b, cols, "접수일시", "DATETIME", true));
        putId(p, "delayCol", ensure(b, cols, "등록 지연 (H)", "NUMBER", true));
        putId(p, "agreedCol", agreed);
        if (agreed != null) putId(p, "firstAgreedCol", ensure(b, cols, "최초 합의완료일", agreed.getType(), false));
        putId(p, "gradeCol", find(cols, "장애등급"));
        putId(p, "impactCol", ensure(b, cols, "영향도", "DROPDOWN", true));
        putId(p, "urgencyCol", ensure(b, cols, "긴급도", "DROPDOWN", true));
        p.put("gradeType", "장애");
        putId(p, "workHoursCol", find(cols, "작업시간 (H)"));
        ArrayNode req = p.putArray("required");
        for (String name : List.of("접수유형", "작업유형", "처리담당자", "접수일시")) { BoardColumn c = find(cols, name); if (c != null) req.add(String.valueOf(c.getId())); }
        BoardColumn result = ensure(b, cols, "처리내역", "LONG_TEXT", true);
        if (result != null) req.add(String.valueOf(result.getId()));
        p.put("maxEntryHours", 12);
        p.put("maxDayHours", 16);
        writeConfig(b, p);
    }

    // ───────────────────────── 업무 수정 전·후

    /** 수정 요청 검사: 완료 잠금·재개 사유·자동 컬럼 보호·합의완료일 변경 사유 */
    public void checkUpdate(Item i, Map<String, Object> body, Policy p) {
        if (p == null) return;
        boolean done = i.getStatus() != null && i.getStatus().isDone();
        if (done && p.lockDone()) {
            BoardStatus next = body.containsKey("statusId") ? status(body.get("statusId")) : null;
            boolean reopening = next != null && !next.isDone();
            Set<String> keys = new HashSet<>(body.keySet());
            keys.removeAll(Set.of("statusId", "reopenReason"));
            if (!reopening || !keys.isEmpty())
                throw ApiException.bad("완료된 SR은 수정할 수 없습니다. 상태를 바꿔 '재개'(사유 입력)한 뒤 수정하세요.");
            if (blank(body.get("reopenReason"))) throw ApiException.bad("재개 사유를 입력하세요.");
        }
        if (body.get("values") instanceof Map<?, ?> vm) {
            Set<Long> auto = p.autoCols();
            for (Object k : vm.keySet()) {
                Long cid = toLong(k);
                if (cid != null && auto.contains(cid)) throw ApiException.bad("'" + colName(cid) + "' 은(는) 자동으로 계산되는 항목이라 직접 수정할 수 없습니다.");
            }
            if (p.agreedCol() != null && vm.containsKey(String.valueOf(p.agreedCol())) || p.agreedCol() != null && vm.containsKey(p.agreedCol())) {
                Object nv = vm.containsKey(p.agreedCol()) ? vm.get(p.agreedCol()) : vm.get(String.valueOf(p.agreedCol()));
                String before = value(i.getId(), p.agreedCol());
                String next = nv == null ? "" : nv.toString().trim().replace('T', ' ');
                if (!before.isEmpty() && !before.equals(next) && blank(body.get("changeReason")))
                    throw ApiException.bad("'" + colName(p.agreedCol()) + "' 을(를) 바꾸려면 변경 사유를 입력하세요.");
            }
        }
        if (body.get("values") instanceof Map<?, ?> vm3) {
            for (Long lc : new Long[]{p.impactCol(), p.urgencyCol()}) {
                if (lc == null) continue;
                Object lv = vm3.containsKey(lc) ? vm3.get(lc) : vm3.get(String.valueOf(lc));
                if (lv != null && !lv.toString().isBlank() && level(lv.toString()) == 0)
                    throw ApiException.bad("'" + colName(lc) + "' 은(는) 상·중·하 중에서 고르세요.");
            }
        }
        if (p.receivedAtCol() != null && body.get("values") instanceof Map<?, ?> vm2) {
            Object rv = vm2.containsKey(p.receivedAtCol()) ? vm2.get(p.receivedAtCol()) : vm2.get(String.valueOf(p.receivedAtCol()));
            if (rv != null && !rv.toString().isBlank()) {
                LocalDateTime t = parseDt(rv.toString());
                if (t != null && t.isAfter(LocalDateTime.now().plusMinutes(1))) throw ApiException.bad("'" + colName(p.receivedAtCol()) + "' 은(는) 미래 시각일 수 없습니다.");
            }
        }
    }

    /**
     * 수정 반영 직후(같은 트랜잭션): 완료·재개 처리, 접수일시 필수, 자동 컬럼 계산.
     * 조건을 못 맞추면 예외 → 이번 수정 전체가 취소된다
     */
    public void afterUpdate(Item i, Policy p, BoardStatus before, Map<String, Object> body, AppUser actor, List<String> logs) {
        if (p == null) return;
        BoardStatus now = i.getStatus();
        boolean statusChanged = !Objects.equals(id(before), id(now));
        boolean nowDone = now != null && now.isDone(), wasDone = before != null && before.isDone();
        Map<Long, BoardColumn> cols = new HashMap<>();
        partsDao.selectColumnsByBoard(i.getBoard().getId()).forEach(c -> cols.put(c.getId(), c));

        // 합의완료일: 최초 값 보존 + 변경 사유 기록
        if (p.agreedCol() != null && body.get("values") instanceof Map<?, ?> vm
                && (vm.containsKey(p.agreedCol()) || vm.containsKey(String.valueOf(p.agreedCol())))) {
            String cur = value(i.getId(), p.agreedCol());
            if (p.firstAgreedCol() != null && !cur.isEmpty() && value(i.getId(), p.firstAgreedCol()).isEmpty())
                set(i, cols.get(p.firstAgreedCol()), cur);
            if (!blank(body.get("changeReason"))) logs.add(colName(p.agreedCol()) + " 변경 사유: " + body.get("changeReason").toString().trim());
        }
        // 재개 사유
        if (statusChanged && wasDone && !nowDone) {
            if (!blank(body.get("reopenReason"))) logs.add("재개 사유: " + body.get("reopenReason").toString().trim());
            if (p.completedAtCol() != null && !value(i.getId(), p.completedAtCol()).isEmpty()) {
                set(i, cols.get(p.completedAtCol()), "");
                logs.add(colName(p.completedAtCol()) + ": 재개로 비움");
            }
        }
        // 접수일시: 처리 시작(상태 변경) 전 필수. 재개는 잠금 해제가 먼저라 예외 (재개 후 입력)
        boolean reopened = statusChanged && wasDone && !nowDone;
        if (statusChanged && !reopened && p.receivedAtCol() != null && value(i.getId(), p.receivedAtCol()).isEmpty())
            throw ApiException.bad("'" + colName(p.receivedAtCol()) + "'(요청 발생 일시)을(를) 먼저 입력하세요.");
        // 완료 전환: 필수 항목·증빙 검사 후 처리완료일 자동 입력
        if (statusChanged && nowDone && !wasDone) {
            checkDone(i, p, cols);
            if (p.completedAtCol() != null) {
                BoardColumn c = cols.get(p.completedAtCol());
                String v = "DATE".equals(c.getType()) ? LocalDate.now().toString() : LocalDateTime.now().format(DT);
                set(i, c, v);
                logs.add(c.getName() + ": " + v + " (자동)");
            }
        }
        boolean gradeTouched = body.containsKey("typeId") || (statusChanged && nowDone)
                || (body.get("values") instanceof Map<?, ?> v2 && (has(v2, p.impactCol()) || has(v2, p.urgencyCol())));
        recompute(i, p, cols, logs, gradeTouched);
    }

    private static boolean has(Map<?, ?> m, Long k) { return k != null && (m.containsKey(k) || m.containsKey(String.valueOf(k))); }

    /** 완료 조건: 필수 항목, 장애면 영향도·긴급도, 증빙 1건 이상 */
    private void checkDone(Item i, Policy p, Map<Long, BoardColumn> cols) {
        List<String> missing = new ArrayList<>();
        for (String r : p.required()) {
            if (r.equals("assignee")) { if (i.getAssignee() == null) missing.add("담당자"); continue; }
            Long cid = toLong(r);
            if (cid != null && cols.containsKey(cid) && value(i.getId(), cid).isEmpty()) missing.add(cols.get(cid).getName());
        }
        if (isGradeType(i, p)) {
            if (p.impactCol() != null && value(i.getId(), p.impactCol()).isEmpty()) missing.add(colName(p.impactCol()));
            if (p.urgencyCol() != null && value(i.getId(), p.urgencyCol()).isEmpty()) missing.add(colName(p.urgencyCol()));
        }
        if (!missing.isEmpty()) throw ApiException.bad("완료하려면 다음 항목을 입력하세요: " + String.join(", ", missing));
        if (p.requireEvidence() && extraDao.selectAttachmentsByItem(i.getId()).isEmpty())
            throw ApiException.bad("완료하려면 증빙 파일을 1건 이상 첨부하세요.");
    }

    /** 자동 컬럼 다시 계산: 등록 지연, 장애등급 */
    private void recompute(Item i, Policy p, Map<Long, BoardColumn> cols, List<String> logs, boolean gradeTouched) {
        if (p.receivedAtCol() != null && p.delayCol() != null && cols.containsKey(p.delayCol())) {
            LocalDateTime r = parseDt(value(i.getId(), p.receivedAtCol()));
            String v = "";
            if (r != null && i.getCreatedAt() != null) {
                long min = Math.max(0, Duration.between(r, i.getCreatedAt()).toMinutes());
                v = BigDecimal.valueOf(min).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
            }
            if (!v.equals(value(i.getId(), p.delayCol()))) set(i, cols.get(p.delayCol()), v);
        }
        // 장애등급: 영향도·긴급도·유형을 바꿨거나 비어 있을 때만 다시 계산 (이관된 기존 등급은 건드리지 않음)
        if (p.gradeCol() != null && p.impactCol() != null && p.urgencyCol() != null && cols.containsKey(p.gradeCol())) {
            String cur = value(i.getId(), p.gradeCol());
            if (gradeTouched || cur.isEmpty()) {
                String g = null;
                if (!isGradeType(i, p)) { if (i.getType() != null) g = "N/A"; }
                else {
                    int a = level(value(i.getId(), p.impactCol())), b = level(value(i.getId(), p.urgencyCol()));
                    if (a > 0 && b > 0) g = grade(a, b);
                    else if ("N/A".equals(cur)) g = "";  // 장애로 바뀌었는데 아직 영향도·긴급도가 없으면 N/A 를 지움
                }
                if (g != null && !g.equals(cur)) { set(i, cols.get(p.gradeCol()), g); logs.add(cols.get(p.gradeCol()).getName() + ": " + (cur.isEmpty() ? "없음" : cur) + " → " + (g.isEmpty() ? "없음" : g) + " (자동)"); }
            }
        }
        if (p.workHoursCol() != null && cols.containsKey(p.workHoursCol()) && extraDao.selectTimeCountAllByItem(i.getId()) > 0) {
            BigDecimal sum = extraDao.selectTimeSumByItem(i.getId());
            String v = sum == null || sum.signum() == 0 ? "" : sum.stripTrailingZeros().toPlainString();
            if (!v.equals(value(i.getId(), p.workHoursCol()))) set(i, cols.get(p.workHoursCol()), v);
        }
    }

    /** 영향도·긴급도 → 1(상)·2(중)·3(하), 모르면 0 */
    static int level(String v) {
        if (v == null) return 0;
        String s = v.replace(" ", "");
        if (s.matches(".*(상|높음|High|HIGH|긴급).*")) return 1;
        if (s.matches(".*(중|보통|Medium|MEDIUM).*")) return 2;
        if (s.matches(".*(하|낮음|Low|LOW).*")) return 3;
        return 0;
    }
    /** 영향도 × 긴급도 등급표: 상상 1등급, 상중·중상 2등급, 상하·중중·하상 3등급, 나머지 4등급 */
    static String grade(int impact, int urgency) {
        int s = impact + urgency;
        return (s <= 2 ? 1 : s == 3 ? 2 : s == 4 ? 3 : 4) + "등급";
    }
    private boolean isGradeType(Item i, Policy p) {
        if (p.gradeType() == null || p.gradeType().isBlank()) return true;
        return i.getType() != null && p.gradeType().equals(i.getType().getName());
    }

    // ───────────────────────── 시간 기록·첨부

    /** 시간 기록 추가 검사 (전역: 미래일 금지·24시간 이하, 규칙: 1건·1인 1일 상한, 완료 잠금) */
    public void checkTimeEntry(Item i, Long userId, LocalDate date, BigDecimal hours) {
        if (date.isAfter(LocalDate.now())) throw ApiException.bad("미래 날짜에는 시간을 기록할 수 없습니다.");
        if (hours.compareTo(BigDecimal.valueOf(24)) > 0) throw ApiException.bad("한 번에 24시간을 넘게 기록할 수 없습니다.");
        Policy p = get(i.getBoard().getId());
        if (p == null) return;
        if (p.lockDone() && i.getStatus() != null && i.getStatus().isDone()) throw ApiException.bad("완료된 SR에는 시간을 기록할 수 없습니다. 재개 후 기록하세요.");
        if (hours.compareTo(p.maxEntryHours()) > 0) throw ApiException.bad("시간 기록 1건은 " + p.maxEntryHours().stripTrailingZeros().toPlainString() + "시간을 넘을 수 없습니다.");
        BigDecimal day = extraDao.selectTimeSumByUserDate(userId, date).add(hours);
        if (day.compareTo(p.maxDayHours()) > 0)
            throw ApiException.bad(date + " 하루 기록이 " + p.maxDayHours().stripTrailingZeros().toPlainString() + "시간을 넘습니다 (이미 "
                    + day.subtract(hours).stripTrailingZeros().toPlainString() + "시간 기록됨).");
    }

    /** 완료 잠금 상태에서 시간 기록·첨부 삭제 막기 */
    public void checkNotLocked(Item i, String what) {
        Policy p = get(i.getBoard().getId());
        if (p != null && p.lockDone() && i.getStatus() != null && i.getStatus().isDone())
            throw ApiException.bad("완료된 SR의 " + what + "은(는) 삭제할 수 없습니다. 재개 후 처리하세요.");
    }

    /** 작업시간 컬럼 = 시간 기록 합계 */
    public void syncWorkHours(Item i) {
        Policy p = get(i.getBoard().getId());
        if (p == null || p.workHoursCol() == null) return;
        BoardColumn c = partsDao.selectColumnsByBoard(i.getBoard().getId()).stream().filter(x -> x.getId().equals(p.workHoursCol())).findFirst().orElse(null);
        if (c == null || extraDao.selectTimeCountAllByItem(i.getId()) == 0) return;
        BigDecimal sum = extraDao.selectTimeSumByItem(i.getId());
        set(i, c, sum.signum() == 0 ? "" : sum.stripTrailingZeros().toPlainString());
    }

    /** 복제·가져오기에서 건너뛸 자동 컬럼 */
    public Set<Long> autoCols(Long boardId) {
        Policy p = get(boardId);
        if (p == null) return Set.of();
        Set<Long> s = new HashSet<>(p.autoCols());
        if (p.agreedCol() != null) s.add(p.firstAgreedCol() == null ? -1L : p.firstAgreedCol());
        return s;
    }

    // ───────────────────────── 내부

    private String value(Long itemId, Long colId) {
        if (colId == null) return "";
        return itemDao.selectValuesByItemAndColumns(itemId, List.of(colId)).stream().findFirst().map(ItemValue::getValue).orElse("");
    }

    /** 규칙이 쓰는 내부 저장 (검사 없이) */
    private void set(Item i, BoardColumn c, String v) {
        if (c == null) return;
        ItemValue cur = itemDao.selectValuesByItemAndColumns(i.getId(), List.of(c.getId())).stream().findFirst().orElse(null);
        if (v == null || v.isEmpty()) { if (cur != null) itemDao.deleteValue(cur.getId()); return; }
        if (cur != null) { if (!v.equals(cur.getValue())) { cur.setValue(v); itemDao.updateValue(cur); } return; }
        ItemValue nv = new ItemValue();
        nv.setItemId(i.getId()); nv.setColumnId(c.getId()); nv.setValue(v);
        itemDao.insertValue(nv);
    }

    private BoardStatus status(Object sid) {
        Long id = toLong(sid);
        return id == null ? null : partsDao.selectStatusById(id);
    }
    private String colName(Long id) {
        BoardColumn c = id == null ? null : partsDao.selectColumnById(id);
        return c == null ? "컬럼" : c.getName();
    }
    private static BoardColumn find(List<BoardColumn> cols, String name) {
        return cols.stream().filter(c -> c.getName().replace(" ", "").equalsIgnoreCase(name.replace(" ", ""))).findFirst().orElse(null);
    }
    private BoardColumn ensure(Board b, List<BoardColumn> cols, String name, String type, boolean visible) {
        BoardColumn c = find(cols, name);
        if (c != null) return c;
        c = new BoardColumn();
        c.setBoard(b); c.setName(name); c.setType(type); c.setVisible(visible);
        c.setSortOrder(partsDao.selectColumnMaxSort(b.getId()) + 1);
        partsDao.insertColumn(c);
        cols.add(c);
        return c;
    }
    private void writeConfig(Board b, ObjectNode policy) {
        try {
            ObjectNode root = b.getColumnConfig() == null || b.getColumnConfig().isBlank() ? json.createObjectNode() : (ObjectNode) json.readTree(b.getColumnConfig());
            root.set(KEY, policy);
            b.setColumnConfig(json.writeValueAsString(root));
            boardDao.update(b);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("워크스페이스 설정을 저장할 수 없습니다.");
        }
    }
    private static void putId(ObjectNode p, String k, BoardColumn c) { if (c != null) p.put(k, c.getId()); }
    private static Long id(JsonNode n, String k) { JsonNode v = n.get(k); return v == null || v.isNull() || !v.canConvertToLong() ? null : v.asLong(); }
    private static Long id(BoardStatus s) { return s == null ? null : s.getId(); }
    private static BigDecimal dec(JsonNode n, String k, String def) { JsonNode v = n.get(k); try { return v == null || v.isNull() ? new BigDecimal(def) : new BigDecimal(v.asText()); } catch (Exception e) { return new BigDecimal(def); } }
    private static BigDecimal toDec(Object o, String def) { try { return o == null || o.toString().isBlank() ? new BigDecimal(def) : new BigDecimal(o.toString()); } catch (Exception e) { throw ApiException.bad("숫자 형식이 올바르지 않습니다."); } }
    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.valueOf(o.toString().trim()); } catch (Exception e) { return null; }
    }
    private static boolean blank(Object o) { return o == null || o.toString().isBlank(); }
    static LocalDateTime parseDt(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim().replace('T', ' ');
        try {
            if (s.length() == 10) return LocalDate.parse(s).atStartOfDay();
            if (s.length() == 16) return LocalDateTime.parse(s, DT);
            return LocalDateTime.parse(s.substring(0, 19), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) { return null; }
    }
}
