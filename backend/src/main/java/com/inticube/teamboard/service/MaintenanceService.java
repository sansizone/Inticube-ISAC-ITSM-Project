package com.inticube.teamboard.service;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.dao.*;
import com.inticube.teamboard.domain.*;
import com.inticube.teamboard.web.ApiException;
import com.inticube.teamboard.web.dto.Dtos;
import com.inticube.teamboard.web.dto.Dtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** v3.0 유지보수 기능: 업무 관계, 감시자, 시간 기록, 위키, SLA 알림 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MaintenanceService {
    private final ItemDao itemDao;
    private final ItemExtraDao extraDao;
    private final WikiPageDao wikiDao;
    private final AppUserDao userDao;
    private final NotificationDao notiDao;
    private final NotificationService notifier;
    private final AccessGuard guard;
    private final SrPolicyService srPolicy;

    private Item item(Long id) {
        Item i = itemDao.selectById(id);
        if (i == null) throw ApiException.notFound("업무");
        return i;
    }

    // ---------- 관계 ----------
    @Transactional(readOnly = true)
    public List<LinkDto> getLinks(Long itemId) {
        List<ItemLink> list = extraDao.selectLinksByItem(itemId);
        if (list.isEmpty()) return List.of();
        Set<Long> others = new HashSet<>();
        for (ItemLink l : list) others.add(l.getFromItemId().equals(itemId) ? l.getToItemId() : l.getFromItemId());
        Map<Long, Item> byId = new HashMap<>();
        for (Item i : itemDao.selectByIds(others)) byId.put(i.getId(), i);
        Set<Long> visible = guard.getVisibleBoardIds();
        List<LinkDto> out = new ArrayList<>();
        for (ItemLink l : list) {
            boolean outgoing = l.getFromItemId().equals(itemId);
            Item o = byId.get(outgoing ? l.getToItemId() : l.getFromItemId());
            if (o == null || !AccessGuard.isVisible(o.getBoard(), visible)) continue;
            out.add(new LinkDto(l.getId(), l.getType(), outgoing, o.getId(), o.getBoard().getId(), o.getBoard().getName(),
                    Dtos.keyOf(o), o.getName(), StatusDto.of(o.getStatus())));
        }
        out.sort(Comparator.comparing(LinkDto::type).thenComparing(LinkDto::outgoing, Comparator.reverseOrder()).thenComparing(LinkDto::id));
        return out;
    }

    public LinkDto insertLink(Long itemId, LinkRequest req, UserPrincipal me) {
        if (req.toItemId() == null) throw ApiException.bad("연결할 업무를 선택하세요.");
        if (req.toItemId().equals(itemId)) throw ApiException.bad("자기 자신과는 연결할 수 없습니다.");
        String type = req.type() == null ? "RELATES" : req.type();
        if (!ItemLink.TYPES.contains(type)) throw ApiException.bad("알 수 없는 관계 유형: " + type);
        guard.getItemChecked(req.toItemId());
        if (extraDao.selectLinkExists(itemId, req.toItemId(), type)
                || ("RELATES".equals(type) && extraDao.selectLinkExists(req.toItemId(), itemId, type)))
            throw ApiException.bad("이미 같은 관계가 있습니다.");
        ItemLink l = new ItemLink();
        l.setFromItemId(itemId); l.setToItemId(req.toItemId()); l.setType(type); l.setCreatedBy(me.id());
        extraDao.insertLink(l);
        Item a = item(itemId), b = item(req.toItemId());
        AppUser actor = userDao.selectById(me.id());
        insertLog(a, actor, linkLabel(type, true) + ": " + ref(b));
        insertLog(b, actor, linkLabel(type, false) + ": " + ref(a));
        return getLinks(itemId).stream().filter(x -> x.id().equals(l.getId())).findFirst().orElseThrow();
    }

    public void deleteLink(Long linkId, UserPrincipal me) {
        ItemLink l = extraDao.selectLinkById(linkId);
        if (l == null) throw ApiException.notFound("관계");
        guard.getItemChecked(l.getFromItemId());
        Item a = itemDao.selectById(l.getFromItemId()), b = itemDao.selectById(l.getToItemId());
        extraDao.deleteLink(l.getId());
        AppUser actor = userDao.selectById(me.id());
        if (a != null && b != null) { insertLog(a, actor, "관계 해제: " + ref(b)); insertLog(b, actor, "관계 해제: " + ref(a)); }
    }

    public static String linkLabel(String type, boolean outgoing) {
        return switch (type) {
            case "BLOCKS" -> outgoing ? "차단함" : "차단됨";
            case "DUPLICATES" -> outgoing ? "중복함" : "중복됨";
            case "CAUSES" -> outgoing ? "원인임" : "원인";
            default -> "관련";
        };
    }

    private static String ref(Item i) { String k = Dtos.keyOf(i); return (k == null ? "#" + i.getId() : k) + " " + i.getName(); }

    // ---------- 감시자 ----------
    @Transactional(readOnly = true)
    public List<WatcherDto> getWatchers(Long itemId) {
        return extraDao.selectWatchersByItem(itemId).stream()
                .map(w -> new WatcherDto(w.getUser().getId(), w.getUser().getName(), w.getUser().getUsername()))
                .sorted(Comparator.comparing(WatcherDto::name)).toList();
    }

    @Transactional(readOnly = true)
    public List<Long> getMyWatching(Long userId) { return extraDao.selectWatchingItemIds(userId); }

    public boolean getWatching(Long itemId, Long userId) { return extraDao.selectWatcherExists(itemId, userId); }

    public void insertWatch(Long itemId, AppUser u) {
        if (u == null || !u.isActive() || extraDao.selectWatcherExists(itemId, u.getId())) return;
        extraDao.insertWatcher(itemId, u.getId());
    }

    public void deleteWatch(Long itemId, Long userId) {
        extraDao.deleteWatcher(itemId, userId);
    }

    /** 감시자 전원에게 알림 (행위자·이미 알림 받은 사람 제외) */
    public void insertNotifyWatchers(Item i, AppUser actor, Set<Long> already, NotificationType type, String message) {
        for (ItemWatcher w : extraDao.selectWatchersByItem(i.getId())) {
            if (already != null && already.contains(w.getUser().getId())) continue;
            notifier.insertNotify(w.getUser(), actor, type, message, i);
            if (already != null) already.add(w.getUser().getId());
        }
    }

    // ---------- 시간 기록 ----------
    @Transactional(readOnly = true)
    public List<TimeEntryDto> getTimeEntries(Long itemId) {
        return extraDao.selectTimesByItem(itemId).stream().map(TimeEntryDto::of).toList();
    }

    public TimeEntryDto insertTime(Long itemId, TimeEntryRequest req, UserPrincipal me) {
        Item i = item(itemId);
        if (req.hours() == null || req.hours().signum() <= 0 || req.hours().compareTo(new BigDecimal("999")) > 0)
            throw ApiException.bad("시간은 0보다 크고 999 이하여야 합니다.");
        AppUser actor = userDao.selectById(me.id());
        TimeEntry t = new TimeEntry();
        t.setItemId(itemId); t.setBoardId(i.getBoard().getId());
        t.setUser(actor);
        t.setWorkDate(req.workDate() == null ? LocalDate.now() : req.workDate());
        t.setHours(req.hours().setScale(2, java.math.RoundingMode.HALF_UP));
        srPolicy.checkTimeEntry(i, actor.getId(), t.getWorkDate(), t.getHours());
        t.setComment(req.comment() == null || req.comment().isBlank() ? null : req.comment().trim());
        extraDao.insertTime(t);
        i.setUpdatedAt(LocalDateTime.now());
        itemDao.updateTouch(i.getId(), i.getUpdatedAt(), null);  // 전체 행을 덮어쓰지 않음
        insertLog(i, actor, "시간 기록: " + t.getWorkDate() + " " + t.getHours().stripTrailingZeros().toPlainString() + "h" + (t.getComment() == null ? "" : " — " + t.getComment()));
        srPolicy.syncWorkHours(i);
        return TimeEntryDto.of(extraDao.selectTimeById(t.getId()));
    }

    public void deleteTime(Long entryId, UserPrincipal me) {
        TimeEntry t = extraDao.selectTimeById(entryId);
        if (t == null) throw ApiException.notFound("시간 기록");
        guard.getItemChecked(t.getItemId());
        if (!me.isAdmin() && !t.getUser().getId().equals(me.id())) throw new ApiException(HttpStatus.FORBIDDEN, "본인 기록만 삭제할 수 있습니다.");
        if (t.getDeletedAt() != null) return;
        Item i = item(t.getItemId());
        srPolicy.checkNotLocked(i, "시간 기록");
        // 논리 삭제: 기록은 보존, 합계에서만 빠짐
        extraDao.updateTimeDeleted(t.getId(), me.id(), LocalDateTime.now());
        insertLog(i, userDao.selectById(me.id()), "시간 기록 삭제: " + t.getWorkDate() + " " + t.getHours().stripTrailingZeros().toPlainString() + "h ("
                + (t.getUser() == null ? "-" : t.getUser().getName()) + ", 기록 보존)");
        srPolicy.syncWorkHours(i);
    }

    // ---------- 위키 ----------
    @Transactional(readOnly = true)
    public List<WikiSummary> getWikiList(Long boardId) {
        return wikiDao.selectByBoard(boardId).stream().map(WikiSummary::of).toList();
    }

    @Transactional(readOnly = true)
    public WikiDto getWiki(Long pageId) {
        WikiPage p = wikiDao.selectById(pageId);
        if (p == null) throw ApiException.notFound("위키 페이지");
        guard.getBoardChecked(p.getBoardId());
        return new WikiDto(p.getId(), p.getBoardId(), p.getParentId(), p.getTitle(), p.getContent(), p.getSortOrder(),
                UserSummary.of(p.getCreatedBy()), UserSummary.of(p.getUpdatedBy()), p.getCreatedAt(), p.getUpdatedAt());
    }

    public WikiDto insertWiki(Long boardId, WikiRequest req, UserPrincipal me) {
        if (req.title() == null || req.title().isBlank()) throw ApiException.bad("제목을 입력하세요.");
        if (wikiDao.selectCountByBoard(boardId) >= 500) throw ApiException.bad("위키 페이지는 워크스페이스당 500개까지 만들 수 있습니다.");
        WikiPage p = new WikiPage();
        p.setBoardId(boardId);
        if (req.parentId() != null) {
            WikiPage parent = wikiDao.selectById(req.parentId());
            if (parent == null) throw ApiException.notFound("상위 페이지");
            if (!parent.getBoardId().equals(boardId)) throw ApiException.bad("다른 워크스페이스의 페이지입니다.");
            p.setParentId(parent.getId());
        }
        p.setTitle(req.title().trim());
        p.setContent(req.content() == null ? "" : req.content());
        p.setSortOrder(wikiDao.selectMaxSort(boardId) + 1);
        AppUser u = userDao.selectById(me.id());
        p.setCreatedBy(u); p.setUpdatedBy(u);
        wikiDao.insert(p);
        return getWiki(p.getId());
    }

    public WikiDto updateWiki(Long pageId, WikiRequest req, UserPrincipal me) {
        WikiPage p = wikiDao.selectById(pageId);
        if (p == null) throw ApiException.notFound("위키 페이지");
        guard.getBoardChecked(p.getBoardId());
        if (req.title() != null && !req.title().isBlank()) p.setTitle(req.title().trim());
        if (req.content() != null) p.setContent(req.content());
        if (req.parentId() != null && !req.parentId().equals(p.getParentId())) {
            if (req.parentId() <= 0) p.setParentId(null);
            else {
                if (req.parentId().equals(pageId) || isDescendant(req.parentId(), pageId)) throw ApiException.bad("자기 자신이나 하위 페이지 아래로 옮길 수 없습니다.");
                WikiPage parent = wikiDao.selectById(req.parentId());
                if (parent == null) throw ApiException.notFound("상위 페이지");
                if (!parent.getBoardId().equals(p.getBoardId())) throw ApiException.bad("다른 워크스페이스의 페이지입니다.");
                p.setParentId(parent.getId());
            }
        }
        p.setUpdatedBy(userDao.selectById(me.id()));
        p.setUpdatedAt(LocalDateTime.now());
        wikiDao.update(p);
        return getWiki(pageId);
    }

    private boolean isDescendant(Long candidate, Long ancestor) {
        Long cur = candidate;
        for (int guardCount = 0; cur != null && guardCount < 100; guardCount++) {
            WikiPage p = wikiDao.selectById(cur);
            if (p == null) return false;
            if (ancestor.equals(p.getParentId())) return true;
            cur = p.getParentId();
        }
        return false;
    }

    public void deleteWiki(Long pageId, UserPrincipal me) {
        WikiPage p = wikiDao.selectById(pageId);
        if (p == null) throw ApiException.notFound("위키 페이지");
        guard.getBoardChecked(p.getBoardId());
        // 하위 페이지는 한 단계 위로
        for (WikiPage c : wikiDao.selectByParent(pageId)) wikiDao.updateParent(c.getId(), p.getParentId());
        wikiDao.delete(p.getId());
    }

    public void setWikiOrder(Long boardId, List<Long> ids) {
        Set<Long> mine = new HashSet<>();
        for (WikiPage p : wikiDao.selectByBoard(boardId)) mine.add(p.getId());
        int order = 1;
        for (Long id : ids) if (mine.contains(id)) wikiDao.updateSort(id, order++);
    }

    // ---------- SLA 알림: 매 30분, 처리 목표 2시간 전·초과 시 담당자(없으면 등록자)에게 1회 ----------
    /** SLA 는 화면에서 제거됨 — 알림도 기본 꺼짐 (다시 쓰려면 teamboard.sla-enabled: true) */
    @org.springframework.beans.factory.annotation.Value("${teamboard.sla-enabled:false}")
    private boolean slaEnabled;

    @Scheduled(cron = "${teamboard.sla-check-cron:0 */30 * * * *}")
    public void batchSlaCheck() {
        if (!slaEnabled) return;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(20);
        int count = 0;
        for (Item i : itemDao.selectOpenWithSla()) {
            if (i.getStatus() != null && i.getStatus().isDone()) continue;
            if (i.getType() == null || i.getType().getResolveHours() == null) continue;
            LocalDateTime deadline = SlaCalc.deadline(i.getCreatedAt(), i.getType().getResolveHours(), i.getBoard().isSlaBusiness());
            long minutesLeft = java.time.Duration.between(now, deadline).toMinutes();
            if (minutesLeft > 120) continue;
            AppUser to = i.getAssignee() != null ? i.getAssignee() : i.getCreatedBy();
            if (to == null || notiDao.selectExistsRecent(to.getId(), i.getId(), NotificationType.SLA, since)) continue;
            String k = Dtos.keyOf(i);
            String msg = minutesLeft < 0
                    ? "[" + i.getBoard().getName() + "] " + (k == null ? "" : k + " ") + "'" + i.getName() + "' 처리 목표(" + i.getType().getResolveHours() + "h)를 초과했습니다."
                    : "[" + i.getBoard().getName() + "] " + (k == null ? "" : k + " ") + "'" + i.getName() + "' 처리 목표까지 " + Math.max(1, minutesLeft / 60) + "시간 남았습니다.";
            notifier.insertNotify(to, null, NotificationType.SLA, msg, i);
            count++;
        }
        if (count > 0) log.info("SLA 알림 {}건 발송", count);
    }

    // ---------- 공통 ----------
    private void insertLog(Item i, AppUser actor, String text) {
        ItemUpdate u = new ItemUpdate();
        u.setItem(i); u.setAuthor(actor); u.setContent(text); u.setSystem(true);
        extraDao.insertUpdate(u);
    }
}
