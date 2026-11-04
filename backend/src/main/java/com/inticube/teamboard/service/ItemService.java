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
    private final AuditService audit;
    private final SrPolicyService srPolicy;

    public Item getItem(Long id) {
        Item i = itemDao.selectById(id);
        if (i == null) throw ApiException.notFound("아이템");
        if (i.getDeletedAt() != null) throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "삭제되어 휴지통에 있는 업무입니다.");
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
        audit.record("ITEM_CREATE", g.getBoard().getId(), g.getBoard().getName(), "ITEM", i.getId(), i.getName(),
                "그룹: " + g.getName() + (i.getParent() != null ? " (하위 업무, 상위 #" + i.getParent().getId() + ")" : ""));
        return BoardService.toDto(i, 0, 0);
    }

    /**
     * 부분 수정. body 에 키가 있으면 변경(값이 null 이면 비움), 없으면 유지.
     * 허용 키: name, description, statusId, assigneeId, dutyId, priority, startDate, dueDate, groupId, typeId, versionId,
     * estimateHours, progress, recurRule, values
     */
    public ItemDto updateItem(Long id, Map<String, Object> body, UserPrincipal me) {
        itemDao.lockById(id);   // 같은 업무를 동시에 고치면 앞 요청이 끝난 뒤 최신 값으로 다시 읽어 처리
        Item i = getItem(id);
        body = new LinkedHashMap<>(body);
        if (body.remove("base") instanceof Map<?, ?> base) checkBase(i, body, base);
        AppUser actorFull = getUser(me.id());
        String boardName = i.getBoard().getName();
        SrPolicyService.Policy policy = srPolicy.get(i.getBoard().getId());
        srPolicy.checkUpdate(i, body, policy);
        BoardStatus statusBefore = i.getStatus();
        List<String> logs = new ArrayList<>();
        List<Runnable> after = new ArrayList<>(); // 본 업무 저장 뒤 실행할 후속 작업
        BoardStatus[] statusTo = { null };       // 이번 수정으로 바뀐 상태 (자동화 규칙 실행용)

        if (body.containsKey("name")) {
            String name = str(body.get("name"));
            if (name == null || name.isBlank() || name.length() > 300) throw ApiException.bad("이름은 1~300자여야 합니다.");
            String before = i.getName();
            if (!Objects.equals(before, name.trim())) logs.add("이름: " + before + " → " + name.trim());
            i.setName(name.trim());
        }
        if (body.containsKey("description")) {
            String d = str(body.get("description"));
            if (d != null && d.length() > 50000) throw ApiException.bad("설명은 50,000자 이하여야 합니다.");
            String next = d == null || d.isBlank() ? "" : d;
            String before = i.getDescription() == null ? "" : i.getDescription();
            if (!before.equals(next)) logs.add("설명: " + before.length() + "자 → " + next.length() + "자 (내용 수정)");
            i.setDescription(next);
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
            if (next != null && next.equals(recurBase(i.getRecurRule()))) next = i.getRecurRule();  // 같은 규칙이면 기준일 유지
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
        if (policy != null) {
            srPolicy.afterUpdate(i, policy, statusBefore, body, actorFull, logs);
            // 자동 컬럼(처리완료일·등록 지연·장애등급 등)은 서버가 바꿨으므로 응답에 최신 값을 담는다
            Set<Long> autoIds = new HashSet<>(policy.autoCols());
            if (policy.receivedAtCol() != null) autoIds.add(policy.receivedAtCol());
            if (changedValues == null) changedValues = new LinkedHashMap<>();
            Map<Long, String> cv = changedValues;
            Map<Long, String> now = new HashMap<>();
            if (!autoIds.isEmpty()) itemDao.selectValuesByItemAndColumns(i.getId(), autoIds).forEach(v -> now.put(v.getColumnId(), v.getValue()));
            for (Long c : autoIds) cv.put(c, now.getOrDefault(c, ""));
        }
        // 활동 기록: 모든 변경을 그대로 기록
        List<String> changes = new ArrayList<>();
        for (String log : logs) { if (log.startsWith("반복 업무")) insertSystemLog(i, actorFull, log); else changes.add(log); }
        insertChangeLog(i, actorFull, changes);
        if (statusTo[0] != null && i.getParent() == null) runAutomations(i, statusTo[0], actorFull);
        if (changedValues != null) maskPhones(i.getBoard().getId(), changedValues);
        return BoardService.toDto(i, 0, 0, changedValues);
    }

    // ---------- 개인정보 (전화번호) ----------
    /** 응답에 담을 값 중 전화번호 컬럼을 마스킹 */
    private void maskPhones(Long boardId, Map<Long, String> vals) {
        if (vals == null || vals.isEmpty()) return;
        for (BoardColumn c : partsDao.selectColumnsByBoard(boardId))
            if ("PHONE".equals(c.getType()) && vals.get(c.getId()) != null) vals.put(c.getId(), PhoneFormat.mask(vals.get(c.getId())));
    }

    /**
     * 전화번호 수정 통제: 마스킹된 값은 저장 불가, 이미 값이 있는 번호를 바꾸거나 지우는 것은 관리자만.
     * (빈 칸에 새로 입력하는 것은 누구나)
     */
    private void checkPhoneEdit(Item i, BoardColumn c, String next) {
        if (PhoneFormat.isMasked(next)) throw ApiException.bad("'" + c.getName() + "' 은(는) 가려진 값으로 저장할 수 없습니다. 원문을 열람한 뒤 수정하세요.");
        if (guard.getMe().isAdmin()) return;
        boolean has = itemDao.selectValuesByItemAndColumns(i.getId(), Set.of(c.getId())).stream().anyMatch(v -> v.getValue() != null && !v.getValue().isBlank());
        if (has) throw ApiException.forbidden();
    }

    /** 전화번호 원문 열람: 시스템 관리자만, 사유 필수. 업무 활동 기록(삭제 불가)과 서버 로그에 남긴다 */
    public String getPhoneRevealed(Long itemId, Long columnId, String reason, UserPrincipal me) {
        if (!me.isAdmin()) throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "전화번호 원문은 관리자만 볼 수 있습니다.");
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 2 || why.length() > 200) throw ApiException.bad("열람 사유를 2~200자로 입력하세요.");
        Item i = getItem(itemId);
        BoardColumn c = partsDao.selectColumnById(columnId);
        if (c == null || !c.getBoard().getId().equals(i.getBoard().getId()) || !"PHONE".equals(c.getType())) throw ApiException.bad("전화번호 컬럼이 아닙니다.");
        String raw = itemDao.selectValuesByItemAndColumns(itemId, Set.of(columnId)).stream().map(ItemValue::getValue).findFirst().orElse("");
        insertSystemLog(i, getUser(me.id()), "개인정보 열람: " + c.getName() + " (사유: " + why + ")");
        return raw;
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

    /** 반복 규칙의 단위·간격 부분 ("MONTHLY:1:31" → "MONTHLY:1"). 세 번째 값은 월말 보정용 기준일 */
    static String recurBase(String rule) {
        if (rule == null) return null;
        String[] p = rule.split(":");
        return p.length >= 2 ? p[0] + ":" + p[1] : rule;
    }

    /**
     * 다음 회차 마감일. 월·년 단위는 처음 기준일(anchor)을 기억해 월말이 밀리지 않게 한다
     * (1/31 → 2/28 → 3/31 → 4/30). 계산한 날짜가 이미 지났으면 오늘 이후가 될 때까지 같은 간격으로 넘긴다.
     */
    static LocalDate nextRecurDue(String unit, int n, LocalDate base, int anchor, LocalDate today) {
        LocalDate d = base;
        int guard = 0;
        do {
            d = step(unit, n, d, anchor);
        } while (d.isBefore(today) && ++guard < 10000);
        return d;
    }
    private static LocalDate step(String unit, int n, LocalDate d, int anchor) {
        return switch (unit) {
            case "DAILY" -> d.plusDays(n);
            case "WEEKLY" -> d.plusWeeks(n);
            case "MONTHLY" -> { LocalDate m = d.plusMonths(n); yield m.withDayOfMonth(Math.min(anchor, m.lengthOfMonth())); }
            default -> { LocalDate y = d.plusYears(n); yield y.withDayOfMonth(Math.min(anchor, y.lengthOfMonth())); }
        };
    }

    /** 반복 업무: 완료 처리되면 다음 회차를 같은 그룹에 만든다 (유형·담당자·담당업무·우선순위·예상시간·컬럼 값·반복 규칙 복사) */
    private void spawnNext(Item done, AppUser actor, List<String> logs) {
        String[] p = done.getRecurRule().split(":");
        String unit = p[0];
        int n = p.length > 1 ? Integer.parseInt(p[1]) : 1;
        LocalDate today = LocalDate.now();
        LocalDate base = done.getDueDate() != null ? done.getDueDate() : today;
        int anchor = p.length > 2 ? Integer.parseInt(p[2]) : base.getDayOfMonth();
        LocalDate nextDue = nextRecurDue(unit, n, base, anchor, today);
        String nextRule = unit.equals("MONTHLY") || unit.equals("YEARLY") ? unit + ":" + n + ":" + anchor : unit + ":" + n;
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
        next.setRecurRule(nextRule);
        next.setVersion(done.getVersion());
        if (done.getStartDate() != null && done.getDueDate() != null) next.setStartDate(nextDue.minusDays(java.time.temporal.ChronoUnit.DAYS.between(done.getStartDate(), done.getDueDate())));
        next.setDueDate(nextDue);
        next.setCreatedBy(actor);
        next.setSortOrder(itemDao.selectMaxSort(done.getGroup().getId()) + 1);
        next.setStatus(getInitialStatus(done.getBoard().getId()));
        next.setKeyNum(boardService.getNextKey(done.getBoard()));
        itemDao.insert(next);
        List<ItemValue> copies = new ArrayList<>();
        Set<Long> skip = srPolicy.autoCols(done.getBoard().getId());  // 처리완료일 등 자동 값은 새 회차에 복사하지 않음
        for (ItemValue v : itemDao.selectValuesByItem(done.getId())) {
            if (skip.contains(v.getColumnId())) continue;
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
            if ("PHONE".equals(c.getType())) checkPhoneEdit(i, c, v);
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
            else if ("PHONE".equals(c.getType())) logs.add(c.getName() + ": " + shortLog(PhoneFormat.mask(before)) + " → " + shortLog(PhoneFormat.mask(next)));
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

    /** 삭제 = 휴지통으로 이동 (삭제자·사유·시각 기록, 관리자가 복원 가능) */
    public void deleteItem(Long id, String reason) {
        Item i = getItem(id);
        guard.setDeleteItemChecked(i);
        boardService.softDeleteItems(List.of(i), requireReason(reason));
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.bad("삭제 사유를 입력하세요.");
        if (reason.length() > 500) throw ApiException.bad("삭제 사유는 500자 이하여야 합니다.");
        return reason.trim();
    }

    // ---------- 휴지통 (시스템 관리자) ----------
    public record TrashRow(Long id, String key, String name, Long boardId, String boardName, String groupName, boolean sub,
                           int childCount, LocalDateTime deletedAt, String deletedByName, String deleteReason, String createdByName) {}
    public record TrashPage(List<TrashRow> content, long total, int page, int size) {}

    @Transactional(readOnly = true)
    public TrashPage getTrash(Long boardId, String q, int page, int size) {
        int sz = Math.max(10, Math.min(size, 200)), pg = Math.max(0, page);
        String qq = q == null || q.isBlank() ? null : q.trim();
        long total = itemDao.selectTrashCount(boardId, qq);
        List<TrashRow> rows = itemDao.selectTrash(boardId, qq, pg * sz, sz).stream().map(m -> {
            Object kn = m.get("key_num"), pre = m.get("key_prefix");
            String key = kn == null ? null : (pre == null ? "" : pre + "-") + kn;
            Object at = m.get("deleted_at");
            return new TrashRow(((Number) m.get("id")).longValue(), key, (String) m.get("item_name"),
                    ((Number) m.get("board_id")).longValue(), (String) m.get("board_name"), (String) m.get("group_name"),
                    m.get("parent_id") != null, ((Number) m.get("child_count")).intValue(),
                    at instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) at,
                    (String) m.get("deleted_by_name"), (String) m.get("delete_reason"), (String) m.get("created_by_name"));
        }).toList();
        return new TrashPage(rows, total, pg, sz);
    }

    /** 휴지통에서 복원: 같은 시각에 함께 지워진 하위 업무까지 되살림 */
    public void restoreItem(Long id, UserPrincipal me) {
        Item i = itemDao.selectById(id);
        if (i == null) throw ApiException.notFound("업무");
        if (i.getDeletedAt() == null) throw ApiException.bad("휴지통에 있는 업무가 아닙니다.");
        if (i.getParent() != null) {
            Item p = itemDao.selectById(i.getParent().getId());
            if (p != null && p.getDeletedAt() != null) throw ApiException.bad("상위 업무가 휴지통에 있습니다. 상위 업무를 먼저 복원하세요.");
        }
        Set<Long> ids = new LinkedHashSet<>(List.of(i.getId()));
        List<Long> frontier = List.of(i.getId());
        while (!frontier.isEmpty()) {
            List<Long> next = new ArrayList<>();
            for (Item k : itemDao.selectByParents(frontier))
                if (i.getDeletedAt().equals(k.getDeletedAt()) && ids.add(k.getId())) next.add(k.getId());
            frontier = next;
        }
        LocalDateTime at = i.getDeletedAt();
        itemDao.updateRestore(ids, at);
        insertSystemLog(i, getUser(me.id()), "휴지통에서 복원" + (ids.size() > 1 ? " (하위 업무 " + (ids.size() - 1) + "건 포함)" : "")
                + " · 삭제 사유: " + i.getDeleteReason());
    }

    /** 휴지통에서 영구 삭제 (되돌릴 수 없음) */
    public void purgeItem(Long id) {
        Item i = itemDao.selectById(id);
        if (i == null) throw ApiException.notFound("업무");
        if (i.getDeletedAt() == null) throw ApiException.bad("휴지통에 있는 업무만 영구 삭제할 수 있습니다.");
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
        maskPhones(i.getBoard().getId(), vals);
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
        itemDao.lockById(id);
        Item root = getItem(id);
        if (root.getParent() != null) throw ApiException.bad("하위 업무는 상위 업무와 함께 이동합니다. 상위 업무를 이동하세요.");
        if (req.boardId() == null) throw ApiException.bad("이동할 워크스페이스를 선택하세요.");
        Board to = guard.getBoardChecked(req.boardId());
        if (root.getBoard().getId().equals(to.getId())) throw ApiException.bad("같은 워크스페이스입니다.");
        guard.setRemoveItemChecked(root, "다른 워크스페이스로 옮길");
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
        List<Item> items = itemDao.selectByIds(new ArrayList<>(new LinkedHashSet<>(ids))).stream().filter(x -> x.getDeletedAt() == null).toList();
        Set<Long> sel = items.stream().map(Item::getId).collect(Collectors.toSet());
        return items.stream().filter(i -> i.getParent() == null || !sel.contains(i.getParent().getId()))
                .sorted(Comparator.comparingInt(Item::getSortOrder).thenComparing(Item::getId)).toList();
    }

    public BulkResult deleteItems(List<Long> ids, String reason) {
        String why = requireReason(reason);
        List<Item> roots = getBulkRoots(ids);
        for (Item i : roots) guard.setDeleteItemChecked(i); // 하나라도 권한이 없으면 아무것도 지우지 않음
        boardService.softDeleteItems(roots, why);
        return new BulkResult(roots.size(), ids.size() - roots.size());
    }

    /** 다른 워크스페이스로 이동. 하위 업무만 선택한 경우는 상위 업무와 함께만 이동할 수 있어 건너뜀 */
    public BulkResult updateItemsMove(List<Long> ids, Long boardId, Long groupId, UserPrincipal me) {
        List<Item> roots = getBulkRoots(ids);
        // 하나라도 옮길 권한이 없으면 아무것도 옮기지 않음 (남의 업무를 이동으로 없애지 못하게)
        for (Item it : roots) if (it.getParent() == null) guard.setRemoveItemChecked(it, "다른 워크스페이스로 옮길");
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
        c.setStatus(getInitialStatus(src.getBoard().getId()));  // 복제본은 항상 '접수'(없으면 첫 상태)로 시작
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
        Set<Long> skip = srPolicy.autoCols(src.getBoard().getId());  // 처리완료일·등록 지연·장애등급 등은 복사하지 않음
        for (ItemValue v : itemDao.selectValuesByItem(src.getId())) {
            if (skip.contains(v.getColumnId())) continue;
            ItemValue n = new ItemValue(); n.setItemId(c.getId()); n.setColumnId(v.getColumnId()); n.setValue(v.getValue()); vals.add(n);
        }
        if (!vals.isEmpty()) itemDao.batchInsertValues(vals);
        maint.insertWatch(c.getId(), actor);
        return c;
    }

    /** 새로 만들어지는 업무(복제·가져오기)의 시작 상태: '접수', 없으면 첫 번째 상태 */
    public BoardStatus getInitialStatus(Long boardId) {
        List<BoardStatus> ss = partsDao.selectStatusesByBoard(boardId);
        return ss.stream().filter(x -> "접수".equals(x.getLabel() == null ? null : x.getLabel().trim())).findFirst()
                .orElse(ss.isEmpty() ? null : ss.get(0));
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
        LocalDateTime now = LocalDateTime.now();
        i.setUpdatedAt(now);
        boolean firstResponse = i.getRespondedAt() == null && (i.getCreatedBy() == null || !i.getCreatedBy().getId().equals(author.getId()));
        if (firstResponse) i.setRespondedAt(now);
        itemDao.updateTouch(i.getId(), now, firstResponse ? now : null);  // 전체 행을 덮어쓰지 않음 (동시에 바뀐 상태 등 보존)
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
        if (u.getDeletedAt() != null) throw ApiException.bad("삭제된 댓글은 수정할 수 없습니다.");
        if (!u.getAuthor().getId().equals(me.id())) throw ApiException.forbidden();
        if (u.getContent() != null && u.getContent().equals(content.trim())) return UpdateDto.of(u);
        Set<Long> before = new HashSet<>();
        for (AppUser x : mentions.getMentionedUsers(u.getContent())) before.add(x.getId());
        // 수정 전 내용은 이력 테이블에 보존
        extraDao.insertUpdateRev(u.getId(), u.getContent(), me.id(), LocalDateTime.now());
        u.setContent(content.trim());
        u.setUpdatedAt(LocalDateTime.now());
        extraDao.updateUpdateContent(u);
        Item i = getItem(u.getItem().getId());
        AppUser author = getUser(me.id());
        insertSystemLog(i, author, "댓글 수정: 작성 " + written(u) + " (이전 내용 보존)");
        String where = "[" + i.getBoard().getName() + "] '" + i.getName() + "'";
        String preview = u.getContent().length() > 60 ? u.getContent().substring(0, 60) + "…" : u.getContent();
        for (AppUser target : mentions.getMentionedUsers(u.getContent()))
            if (!before.contains(target.getId()))
                notifier.insertNotify(target, author, NotificationType.MENTION, author.getName() + "님이 " + where + "에서 회원님을 언급했습니다: " + preview, i);
        return UpdateDto.of(u);
    }

    /** 댓글 수정 이력 (작성자 본인·관리자) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getUpdateRevs(Long updateId, UserPrincipal me) {
        ItemUpdate u = extraDao.selectUpdateById(updateId);
        if (u == null) throw ApiException.notFound("댓글");
        if (!me.isAdmin() && (u.getAuthor() == null || !u.getAuthor().getId().equals(me.id()))) throw ApiException.forbidden();
        return extraDao.selectUpdateRevs(updateId);
    }

    /** 논리 삭제: 내용은 DB에 보존하고 화면에는 '삭제된 댓글'로 표시. 답글은 그대로 둠 */
    public void deleteUpdate(Long updateId, UserPrincipal me) {
        ItemUpdate u = extraDao.selectUpdateById(updateId);
        if (u == null) throw ApiException.notFound("업데이트");
        if (u.isSystem()) throw ApiException.bad("활동 기록은 삭제할 수 없습니다.");
        if (u.getDeletedAt() != null) return;
        if (!me.isAdmin() && !u.getAuthor().getId().equals(me.id())) throw ApiException.forbidden();
        extraDao.updateUpdateDeleted(u.getId(), me.id(), LocalDateTime.now());
        String writer = u.getAuthor() != null ? u.getAuthor().getName() : "알 수 없음";
        insertSystemLog(getItem(u.getItem().getId()), getUser(me.id()), "댓글 삭제: 작성자 " + writer + ", 작성 " + written(u) + " (내용 보존)");
    }

    private static String written(ItemUpdate u) {
        return u.getCreatedAt() != null ? u.getCreatedAt().withNano(0).toString().replace('T', ' ') : "-";
    }

    // ---------- 첨부파일 ----------
    public AttachmentDto insertAttachment(Long itemId, MultipartFile file, UserPrincipal me) {
        if (file == null || file.isEmpty()) throw ApiException.bad("빈 파일은 업로드할 수 없습니다.");
        Item i = getItem(itemId);
        AppUser uploader = getUser(me.id());
        String name = FileStorageService.cleanName(file.getOriginalFilename());
        // 증빙 품질: 내용 해시, 확장자·실제 형식 일치, 최소 크기
        String hash; byte[] head;
        try { var ins = Evidence.inspect(file.getInputStream()); hash = ins.hash(); head = ins.head(); }
        catch (java.io.IOException e) { throw ApiException.bad("파일을 읽을 수 없습니다."); }
        String mismatch = Evidence.checkType(name, head);
        if (mismatch != null) throw ApiException.bad(mismatch);
        SrPolicyService.Policy p = srPolicy.get(i.getBoard().getId());
        if (p != null && file.getSize() < Evidence.MIN_SIZE)
            throw ApiException.bad("파일이 너무 작습니다 (" + file.getSize() + "바이트). 내용이 있는 증빙 파일을 첨부하세요.");
        String warning = null;
        List<Map<String, Object>> dup = extraDao.selectAttachmentsByHash(i.getBoard().getId(), hash);
        if (!dup.isEmpty()) {
            Map<String, Object> d = dup.get(0);
            boolean same = Objects.equals(lng(d, "item_id"), i.getId());
            warning = "같은 내용의 파일이 이미 " + (same ? "이 업무에" : "'" + d.get("item_name") + "' 업무에") + " 있습니다 (" + d.get("original_name") + ").";
        }
        String stored = storage.store(file);
        Attachment a = new Attachment();
        a.setItem(i);
        a.setOriginalName(name);
        a.setContentType(file.getContentType());
        a.setFileSize(file.getSize());
        a.setStoredName(stored);
        a.setUploader(uploader);
        a.setFileHash(hash);
        extraDao.insertAttachment(a);
        insertSystemLog(i, uploader, "파일 첨부: " + a.getOriginalName() + (warning != null ? " (중복 내용 경고)" : ""));
        return AttachmentDto.of(a, warning);
    }

    public record Download(String name, String contentType, long size, Resource resource) {}

    @Transactional(readOnly = true)
    public Download getDownload(Long attachmentId, UserPrincipal me) {
        Attachment a = extraDao.selectAttachmentById(attachmentId);
        if (a == null || (a.getDeletedAt() != null && (me == null || !me.isAdmin()))) throw ApiException.notFound("파일");
        return new Download(a.getOriginalName(), a.getContentType(), a.getFileSize(), storage.load(a.getStoredName()));
    }

    /** 논리 삭제: 파일은 디스크에 보존(관리자만 내려받기 가능), 완료 잠금이면 삭제 불가 */
    public void deleteAttachment(Long attachmentId, UserPrincipal me) {
        Attachment a = extraDao.selectAttachmentById(attachmentId);
        if (a == null || a.getDeletedAt() != null) throw ApiException.notFound("파일");
        if (!me.isAdmin() && !a.getUploader().getId().equals(me.id())) throw ApiException.forbidden();
        Item i = getItem(a.getItem().getId());
        srPolicy.checkNotLocked(i, "증빙 파일");
        extraDao.updateAttachmentDeleted(a.getId(), me.id(), LocalDateTime.now());
        insertSystemLog(i, getUser(me.id()), "파일 삭제: " + a.getOriginalName() + " (파일 보존)");
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
    private static final String SEP = " · ";

    /**
     * 값 변경 활동 기록 — 감사 증적으로 쓰기 위해 모든 변경을 그대로 한 줄씩 남긴다.
     * (생성 직후 변경·연속 변경·원래 값으로 되돌림도 생략·합치기·삭제 없이 기록)
     */
    /**
     * 동시 수정 충돌 검사. 화면이 편집을 시작할 때 본 값(base)과 지금 저장된 값이 다르고,
     * 이번에 저장하려는 값과도 다르면 다른 사람이 그 사이에 고친 것 → 덮어쓰지 않고 409.
     * 대상: 이름, 설명, 텍스트·긴 텍스트·숫자·링크 컬럼 (자유 입력이라 덮어쓰면 내용이 사라지는 항목)
     */
    private void checkBase(Item i, Map<String, Object> body, Map<?, ?> base) {
        if (body.containsKey("name") && base.containsKey("name"))
            conflictIf(nz(str(base.get("name"))).trim(), nz(i.getName()).trim(), nz(str(body.get("name"))).trim(), "이름");
        if (body.containsKey("description") && base.containsKey("description"))
            conflictIf(nz(str(base.get("description"))), nz(i.getDescription()), nz(str(body.get("description"))), "설명");
        if (body.get("values") instanceof Map<?, ?> vm && base.get("values") instanceof Map<?, ?> bv && !bv.isEmpty()) {
            Map<Long, BoardColumn> cols = partsDao.selectColumnsByBoard(i.getBoard().getId()).stream()
                    .collect(Collectors.toMap(BoardColumn::getId, Function.identity()));
            Set<Long> ids = new HashSet<>();
            for (Object k : bv.keySet()) { Long c = toLong(k); if (c != null && cols.containsKey(c)) ids.add(c); }
            if (ids.isEmpty()) return;
            Map<Long, String> cur = new HashMap<>();
            itemDao.selectValuesByItemAndColumns(i.getId(), ids).forEach(v -> cur.put(v.getColumnId(), v.getValue()));
            for (Long c : ids) {
                if (!Set.of("TEXT", "LONG_TEXT", "NUMBER", "LINK").contains(cols.get(c).getType())) continue;
                Object nv = vm.containsKey(c) ? vm.get(c) : vm.get(String.valueOf(c));
                if (nv == null && !vm.containsKey(c) && !vm.containsKey(String.valueOf(c))) continue;
                Object b = bv.containsKey(c) ? bv.get(c) : bv.get(String.valueOf(c));
                conflictIf(nz(str(b)).trim(), nz(cur.get(c)).trim(), nz(str(nv)).trim(), cols.get(c).getName());
            }
        }
    }
    private static void conflictIf(String base, String current, String next, String field) {
        if (!base.equals(current) && !next.equals(current))
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "다른 사용자가 방금 '" + field + "'을(를) 수정했습니다. 새로고침해서 바뀐 내용을 확인한 뒤 다시 저장해 주세요.");
    }
    private static String nz(String s) { return s == null ? "" : s; }

    private void insertChangeLog(Item i, AppUser actor, List<String> changes) {
        if (changes.isEmpty()) return;
        insertSystemLog(i, actor, String.join(SEP, changes));
    }

    private void insertSystemLog(Item i, AppUser actor, String text) {
        ItemUpdate log = new ItemUpdate();
        log.setItem(i);
        log.setAuthor(actor);
        log.setContent(text);
        log.setSystem(true);
        extraDao.insertUpdate(log);
        // 감사 로그에도 같은 내용을 남김 (업무를 지워도 이력이 남도록)
        Board b = i.getBoard();
        audit.record(auditAction(text), b == null ? null : b.getId(), b == null ? null : b.getName(), "ITEM", i.getId(), i.getName(), text);
    }

    /** 활동 기록 문구 → 감사 로그 종류 */
    private static String auditAction(String text) {
        if (text.startsWith("개인정보 열람")) return "PHONE_REVEAL";
        if (text.startsWith("댓글 삭제")) return "COMMENT_DELETE";
        if (text.startsWith("휴지통에서 복원")) return "ITEM_RESTORE";
        if (text.startsWith("파일 첨부")) return "FILE_ADD";
        if (text.startsWith("파일 삭제")) return "FILE_DELETE";
        if (text.startsWith("업무 복제") || text.contains("워크스페이스 이동") || text.startsWith("워크스페이스")) return "ITEM_MOVE";
        if (text.startsWith("양식으로 접수") || text.startsWith("반복 업무에서 생성")) return "ITEM_CREATE";
        return "ITEM_UPDATE";
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
