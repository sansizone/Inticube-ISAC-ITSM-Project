package com.inticube.teamboard.service;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.dao.*;
import com.inticube.teamboard.domain.*;
import com.inticube.teamboard.web.ApiException;
import com.inticube.teamboard.web.dto.Dtos;
import com.inticube.teamboard.web.dto.Dtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.inticube.teamboard.dao.Rows.*;

/** 업무(행): 생성·수정·이동·삭제, 댓글, 첨부, 검색, 내 대시보드 */
@Service
@RequiredArgsConstructor
@Transactional
public class ItemService {
    private static final Pattern MENTION = Pattern.compile("@([A-Za-z0-9._-]{3,50}(?:@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)?)");  // @아이디 또는 @이메일아이디

    private final ItemDao itemDao;
    private final MentionResolver mentions;
    private final ItemExtraDao extraDao;
    private final BoardPartsDao partsDao;
    private final BoardDutyDao dutyDao;
    private final BoardDao boardDao;
    private final AppUserDao userDao;
    private final NotificationService notifier;
    private final FileStorageService storage;
    private final BoardService boardService;
    private final DutyService dutyService;
    private final AccessGuard guard;
    private final RelationService relations;
    private final MaintenanceService maint;

    public Item getItem(Long id) {
        Item i = itemDao.selectById(id);
        if (i == null) throw ApiException.notFound("아이템");
        return i;
    }

    private AppUser getUser(Long id) {
        AppUser u = userDao.selectById(id);
        if (u == null) throw ApiException.notFound("사용자");
        return u;
    }

    public ItemDto insertItem(Long groupId, ItemCreateRequest req, UserPrincipal me) {
        BoardGroup g = partsDao.selectGroupById(groupId);
        if (g == null) throw ApiException.notFound("그룹");
        AppUser actor = getUser(me.id());
        Item i = new Item();
        i.setBoard(g.getBoard());
        i.setGroup(g);
        i.setName(req.name().trim());
        i.setCreatedBy(actor);
        i.setSortOrder(itemDao.selectMaxSort(groupId) + 1);
        if (req.parentId() != null) {
            Item parent = getItem(req.parentId());
            if (!parent.getBoard().getId().equals(g.getBoard().getId())) throw ApiException.bad("다른 워크스페이스의 업무입니다.");
            if (parent.getParent() != null) throw ApiException.bad("하위 업무 아래에는 다시 하위 업무를 만들 수 없습니다.");
            i.setParent(parent);
            i.setGroup(parent.getGroup());
        }
        partsDao.selectStatusesByBoard(g.getBoard().getId()).stream().findFirst().ifPresent(i::setStatus);
        i.setKeyNum(boardService.getNextKey(g.getBoard()));
        if (i.getParent() != null) i.setType(i.getParent().getType()); else i.setType(boardService.getDefaultType(g.getBoard().getId()));
        itemDao.insert(i);
        maint.insertWatch(i.getId(), actor);
        return BoardService.toDto(i, 0, 0);
    }

    /**
     * 부분 수정. body 에 키가 있으면 변경(값이 null 이면 비움), 없으면 유지.
     * 허용 키: name, description, statusId, assigneeId, dutyId, priority, startDate, dueDate, groupId, typeId, versionId,
     * estimateHours, progress, recurRule, values
     */
    public ItemDto updateItem(Long id, Map<String, Object> body, UserPrincipal me) {
        Item i = getItem(id);
        AppUser actorFull = getUser(me.id());
        String boardName = i.getBoard().getName();
        List<String> logs = new ArrayList<>();
        List<Runnable> after = new ArrayList<>(); // 본 업무 저장 뒤 실행할 후속 작업
        BoardStatus[] statusTo = { null };       // 이번 수정으로 바뀐 상태 (자동화 규칙 실행용)

        if (body.containsKey("name")) {
            String name = str(body.get("name"));
            if (name == null || name.isBlank() || name.length() > 300) throw ApiException.bad("이름은 1~300자여야 합니다.");
            i.setName(name.trim());
        }
        if (body.containsKey("description")) {
            String d = str(body.get("description"));
            if (d != null && d.length() > 50000) throw ApiException.bad("설명은 50,000자 이하여야 합니다.");
            i.setDescription(d == null || d.isBlank() ? "" : d);
        }
        if (body.containsKey("statusId")) {
            Long sid = toLong(body.get("statusId"));
            BoardStatus before = i.getStatus();
            BoardStatus next = null;
            if (sid != null) {
                next = partsDao.selectStatusById(sid);
                if (next == null) throw ApiException.notFound("상태");
                if (!next.getBoard().getId().equals(i.getBoard().getId())) throw ApiException.bad("다른 보드의 상태입니다.");
            }
            if (!Objects.equals(id(before), id(next))) {
                statusTo[0] = next;
                i.setStatus(next);
                logs.add("상태: " + label(before) + " → " + label(next));
                if (i.getRespondedAt() == null) i.setRespondedAt(LocalDateTime.now());
                boolean nowDone = next != null && next.isDone();
                boolean wasDone = before != null && before.isDone();
                if (nowDone && !wasDone) { i.setResolvedAt(LocalDateTime.now()); if (i.getProgress() != null) i.setProgress(100); }
                if (!nowDone && wasDone) i.setResolvedAt(null);
                String msg = actorFull.getName() + "님이 [" + boardName + "] '" + i.getName() + "'을(를) '" + label(next) + "'(으)로 변경했습니다.";
                Set<Long> told = new HashSet<>();
                if (nowDone && i.getCreatedBy() != null) { notifier.insertNotify(i.getCreatedBy(), actorFull, NotificationType.STATUS, msg, i); told.add(i.getCreatedBy().getId()); }
                maint.insertNotifyWatchers(i, actorFull, told, NotificationType.WATCH, msg);
                if (nowDone && !wasDone && i.getRecurRule() != null && !i.getRecurRule().isBlank()) spawnNext(i, actorFull, logs);
            }
        }
        if (body.containsKey("assigneeId")) {
            Long uid = toLong(body.get("assigneeId"));
            AppUser before = i.getAssignee();
            AppUser next = null;
            if (uid != null) {
                next = userDao.selectById(uid);
                if (next == null || !next.isActive()) throw ApiException.notFound("사용자");
            }
            if (!Objects.equals(before == null ? null : before.getId(), next == null ? null : next.getId())) {
                i.setAssignee(next);
                logs.add("담당자: " + (before == null ? "없음" : before.getName()) + " → " + (next == null ? "없음" : next.getName()));
                if (i.getRespondedAt() == null && next != null) i.setRespondedAt(LocalDateTime.now());
                Set<Long> told = new HashSet<>();
                if (next != null) {
                    notifier.insertNotify(next, actorFull, NotificationType.ASSIGNED,
                            actorFull.getName() + "님이 [" + boardName + "] '" + i.getName() + "'의 담당자로 지정했습니다.", i);
                    told.add(next.getId());
                    maint.insertWatch(i.getId(), next);
                }
                maint.insertNotifyWatchers(i, actorFull, told, NotificationType.WATCH,
                        actorFull.getName() + "님이 [" + boardName + "] '" + i.getName() + "' 담당자를 " + (next == null ? "없음" : next.getName()) + "(으)로 바꿨습니다.");
            }
        }
        if (body.containsKey("dutyId")) {
            Long did = toLong(body.get("dutyId"));
            BoardDuty before = i.getDuty();
            BoardDuty next = did == null ? null : dutyService.getDuty(did);
            if (next != null && !next.getBoard().getId().equals(i.getBoard().getId())) throw ApiException.bad("다른 워크스페이스의 담당업무입니다.");
            if (!Objects.equals(before == null ? null : before.getId(), next == null ? null : next.getId())) {
                i.setDuty(next);
                logs.add("담당업무: " + (before == null ? "없음" : before.getName()) + " → " + (next == null ? "없음" : next.getName()));
                // 담당자가 비어 있고 이번 요청에서 담당자를 직접 지정하지 않았다면 정 담당자 자동 배정
                if (next != null && i.getAssignee() == null && !body.containsKey("assigneeId")) {
                    AppUser primary = dutyService.getInheritedPrimary(next);
                    if (primary != null) {
                        i.setAssignee(primary);
                        logs.add("담당자: 없음 → " + primary.getName() + " (담당업무 정 담당자 자동 지정)");
                        notifier.insertNotify(primary, actorFull, NotificationType.ASSIGNED,
                                "[" + boardName + "] '" + i.getName() + "'이(가) 담당업무 '" + next.getName() + "' 정 담당자로 자동 배정되었습니다.", i);
                    }
                }
            }
        }
        if (body.containsKey("priority")) {
            Object p = body.get("priority");
            Priority before = i.getPriority();
            Priority next = p == null || p.toString().isBlank() ? null : Priority.valueOf(p.toString());
            if (before != next) {
                i.setPriority(next);
                logs.add("우선순위: " + priorityLabel(before) + " → " + priorityLabel(next));
            }
        }
        if (body.containsKey("startDate")) i.setStartDate(toDate(body.get("startDate")));
        if (body.containsKey("dueDate")) {
            LocalDate before = i.getDueDate();
            LocalDate next = toDate(body.get("dueDate"));
            if (!Objects.equals(before, next)) {
                i.setDueDate(next);
                logs.add("마감일: " + (before == null ? "없음" : before) + " → " + (next == null ? "없음" : next));
            }
        }
        if (i.getStartDate() != null && i.getDueDate() != null && i.getStartDate().isAfter(i.getDueDate()))
            throw ApiException.bad("시작일은 마감일보다 늦을 수 없습니다.");
        if (body.containsKey("groupId")) {
            Long gid = toLong(body.get("groupId"));
            BoardGroup g = gid == null ? null : partsDao.selectGroupById(gid);
            if (g == null || !g.getBoard().getId().equals(i.getBoard().getId())) throw ApiException.bad("잘못된 그룹입니다.");
            if (i.getParent() != null) throw ApiException.bad("하위 업무는 상위 업무의 그룹을 따릅니다.");
            if (!g.getId().equals(i.getGroup().getId())) {
                logs.add("그룹 이동: " + i.getGroup().getName() + " → " + g.getName());
                i.setGroup(g);
                i.setSortOrder(itemDao.selectMaxSort(g.getId()) + 1);
                after.add(() -> itemDao.updateGroupOfChildren(i.getId(), g.getId()));
            }
        }
        if (body.containsKey("typeId")) {
            Long tid = toLong(body.get("typeId"));
            BoardType before = i.getType();
            BoardType next = null;
            if (tid != null) {
                next = partsDao.selectTypeById(tid);
                if (next == null) throw ApiException.notFound("유형");
                if (!next.getBoard().getId().equals(i.getBoard().getId())) throw ApiException.bad("다른 워크스페이스의 유형입니다.");
            }
            if (!Objects.equals(before == null ? null : before.getId(), next == null ? null : next.getId())) {
                i.setType(next);
                logs.add("유형: " + (before == null ? "없음" : before.getName()) + " → " + (next == null ? "없음" : next.getName()));
                Long typeId = next == null ? null : next.getId();
                after.add(() -> itemDao.updateTypeOfChildren(i.getId(), typeId));
            }
        }
        if (body.containsKey("versionId")) {
            Long vid = toLong(body.get("versionId"));
            BoardVersion before = i.getVersion();
            BoardVersion next = null;
            if (vid != null) {
                next = partsDao.selectVersionById(vid);
                if (next == null) throw ApiException.notFound("버전");
                if (!next.getBoard().getId().equals(i.getBoard().getId())) throw ApiException.bad("다른 워크스페이스의 버전입니다.");
            }
            if (!Objects.equals(before == null ? null : before.getId(), next == null ? null : next.getId())) {
                i.setVersion(next);
                logs.add("버전: " + (before == null ? "없음" : before.getName()) + " → " + (next == null ? "없음" : next.getName()));
            }
        }
        if (body.containsKey("estimateHours")) {
            Object v = body.get("estimateHours");
            BigDecimal next = v == null || v.toString().isBlank() ? null : new BigDecimal(v.toString().replace(",", ""));
            if (next != null && (next.signum() < 0 || next.compareTo(new BigDecimal("99999")) > 0)) throw ApiException.bad("예상 시간은 0~99999 사이여야 합니다.");
            if (!Objects.equals(i.getEstimateHours(), next)) {
                logs.add("예상 시간: " + hours(i.getEstimateHours()) + " → " + hours(next));
                i.setEstimateHours(next);
            }
        }
        if (body.containsKey("progress")) {
            Long p = toLong(body.get("progress"));
            Integer next = p == null ? null : (int) Math.max(0, Math.min(100, p));
            if (!Objects.equals(i.getProgress(), next)) {
                logs.add("진척률: " + (i.getProgress() == null ? "-" : i.getProgress() + "%") + " → " + (next == null ? "-" : next + "%"));
                i.setProgress(next);
            }
        }
        if (body.containsKey("recurRule")) {
            String r = str(body.get("recurRule"));
            if (r != null && !r.isBlank() && !r.matches("(DAILY|WEEKLY|MONTHLY|YEARLY):[1-9][0-9]?")) throw ApiException.bad("반복 규칙 형식이 올바르지 않습니다.");
            String next = r == null || r.isBlank() ? null : r;
            if (!Objects.equals(i.getRecurRule(), next)) {
                logs.add("반복: " + recurLabel(i.getRecurRule()) + " → " + recurLabel(next));
                i.setRecurRule(next);
            }
        }
        Map<Long, String> changedValues = null;
        if (body.get("values") instanceof Map<?, ?> vm && !vm.isEmpty()) {
            changedValues = updateValues(i, vm, logs);
            // 연결 값을 고르면 그 항목에 연결된 다른 워크스페이스 값도 함께 채움 (예: 서비스요청자 → 그 직원의 고객사)
            Map<Long, String> auto = getRelationAutoFill(i, changedValues, vm.keySet());
            if (!auto.isEmpty()) changedValues.putAll(updateValues(i, auto, logs));
        }
        i.setUpdatedAt(LocalDateTime.now());
        itemDao.update(i);
        after.forEach(Runnable::run);
        // 활동 기록: 반복 업무 안내는 그대로, 값 변경은 '생성 직후 제외 + 연속 변경 묶기'
        List<String> changes = new ArrayList<>();
        for (String log : logs) { if (log.startsWith("반복 업무")) insertSystemLog(i, actorFull, log); else changes.add(log); }
        insertChangeLog(i, actorFull, changes);
        if (statusTo[0] != null && i.getParent() == null) runAutomations(i, statusTo[0], actorFull);
        return BoardService.toDto(i, 0, 0, changedValues);
    }

    // ---------- 자동화 규칙 ----------
    /**
     * 워크스페이스 설정(column_config.automations)의 규칙 중 '상태가 X 가 되면' 에 맞는 첫 규칙을 실행.
     * 그룹 이동 또는 다른 워크스페이스로 이동. 규칙은 관리자가 저장할 때 검증하므로 실행 시 권한은 다시 보지 않음.
     */
    private void runAutomations(Item i, BoardStatus status, AppUser actor) {
        Board full = boardDao.selectById(i.getBoard().getId()); // 업무 조회 시 워크스페이스 설정(JSON)은 같이 읽지 않음
        for (Automations.Rule r : Automations.read(full == null ? null : full.getColumnConfig())) {
            if (!status.getId().equals(r.statusId())) continue;
            if ("group".equals(r.action())) {
                BoardGroup g = r.groupId() == null ? null : partsDao.selectGroupById(r.groupId());
                if (g == null || !g.getBoard().getId().equals(i.getBoard().getId()) || g.getId().equals(i.getGroup().getId())) return;
                String fromName = i.getGroup().getName();
                i.setGroup(g);
                i.setSortOrder(itemDao.selectMaxSort(g.getId()) + 1);
                itemDao.update(i);
                itemDao.updateGroupOfChildren(i.getId(), g.getId());
                insertSystemLog(i, actor, "자동화: '" + status.getLabel() + "' → 그룹 이동 " + fromName + " → " + g.getName());
            } else if ("board".equals(r.action())) {
                Board to = r.boardId() == null ? null : boardDao.selectById(r.boardId());
                if (to == null || to.getId().equals(i.getBoard().getId())) return;
                moveToBoard(i, to, r.groupId(), actor, "자동화: '" + status.getLabel() + "' → 워크스페이스 이동");
            }
            return;
        }
    }

    // ---------- 양식 보기 ----------
    /**
     * 양식 보기로 접수. 보기 설정(config): { groupId, statusId, fields: [{ key: "description" | 컬럼ID, required }] }
     * 업무명(name)은 항상 필수. 설정에 없는 항목은 받지 않음.
     */
    public ItemDto insertFormItem(Long viewId, FormSubmitRequest req, UserPrincipal me) {
        guard.getViewChecked(viewId);
        BoardView v = partsDao.selectViewById(viewId);
        if (!"form".equals(v.getType())) throw ApiException.bad("양식 보기가 아닙니다.");
        Automations.Form f = Automations.readForm(v.getConfig());
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) throw ApiException.bad("제목을 입력하세요.");
        List<BoardGroup> groups = partsDao.selectGroupsByBoard(v.getBoardId());
        BoardGroup g = groups.stream().filter(x -> x.getId().equals(f.groupId())).findFirst().orElse(groups.isEmpty() ? null : groups.get(0));
        if (g == null) throw ApiException.bad("워크스페이스에 그룹이 없습니다.");
        Map<String, Object> in = req.values() == null ? Map.of() : req.values();
        Map<String, Object> body = new HashMap<>();
        Map<String, Object> vals = new LinkedHashMap<>();
        for (Automations.Field fd : f.fields()) {
            Object raw = in.get(fd.key());
            String val = raw == null ? "" : raw.toString().trim();
            if (fd.required() && val.isEmpty()) throw ApiException.bad("필수 항목을 입력하세요.");
            if (val.isEmpty()) continue;
            if ("description".equals(fd.key())) body.put("description", val); else vals.put(fd.key(), val);
        }
        ItemDto created = insertItem(g.getId(), new ItemCreateRequest(name.length() > 300 ? name.substring(0, 300) : name, null), me);
        if (f.statusId() != null) {
            BoardStatus st = partsDao.selectStatusById(f.statusId());
            if (st != null && st.getBoard().getId().equals(v.getBoardId())) body.put("statusId", st.getId());
        }
        if (!vals.isEmpty()) body.put("values", vals);
        ItemDto out = body.isEmpty() ? created : updateItem(created.id(), body, me);
        insertSystemLog(getItem(created.id()), getUser(me.id()), "양식으로 접수: " + v.getName());
        return out;
    }

    private static String hours(BigDecimal h) { return h == null ? "없음" : h.stripTrailingZeros().toPlainString() + "h"; }

    public static String recurLabel(String rule) {
        if (rule == null) return "없음";
        String[] p = rule.split(":");
        int n = p.length > 1 ? Integer.parseInt(p[1]) : 1;
        String unit = switch (p[0]) { case "DAILY" -> "일"; case "WEEKLY" -> "주"; case "MONTHLY" -> "개월"; default -> "년"; };
        return (n == 1 ? "매" : n + "") + unit + "마다";
    }

    /** 반복 업무: 완료 처리되면 다음 회차를 같은 그룹에 만든다 (유형·담당자·담당업무·우선순위·예상시간·컬럼 값·반복 규칙 복사) */
    private void spawnNext(Item done, AppUser actor, List<String> logs) {
        String[] p = done.getRecurRule().split(":");
        int n = p.length > 1 ? Integer.parseInt(p[1]) : 1;
        LocalDate base = done.getDueDate() != null ? done.getDueDate() : LocalDate.now();
        LocalDate nextDue = switch (p[0]) { case "DAILY" -> base.plusDays(n); case "WEEKLY" -> base.plusWeeks(n); case "MONTHLY" -> base.plusMonths(n); default -> base.plusYears(n); };
        while (!nextDue.isAfter(LocalDate.now().minusDays(1)) && p[0].equals("DAILY")) nextDue = nextDue.plusDays(n); // 지난 날짜면 오늘 이후로
        Item next = new Item();
        next.setBoard(done.getBoard());
        next.setGroup(done.getGroup());
        next.setName(done.getName());
        next.setDescription(done.getDescription());
        next.setType(done.getType());
        next.setAssignee(done.getAssignee());
        next.setDuty(done.getDuty());
        next.setPriority(done.getPriority());
        next.setEstimateHours(done.getEstimateHours());
        next.setRecurRule(done.getRecurRule());
        next.setVersion(done.getVersion());
        if (done.getStartDate() != null && done.getDueDate() != null) next.setStartDate(nextDue.minusDays(java.time.temporal.ChronoUnit.DAYS.between(done.getStartDate(), done.getDueDate())));
        next.setDueDate(nextDue);
        next.setCreatedBy(actor);
        next.setSortOrder(itemDao.selectMaxSort(done.getGroup().getId()) + 1);
        partsDao.selectStatusesByBoard(done.getBoard().getId()).stream().findFirst().ifPresent(next::setStatus);
        next.setKeyNum(boardService.getNextKey(done.getBoard()));
        itemDao.insert(next);
        List<ItemValue> copies = new ArrayList<>();
        for (ItemValue v : itemDao.selectValuesByItem(done.getId())) {
            ItemValue c = new ItemValue(); c.setItemId(next.getId()); c.setColumnId(v.getColumnId()); c.setValue(v.getValue()); copies.add(c);
        }
        if (!copies.isEmpty()) itemDao.batchInsertValues(copies);
        if (done.getAssignee() != null) maint.insertWatch(next.getId(), done.getAssignee());
        done.setRecurRule(null); // 다음 회차가 반복을 이어받음
        String k = Dtos.keyOf(next);
        logs.add("반복 업무 다음 회차 생성: " + (k == null ? "#" + next.getId() : k) + " (마감 " + nextDue + ")");
        insertSystemLog(next, actor, "반복 업무에서 생성됨 (이전 회차 " + (Dtos.keyOf(done) == null ? "#" + done.getId() : Dtos.keyOf(done)) + ")");
    }

    /** 사용자 정의 컬럼 값 수정. 값이 null/빈 문자열이면 삭제 */
    private Map<Long, String> updateValues(Item i, Map<?, ?> vm, List<String> logs) {
        Map<Long, BoardColumn> cols = partsDao.selectColumnsByBoard(i.getBoard().getId()).stream()
                .collect(Collectors.toMap(BoardColumn::getId, Function.identity()));
        Map<Long, String> req = new LinkedHashMap<>();
        for (var e : vm.entrySet()) {
            Long cid = toLong(e.getKey());
            BoardColumn c = cid == null ? null : cols.get(cid);
            if (c == null) throw ApiException.bad("이 워크스페이스의 컬럼이 아닙니다: " + e.getKey());
            String v = e.getValue() == null ? "" : e.getValue().toString();
            if (RelationService.TYPE.equals(c.getType())) v = normalizeRelation(c, v);
            req.put(cid, normalizeValue(c, v));
        }
        Map<Long, ItemValue> existing = itemDao.selectValuesByItemAndColumns(i.getId(), req.keySet()).stream()
                .collect(Collectors.toMap(ItemValue::getColumnId, Function.identity(), (a, b) -> a));
        Map<Long, String> out = new LinkedHashMap<>();
        for (var e : req.entrySet()) {
            BoardColumn c = cols.get(e.getKey());
            ItemValue cur = existing.get(e.getKey());
            String before = cur == null ? "" : cur.getValue();
            String next = e.getValue();
            if (before.equals(next)) continue;
            if (next.isEmpty()) { if (cur != null) itemDao.deleteValue(cur.getId()); }
            else if (cur != null) { cur.setValue(next); itemDao.updateValue(cur); }
            else {
                ItemValue nv = new ItemValue();
                nv.setItemId(i.getId()); nv.setColumnId(c.getId()); nv.setValue(next);
                itemDao.insertValue(nv);
            }
            out.put(c.getId(), next);
            if (RelationService.TYPE.equals(c.getType())) logs.add(c.getName() + ": " + shortLog(relNames(before)) + " → " + shortLog(relNames(next)));
            else logs.add(c.getName() + ": " + shortLog(before) + " → " + shortLog(next));
        }
        return out;
    }

    /** 연결 컬럼 값: 대상 워크스페이스에 있는 업무 ID 만 남김 */
    /** 연결 값 정리: 대상 워크스페이스에 있는 업무 ID 만 남김 (입력 순서 유지) */
    private String normalizeRelation(BoardColumn c, String v) {
        List<Long> ids = RelationService.getIds(v);
        if (ids.isEmpty()) return "";
        if (ids.size() > 50) throw ApiException.bad("'" + c.getName() + "' 은(는) 50개까지 연결할 수 있습니다.");
        Set<Long> ok = new HashSet<>();
        for (Map<String, Object> r : itemDao.selectNames(ids)) if (Objects.equals(lng(r, "board_id"), c.getRefBoardId())) ok.add(lng(r, "id"));
        return ids.stream().filter(ok::contains).map(String::valueOf).collect(Collectors.joining(","));
    }

    /**
     * 연결 값 자동 채움. 이 워크스페이스에 연결 컬럼 C(→ T)와 D(→ X)가 있고 T 와 X 가 서로 연결돼 있으면
     * (T 의 연결 컬럼이 X 를 가리키거나, X 의 연결 컬럼이 T 를 가리킴) C 에서 고른 T 항목들과 연결된 X 항목을 D 에 더한다.
     * 예: 서비스요청자(→ 고객사직원정보)에서 직원을 고르면 고객사(→ 고객사관리)에 그 직원의 고객사가 들어간다.
     * 이미 들어 있는 값은 그대로 두고 빠진 것만 더하며, 같은 요청에서 직접 바꾼 컬럼은 건드리지 않는다.
     */
    private Map<Long, String> getRelationAutoFill(Item i, Map<Long, String> changed, Set<?> requestedKeys) {
        Map<Long, String> out = new LinkedHashMap<>();
        if (changed == null || changed.isEmpty()) return out;
        Set<Long> requested = new HashSet<>();
        for (Object k : requestedKeys) { Long id = toLong(k); if (id != null) requested.add(id); }
        List<BoardColumn> allCols = partsDao.selectColumnsByBoard(i.getBoard().getId());
        List<BoardColumn> rels = allCols.stream()
                .filter(c -> RelationService.TYPE.equals(c.getType()) && c.getRefBoardId() != null).toList();
        if (rels.isEmpty()) return out;
        Board board = boardDao.selectById(i.getBoard().getId());
        Map<Long, String> current = null;
        for (BoardColumn c : allCols) {
            if (!changed.containsKey(c.getId())) continue;
            // 고른 항목: 연결 컬럼이면 업무 ID, 후보 워크스페이스가 있는 사람 컬럼이면 이름이 같은 그 워크스페이스 항목
            Long t;
            List<Long> picked;
            if (RelationService.TYPE.equals(c.getType()) && c.getRefBoardId() != null) {
                t = c.getRefBoardId();
                picked = RelationService.getIds(changed.get(c.getId()));
            } else if ("PEOPLE".equals(c.getType()) && (t = RelationService.getPeopleSource(board, c.getId())) != null) {
                Set<String> want = new HashSet<>();
                for (String n : changed.get(c.getId()).split("[,;\\n]")) if (!n.isBlank()) want.add(n.trim().toLowerCase());
                picked = new ArrayList<>();
                for (Map<String, Object> r : itemDao.selectPickRows(t))
                    if (want.contains(Rows.str(r, "item_name").trim().toLowerCase())) picked.add(lng(r, "id"));
            } else continue;
            if (picked.isEmpty()) continue;
            final Long tb = t;
            List<BoardColumn> tCols = partsDao.selectColumnsByBoard(tb);
            for (BoardColumn d : rels) {
                Long x = d.getRefBoardId();
                if (d.getId().equals(c.getId()) || x.equals(tb) || requested.contains(d.getId()) || !d.getScope().equals(c.getScope())) continue;
                LinkedHashSet<Long> mapped = new LinkedHashSet<>();
                // (a) T 쪽 연결 컬럼이 X 를 가리킴: 직원 → 고객사
                Set<Long> eIds = tCols.stream().filter(e -> RelationService.TYPE.equals(e.getType()) && x.equals(e.getRefBoardId()))
                        .map(BoardColumn::getId).collect(Collectors.toSet());
                if (!eIds.isEmpty())
                    for (ItemValue v : itemDao.selectValuesByItems(picked))
                        if (eIds.contains(v.getColumnId())) mapped.addAll(RelationService.getIds(v.getValue()));
                // (b) X 쪽 연결 컬럼이 T 를 가리킴: 고객사 → 직원 목록
                List<Long> fIds = partsDao.selectColumnsByBoard(x).stream()
                        .filter(f -> RelationService.TYPE.equals(f.getType()) && tb.equals(f.getRefBoardId())).map(BoardColumn::getId).toList();
                if (!fIds.isEmpty())
                    for (ItemValue v : itemDao.selectValuesByColumns(fIds))
                        if (RelationService.getIds(v.getValue()).stream().anyMatch(picked::contains)) mapped.add(v.getItemId());
                if (mapped.isEmpty()) continue;
                if (current == null) {
                    current = new HashMap<>();
                    for (ItemValue v : itemDao.selectValuesByItem(i.getId())) current.put(v.getColumnId(), v.getValue());
                }
                String cur = out.containsKey(d.getId()) ? out.get(d.getId()) : current.getOrDefault(d.getId(), "");
                List<String> toks = new ArrayList<>();
                for (String p : cur.split(",")) if (!p.isBlank()) toks.add(p.trim());
                boolean added = false;
                for (Long m : mapped) if (!toks.contains(String.valueOf(m))) { toks.add(String.valueOf(m)); added = true; }
                if (added) out.put(d.getId(), String.join(",", toks));
            }
        }
        return out;
    }

    /** 연결 컬럼 고르기 창에서 '대상 워크스페이스에 추가': 대상 워크스페이스 첫 그룹에 새 항목을 만들고 고르기 후보로 돌려줌 */
    public PickOption insertRelationTarget(Long columnId, String name, UserPrincipal me) {
        BoardColumn c = partsDao.selectColumnById(columnId);
        if (c == null) throw ApiException.notFound("컬럼");
        // 연결 컬럼이면 연결 대상, 사람 컬럼이면 후보 워크스페이스(예: 고객사직원정보)에 추가
        Long target = RelationService.TYPE.equals(c.getType()) ? c.getRefBoardId()
                : "PEOPLE".equals(c.getType()) ? RelationService.getPeopleSource(boardDao.selectById(c.getBoard().getId()), c.getId()) : null;
        if (target == null) throw ApiException.bad("항목을 추가할 워크스페이스가 정해지지 않은 컬럼입니다.");
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 300) throw ApiException.bad("이름은 1~300자여야 합니다.");
        guard.getBoardChecked(target);
        List<BoardGroup> gs = partsDao.selectGroupsByBoard(target);
        if (gs.isEmpty()) throw ApiException.bad("대상 워크스페이스에 그룹이 없습니다.");
        ItemDto created = insertItem(gs.get(0).getId(), new ItemCreateRequest(n, null), me);
        return new PickOption(created.id(), n, gs.get(0).getName());
    }

    private String relNames(String v) {
        List<Long> ids = RelationService.getIds(v);
        if (ids.isEmpty()) return "";
        Map<Long, String> names = new HashMap<>();
        for (Map<String, Object> r : itemDao.selectNames(ids)) names.put(lng(r, "id"), Rows.str(r, "item_name"));
        return ids.stream().map(id -> names.getOrDefault(id, "#" + id)).collect(Collectors.joining(", "));
    }

    private static String shortLog(String v) {
        if (v == null || v.isEmpty()) return "없음";
        String one = v.replaceAll("\\s+", " ");
        return one.length() > 40 ? one.substring(0, 40) + "…" : one;
    }

    static String normalizeValue(BoardColumn c, String raw) {
        String v = raw == null ? "" : ("LONG_TEXT".equals(c.getType()) ? raw.strip() : raw.trim());
        if (v.isEmpty()) return "";
        int max = "LONG_TEXT".equals(c.getType()) ? 100_000 : 2000;
        if (v.length() > max) throw ApiException.bad("'" + c.getName() + "' 값은 " + max + "자 이하여야 합니다.");
        switch (c.getType()) {
            case "NUMBER" -> {
                try { new BigDecimal(v.replace(",", "")); } catch (NumberFormatException e) { throw ApiException.bad("'" + c.getName() + "' 은(는) 숫자여야 합니다."); }
                return v.replace(",", "");
            }
            case "PHONE" -> { return PhoneFormat.format(v); }
            case "DATE" -> {
                try { return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v).toString(); }
                catch (Exception e) { throw ApiException.bad("'" + c.getName() + "' 은(는) 날짜(yyyy-MM-dd)여야 합니다."); }
            }
            case "DATETIME" -> {
                if (!v.matches("\\d{4}-\\d{2}-\\d{2}( \\d{2}:\\d{2}(:\\d{2})?)?")) throw ApiException.bad("'" + c.getName() + "' 은(는) 'yyyy-MM-dd HH:mm' 형식이어야 합니다.");
                return v.replace('T', ' ');
            }
            default -> { return v; }
        }
    }

    public void deleteItem(Long id) {
        Item i = getItem(id);
        guard.setDeleteItemChecked(i);
        boardService.deleteItems(List.of(i));
    }

    /** 드래그앤드롭 결과 반영: ids 순서대로 해당 그룹에 배치 */
    public void setItemOrder(Long groupId, List<Long> ids) {
        BoardGroup g = partsDao.selectGroupById(groupId);
        if (g == null) throw ApiException.notFound("그룹");
        if (ids.isEmpty()) return;
        Map<Long, Item> map = itemDao.selectByIds(ids).stream().collect(Collectors.toMap(Item::getId, Function.identity()));
        int order = 1;
        for (Long id : ids) {
            Item i = map.get(id);
            if (i == null || !i.getBoard().getId().equals(g.getBoard().getId())) throw ApiException.bad("잘못된 아이템입니다.");
            itemDao.updateGroupAndSort(id, g.getId(), order++);
        }
    }

    @Transactional(readOnly = true)
    public ItemDetail getItemDetail(Long id) {
        Item i = getItem(id);
        List<UpdateDto> ups = extraDao.selectUpdatesByItem(id).stream().map(UpdateDto::of).toList();
        List<AttachmentDto> files = extraDao.selectAttachmentsByItem(id).stream().map(AttachmentDto::of).toList();
        long commentCount = ups.stream().filter(u -> !u.system()).count();
        Map<Long, String> vals = new LinkedHashMap<>();
        for (ItemValue v : itemDao.selectValuesByItem(id)) vals.put(v.getColumnId(), v.getValue());
        List<BoardColumn> relCols = partsDao.selectColumnsByBoard(i.getBoard().getId()).stream()
                .filter(c -> RelationService.TYPE.equals(c.getType()) && vals.containsKey(c.getId())).toList();
        Map<Long, List<String>> relVals = new HashMap<>();
        relCols.forEach(c -> relVals.put(c.getId(), List.of(vals.get(c.getId()))));
        List<TimeEntryDto> times = maint.getTimeEntries(id);
        BigDecimal spent = times.stream().map(TimeEntryDto::hours).reduce(BigDecimal.ZERO, BigDecimal::add);
        UserPrincipal me = guard.getMe();
        return new ItemDetail(BoardService.toDto(i, commentCount, files.size(), null, times.isEmpty() ? null : spent), i.getDescription(), i.getBoard().getId(), i.getBoard().getName(),
                i.getGroup().getName(), UserSummary.of(i.getCreatedBy()), i.getCreatedAt(), ups, files, vals,
                relations.getLabels(relCols, relVals),
                maint.getLinks(id), maint.getWatchers(id), maint.getWatching(id, me.id()), times);
    }

    // ---------- 다른 워크스페이스로 이동 ----------
    /**
     * 업무(하위 업무·댓글·첨부 포함)를 다른 워크스페이스로 옮긴다.
     * 상태·담당업무·컬럼은 같은 이름이 있으면 값 유지, 없으면 비움.
     */
    public ItemDto updateItemMove(Long id, MoveRequest req, UserPrincipal me) {
        Item root = getItem(id);
        if (root.getParent() != null) throw ApiException.bad("하위 업무는 상위 업무와 함께 이동합니다. 상위 업무를 이동하세요.");
        if (req.boardId() == null) throw ApiException.bad("이동할 워크스페이스를 선택하세요.");
        Board to = guard.getBoardChecked(req.boardId());
        if (root.getBoard().getId().equals(to.getId())) throw ApiException.bad("같은 워크스페이스입니다.");
        moveToBoard(root, to, req.groupId(), getUser(me.id()), "워크스페이스 이동");
        return BoardService.toDto(root, 0, 0);
    }

    /** 업무(하위 업무 포함)를 다른 워크스페이스로 옮김. 상태·유형·컬럼 값은 이름이 같은 것끼리 이어 붙임. 권한 확인은 호출하는 쪽에서 */
    private void moveToBoard(Item root, Board to, Long groupId, AppUser actor, String logTitle) {
        Board from = root.getBoard();
        MoveRequest req = new MoveRequest(to.getId(), groupId);
        List<BoardGroup> tgroups = partsDao.selectGroupsByBoard(to.getId());
        BoardGroup g = req.groupId() == null ? tgroups.stream().filter(x -> x.getName().equals(root.getGroup().getName())).findFirst().orElse(tgroups.isEmpty() ? null : tgroups.get(0))
                : tgroups.stream().filter(x -> x.getId().equals(req.groupId())).findFirst().orElseThrow(() -> ApiException.bad("대상 워크스페이스의 그룹이 아닙니다."));
        if (g == null) throw ApiException.bad("대상 워크스페이스에 그룹이 없습니다.");

        List<Item> all = new ArrayList<>();
        all.add(root);
        all.addAll(itemDao.selectByParent(root.getId()));
        List<Long> allIds = all.stream().map(Item::getId).toList();
        Map<String, BoardStatus> stByName = new HashMap<>();
        for (BoardStatus st : partsDao.selectStatusesByBoard(to.getId())) stByName.putIfAbsent(key(st.getLabel()), st);
        Map<String, BoardDuty> dutyByName = new HashMap<>();
        for (BoardDuty d : dutyDao.selectByBoard(to.getId())) dutyByName.putIfAbsent(key(d.getName()), d);
        List<BoardColumn> tcols = partsDao.selectColumnsByBoard(to.getId());
        Map<Long, BoardColumn> scols = partsDao.selectColumnsByBoard(from.getId()).stream()
                .collect(Collectors.toMap(BoardColumn::getId, Function.identity()));

        int order = itemDao.selectMaxSort(g.getId());
        int kept = 0, dropped = 0;
        Map<Long, List<ItemValue>> valsByItem = itemDao.selectValuesByItems(allIds).stream()
                .collect(Collectors.groupingBy(ItemValue::getItemId));
        Map<String, BoardType> typeByName = new HashMap<>();
        for (BoardType t : partsDao.selectTypesByBoard(to.getId())) typeByName.putIfAbsent(key(t.getName()), t);
        Map<String, BoardVersion> verByName = new HashMap<>();
        for (BoardVersion v : partsDao.selectVersionsByBoard(to.getId())) verByName.putIfAbsent(key(v.getName()), v);
        extraDao.updateTimeBoard(to.getId(), allIds);
        for (Item it : all) {
            boolean sub = it.getParent() != null;
            it.setBoard(to);
            it.setGroup(g);
            it.setKeyNum(boardService.getNextKey(to));
            it.setType(it.getType() == null ? null : typeByName.get(key(it.getType().getName())));
            it.setVersion(it.getVersion() == null ? null : verByName.get(key(it.getVersion().getName())));
            if (!sub) it.setSortOrder(++order);
            it.setStatus(it.getStatus() == null ? null : stByName.get(key(it.getStatus().getLabel())));
            it.setDuty(it.getDuty() == null ? null : dutyByName.get(key(it.getDuty().getName())));
            if (it == root) it.setUpdatedAt(LocalDateTime.now());
            itemDao.update(it);
            Set<Long> used = new HashSet<>();
            for (ItemValue v : valsByItem.getOrDefault(it.getId(), List.of())) {
                BoardColumn sc = scols.get(v.getColumnId());
                BoardColumn tc = sc == null ? null : matchColumn(tcols, sc, sub ? "SUBITEM" : "ITEM");
                if (tc == null || !used.add(tc.getId())) { itemDao.deleteValue(v.getId()); dropped++; continue; }
                v.setColumnId(tc.getId());
                itemDao.updateValue(v);
                kept++;
            }
        }
        insertSystemLog(root, actor, logTitle + ": " + from.getName() + " → " + to.getName()
                + (dropped > 0 ? " (대상에 없는 컬럼 값 " + dropped + "개 제외)" : ""));
    }

    // ---------- 여러 업무 일괄 처리 ----------
    /** 선택한 업무 중 상위 업무도 함께 선택된 하위 업무는 상위와 같이 처리되므로 제외 */
    private List<Item> getBulkRoots(List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw ApiException.bad("업무를 선택하세요.");
        if (ids.size() > 500) throw ApiException.bad("한 번에 500개까지 처리할 수 있습니다.");
        List<Item> items = itemDao.selectByIds(new ArrayList<>(new LinkedHashSet<>(ids)));
        Set<Long> sel = items.stream().map(Item::getId).collect(Collectors.toSet());
        return items.stream().filter(i -> i.getParent() == null || !sel.contains(i.getParent().getId()))
                .sorted(Comparator.comparingInt(Item::getSortOrder).thenComparing(Item::getId)).toList();
    }

    public BulkResult deleteItems(List<Long> ids) {
        List<Item> roots = getBulkRoots(ids);
        for (Item i : roots) guard.setDeleteItemChecked(i); // 하나라도 권한이 없으면 아무것도 지우지 않음
        boardService.deleteItems(roots);
        return new BulkResult(roots.size(), ids.size() - roots.size());
    }

    /** 다른 워크스페이스로 이동. 하위 업무만 선택한 경우는 상위 업무와 함께만 이동할 수 있어 건너뜀 */
    public BulkResult updateItemsMove(List<Long> ids, Long boardId, Long groupId, UserPrincipal me) {
        List<Item> roots = getBulkRoots(ids);
        int done = 0;
        for (Item it : roots) {
            if (it.getParent() != null) continue;
            updateItemMove(it.getId(), new MoveRequest(boardId, groupId), me);
            done++;
        }
        return new BulkResult(done, ids.size() - done);
    }

    /** 같은 워크스페이스·같은 그룹 맨 아래에 사본 생성. 필드·컬럼 값·하위 업무를 복사하고 댓글·첨부·활동은 복사하지 않음 */
    public BulkResult insertItemsCopy(List<Long> ids, UserPrincipal me) {
        List<Item> roots = getBulkRoots(ids);
        AppUser actor = getUser(me.id());
        int done = 0;
        for (Item src : roots) {
            Item copy = copyItem(src, src.getParent(), src.getName() + " (복사)", actor);
            if (src.getParent() == null)
                for (Item kid : itemDao.selectByParent(src.getId())) copyItem(kid, copy, kid.getName(), actor);
            String k = Dtos.keyOf(src);
            insertSystemLog(copy, actor, "업무 복제: " + (k == null ? "#" + src.getId() : k) + " 에서 복사");
            done++;
        }
        return new BulkResult(done, ids.size() - done);
    }

    private Item copyItem(Item src, Item parent, String name, AppUser actor) {
        Item c = new Item();
        c.setBoard(src.getBoard());
        c.setGroup(parent != null ? parent.getGroup() : src.getGroup());
        c.setParent(parent);
        c.setName(name.length() > 300 ? name.substring(0, 300) : name);
        c.setDescription(src.getDescription());
        c.setStatus(src.getStatus());
        c.setDuty(src.getDuty());
        c.setAssignee(src.getAssignee());
        c.setPriority(src.getPriority());
        c.setStartDate(src.getStartDate());
        c.setDueDate(src.getDueDate());
        c.setType(src.getType());
        c.setVersion(src.getVersion());
        c.setEstimateHours(src.getEstimateHours());
        c.setProgress(src.getProgress());
        c.setCreatedBy(actor);
        c.setSortOrder(parent != null ? src.getSortOrder() : itemDao.selectMaxSort(src.getGroup().getId()) + 1);
        c.setKeyNum(boardService.getNextKey(src.getBoard()));
        itemDao.insert(c);
        List<ItemValue> vals = new ArrayList<>();
        for (ItemValue v : itemDao.selectValuesByItem(src.getId())) {
            ItemValue n = new ItemValue(); n.setItemId(c.getId()); n.setColumnId(v.getColumnId()); n.setValue(v.getValue()); vals.add(n);
        }
        if (!vals.isEmpty()) itemDao.batchInsertValues(vals);
        maint.insertWatch(c.getId(), actor);
        return c;
    }

    private static String key(String s) { return s == null ? "" : s.trim().toLowerCase(); }

    /** 같은 이름(대소문자·공백 무시)의 컬럼. 같은 범위 우선. 연결 컬럼은 대상 워크스페이스까지 같아야 함 */
    private static BoardColumn matchColumn(List<BoardColumn> tcols, BoardColumn sc, String scope) {
        BoardColumn any = null;
        for (BoardColumn tc : tcols) {
            if (!key(tc.getName()).equals(key(sc.getName()))) continue;
            if (RelationService.TYPE.equals(tc.getType()) != RelationService.TYPE.equals(sc.getType())) continue;
            if (RelationService.TYPE.equals(tc.getType()) && !Objects.equals(tc.getRefBoardId(), sc.getRefBoardId())) continue;
            if (tc.getScope().equals(scope)) return tc;
            if (any == null) any = tc;
        }
        return any;
    }

    // ---------- 업데이트(댓글) ----------
    public UpdateDto insertUpdate(Long itemId, String content, Long parentId, UserPrincipal me) {
        Item i = getItem(itemId);
        AppUser author = getUser(me.id());
        ItemUpdate u = new ItemUpdate();
        u.setItem(i);
        if (parentId != null) {
            ItemUpdate p = extraDao.selectUpdateById(parentId);
            if (p == null) throw ApiException.notFound("댓글");
            if (!p.getItem().getId().equals(itemId) || p.isSystem()) throw ApiException.bad("이 업무의 댓글이 아닙니다.");
            u.setParentId(p.getParentId() != null ? p.getParentId() : p.getId());
        }
        u.setAuthor(author);
        u.setContent(content.trim());
        extraDao.insertUpdate(u);
        i.setUpdatedAt(LocalDateTime.now());
        if (i.getRespondedAt() == null && (i.getCreatedBy() == null || !i.getCreatedBy().getId().equals(author.getId()))) i.setRespondedAt(LocalDateTime.now());
        itemDao.update(i);
        maint.insertWatch(i.getId(), author);

        String where = "[" + i.getBoard().getName() + "] '" + i.getName() + "'";
        String preview = content.length() > 60 ? content.substring(0, 60) + "…" : content;
        Set<Long> notified = new HashSet<>();
        {
            for (AppUser target : mentions.getMentionedUsers(content)) {
                notifier.insertNotify(target, author, NotificationType.MENTION, author.getName() + "님이 " + where + "에서 회원님을 언급했습니다: " + preview, i);
                notified.add(target.getId());
            }
        }
        for (AppUser target : new AppUser[]{i.getAssignee(), i.getCreatedBy()}) {
            if (target != null && notified.add(target.getId()))
                notifier.insertNotify(target, author, NotificationType.COMMENT, author.getName() + "님이 " + where + "에 업데이트를 남겼습니다: " + preview, i);
        }
        maint.insertNotifyWatchers(i, author, notified, NotificationType.WATCH, author.getName() + "님이 " + where + "에 업데이트를 남겼습니다: " + preview);
        return UpdateDto.of(u);
    }

    /** 댓글 수정 (작성자 본인만). 새로 추가된 @언급에만 알림 */
    public UpdateDto updateUpdate(Long updateId, String content, UserPrincipal me) {
        ItemUpdate u = extraDao.selectUpdateById(updateId);
        if (u == null) throw ApiException.notFound("댓글");
        if (u.isSystem()) throw ApiException.bad("활동 기록은 수정할 수 없습니다.");
        if (!u.getAuthor().getId().equals(me.id())) throw ApiException.forbidden();
        Set<Long> before = new HashSet<>();
        for (AppUser x : mentions.getMentionedUsers(u.getContent())) before.add(x.getId());
        u.setContent(content.trim());
        u.setUpdatedAt(LocalDateTime.now());
        extraDao.updateUpdateContent(u);
        Item i = getItem(u.getItem().getId());
        AppUser author = getUser(me.id());
        String where = "[" + i.getBoard().getName() + "] '" + i.getName() + "'";
        String preview = u.getContent().length() > 60 ? u.getContent().substring(0, 60) + "…" : u.getContent();
        for (AppUser target : mentions.getMentionedUsers(u.getContent()))
            if (!before.contains(target.getId()))
                notifier.insertNotify(target, author, NotificationType.MENTION, author.getName() + "님이 " + where + "에서 회원님을 언급했습니다: " + preview, i);
        return UpdateDto.of(u);
    }

    public void deleteUpdate(Long updateId, UserPrincipal me) {
        ItemUpdate u = extraDao.selectUpdateById(updateId);
        if (u == null) throw ApiException.notFound("업데이트");
        if (u.isSystem()) throw ApiException.bad("활동 기록은 삭제할 수 없습니다.");
        if (!me.isAdmin() && !u.getAuthor().getId().equals(me.id())) throw ApiException.forbidden();
        extraDao.deleteUpdatesByParent(u.getId());
        extraDao.deleteUpdate(u.getId());
    }

    // ---------- 첨부파일 ----------
    public AttachmentDto insertAttachment(Long itemId, MultipartFile file, UserPrincipal me) {
        if (file == null || file.isEmpty()) throw ApiException.bad("빈 파일은 업로드할 수 없습니다.");
        Item i = getItem(itemId);
        AppUser uploader = getUser(me.id());
        String stored = storage.store(file);
        Attachment a = new Attachment();
        a.setItem(i);
        a.setOriginalName(FileStorageService.cleanName(file.getOriginalFilename()));
        a.setContentType(file.getContentType());
        a.setFileSize(file.getSize());
        a.setStoredName(stored);
        a.setUploader(uploader);
        extraDao.insertAttachment(a);
        insertSystemLog(i, uploader, "파일 첨부: " + a.getOriginalName());
        return AttachmentDto.of(a);
    }

    public record Download(String name, String contentType, long size, Resource resource) {}

    @Transactional(readOnly = true)
    public Download getDownload(Long attachmentId) {
        Attachment a = extraDao.selectAttachmentById(attachmentId);
        if (a == null) throw ApiException.notFound("파일");
        return new Download(a.getOriginalName(), a.getContentType(), a.getFileSize(), storage.load(a.getStoredName()));
    }

    public void deleteAttachment(Long attachmentId, UserPrincipal me) {
        Attachment a = extraDao.selectAttachmentById(attachmentId);
        if (a == null) throw ApiException.notFound("파일");
        if (!me.isAdmin() && !a.getUploader().getId().equals(me.id())) throw ApiException.forbidden();
        storage.delete(a.getStoredName());
        extraDao.deleteAttachment(a.getId());
        insertSystemLog(a.getItem(), getUser(me.id()), "파일 삭제: " + a.getOriginalName());
    }

    // ---------- 검색/대시보드 ----------
    @Transactional(readOnly = true)
    public List<MyItemDto> getSearch(String q) {
        if (q == null || q.trim().length() < 1) return List.of();
        Set<Long> ws = guard.getVisibleBoardIds();
        List<MyItemDto> out = new ArrayList<>();
        Matcher km = Pattern.compile("^([A-Za-z][A-Za-z0-9]{1,9})-(\\d{1,8})$").matcher(q.trim());
        if (km.matches()) {
            String prefix = km.group(1).toUpperCase(Locale.ROOT);
            int num = Integer.parseInt(km.group(2));
            for (Board b : boardDao.selectAll())
                if (prefix.equals(b.getKeyPrefix()) && AccessGuard.isVisible(b, ws)) {
                    Item hit = itemDao.selectByBoardAndKey(b.getId(), num);
                    if (hit != null) out.add(MyItemDto.of(hit));
                }
        }
        for (Item i : itemDao.selectByNameLike("%" + q.trim().toLowerCase() + "%"))
            if (AccessGuard.isVisible(i.getBoard(), ws) && out.stream().noneMatch(x -> x.id().equals(i.getId()))) out.add(MyItemDto.of(i));
        return out;
    }

    @Transactional(readOnly = true)
    public MyDashboard getMyDashboard(UserPrincipal me) {
        LocalDate today = LocalDate.now();
        LocalDate weekEnd = today.plusDays(7);
        Set<Long> ws = guard.getVisibleBoardIds();
        List<Item> mine = itemDao.selectByAssignee(me.id()).stream().filter(i -> AccessGuard.isVisible(i.getBoard(), ws)).toList();
        List<Item> open = mine.stream().filter(i -> i.getStatus() == null || !i.getStatus().isDone()).toList();
        long done = mine.size() - open.size();
        List<MyItemDto> overdue = open.stream().filter(i -> i.getDueDate() != null && i.getDueDate().isBefore(today))
                .map(MyItemDto::of).toList();
        List<MyItemDto> dueSoon = open.stream().filter(i -> i.getDueDate() != null && !i.getDueDate().isBefore(today)
                && !i.getDueDate().isAfter(weekEnd)).map(MyItemDto::of).toList();
        List<MyItemDto> openDtos = open.stream()
                .sorted(Comparator.comparing(Item::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(MyItemDto::of).toList();
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        LocalDate lastMonday = monday.minusDays(7);
        long[] byDay = new long[7];
        long lastWeek = 0;
        for (Item i : mine) {
            if (i.getStatus() == null || !i.getStatus().isDone() || i.getResolvedAt() == null) continue;
            LocalDate d = i.getResolvedAt().toLocalDate();
            if (!d.isBefore(monday) && d.isBefore(monday.plusDays(7))) byDay[(int) (d.toEpochDay() - monday.toEpochDay())]++;
            else if (!d.isBefore(lastMonday) && d.isBefore(monday)) lastWeek++;
        }
        List<Long> doneByDay = java.util.Arrays.stream(byDay).boxed().toList();
        long thisWeek = doneByDay.stream().mapToLong(Long::longValue).sum();
        return new MyDashboard(open.size(), overdue.size(), dueSoon.size(), done, overdue, dueSoon, openDtos, boardService.getBoardList(),
                thisWeek, lastWeek, doneByDay);
    }

    // ---------- 내부 유틸 ----------
    /** 만든 사람이 생성 후 이 시간 안에 채우는 값은 '업무를 만들었습니다'에 포함된 것으로 보고 기록하지 않음 */
    private static final long INITIAL_SETUP_MINUTES = 10;
    /** 같은 사람이 이 시간 안에 연달아 바꾼 값은 한 줄로 묶음 */
    private static final long MERGE_MINUTES = 5;
    private static final String SEP = " · ";

    /**
     * 값 변경 활동 기록.
     * - 업무를 만든 사람이 생성 10분 안에 바꾼 값은 기록하지 않음 (처음 여러 칸을 채우는 단계)
     * - 같은 사람이 5분 안에 연달아 바꾸면 직전 기록 한 줄에 합침. 같은 항목을 또 바꾸면 처음 값 → 마지막 값으로,
     *   결국 원래 값으로 돌아왔으면 그 항목은 빼고, 남은 게 없으면 줄 자체를 지움
     */
    private void insertChangeLog(Item i, AppUser actor, List<String> changes) {
        if (changes.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        if (i.getCreatedBy() != null && i.getCreatedBy().getId().equals(actor.getId())
                && i.getCreatedAt() != null && i.getCreatedAt().isAfter(now.minusMinutes(INITIAL_SETUP_MINUTES))) return;
        ItemUpdate last = extraDao.selectLastSystemUpdate(i.getId());
        if (last != null && last.getAuthor() != null && last.getAuthor().getId().equals(actor.getId())) {
            LocalDateTime at = last.getUpdatedAt() != null ? last.getUpdatedAt() : last.getCreatedAt();
            LinkedHashMap<String, String[]> merged = parseChanges(last.getContent());
            if (at != null && at.isAfter(now.minusMinutes(MERGE_MINUTES)) && merged != null) {
                for (String c : changes) {
                    String[] f = splitChange(c);
                    if (f == null) { merged = null; break; }
                    String[] prev = merged.get(f[0]);
                    if (prev == null) merged.put(f[0], new String[]{f[1], f[2]});
                    else if (prev[0].equals(f[2])) merged.remove(f[0]);      // 원래 값으로 되돌림
                    else prev[1] = f[2];
                }
                if (merged != null) {
                    if (merged.isEmpty()) { extraDao.deleteUpdate(last.getId()); return; }
                    StringBuilder sb = new StringBuilder();
                    for (var e : merged.entrySet()) {
                        if (sb.length() > 0) sb.append(SEP);
                        sb.append(e.getKey()).append(": ").append(e.getValue()[0]).append(" → ").append(e.getValue()[1]);
                    }
                    last.setContent(sb.toString());
                    last.setUpdatedAt(now);
                    extraDao.updateUpdateContent(last);
                    return;
                }
            }
        }
        insertSystemLog(i, actor, String.join(SEP, changes));
    }

    /** "항목: 이전 → 이후" → [항목, 이전, 이후] (형식이 아니면 null) */
    private static String[] splitChange(String s) {
        int c = s.indexOf(": "), a = s.indexOf(" → ", c + 2);
        if (c <= 0 || a < 0) return null;
        return new String[]{s.substring(0, c), s.substring(c + 2, a), s.substring(a + 3)};
    }

    /** 값 변경 기록 한 줄 → 항목별 [이전, 이후]. 값 변경 기록이 아니면(파일 첨부 등) null */
    private static LinkedHashMap<String, String[]> parseChanges(String content) {
        if (content == null || content.isBlank()) return null;
        LinkedHashMap<String, String[]> m = new LinkedHashMap<>();
        for (String part : content.split(java.util.regex.Pattern.quote(SEP))) {
            String[] f = splitChange(part);
            if (f == null) return null;
            m.put(f[0], new String[]{f[1], f[2]});
        }
        return m;
    }

    private void insertSystemLog(Item i, AppUser actor, String text) {
        ItemUpdate log = new ItemUpdate();
        log.setItem(i);
        log.setAuthor(actor);
        log.setContent(text);
        log.setSystem(true);
        extraDao.insertUpdate(log);
    }

    private static Long id(BoardStatus s) { return s == null ? null : s.getId(); }
    private static String label(BoardStatus s) { return s == null ? "없음" : s.getLabel(); }

    private static String priorityLabel(Priority p) {
        if (p == null) return "없음";
        return switch (p) { case LOW -> "낮음"; case MEDIUM -> "보통"; case HIGH -> "높음"; case CRITICAL -> "긴급"; };
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static Long toLong(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (NumberFormatException e) { throw ApiException.bad("숫자 형식이 아닙니다."); }
    }

    private static LocalDate toDate(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try { return LocalDate.parse(o.toString()); } catch (DateTimeParseException e) { throw ApiException.bad("날짜 형식(yyyy-MM-dd)이 아닙니다."); }
    }
}
