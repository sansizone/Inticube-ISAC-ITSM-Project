package com.inticube.teamboard.web;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.service.AccessGuard;
import com.inticube.teamboard.service.BoardService;
import com.inticube.teamboard.service.ItemService;
import com.inticube.teamboard.service.RelationService;
import com.inticube.teamboard.web.dto.Dtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 워크스페이스(보드) 멤버·보기 탭·컬럼 설정·연결 컬럼·업무 이동 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WorkspaceController {
    private final BoardService boardService;
    private final ItemService itemService;
    private final RelationService relations;
    private final AccessGuard guard;
    private final com.inticube.teamboard.service.ColumnConvertService convert;
    private final com.inticube.teamboard.service.MaintenanceService maint;
    private final com.inticube.teamboard.service.UserService userService;

    public record ConvertRequest(Long refBoardId, Long refColumnId, boolean createMissing) {}

    // ---------- 멤버 ----------
    @GetMapping("/boards/{id}/members")
    public List<BoardMemberDto> members(@PathVariable Long id) { guard.getBoardChecked(id); return boardService.getMembers(id); }

    /** 즐겨찾기 추가/해제 (본인만) */
    @PutMapping("/boards/{id}/favorite")
    public Map<String, Boolean> setFavorite(@PathVariable Long id, @RequestBody Map<String, Boolean> body, @AuthenticationPrincipal UserPrincipal me) {
        guard.getBoardChecked(id);
        return Map.of("favorite", boardService.setFavorite(id, me.id(), Boolean.TRUE.equals(body.get("favorite"))));
    }

    /** 사이드바 워크스페이스 정렬 순서 저장 (본인만). body: { ids: [...] } */
    @PutMapping("/boards/order")
    public Map<String, Boolean> setBoardOrder(@RequestBody Map<String, List<Long>> body, @AuthenticationPrincipal UserPrincipal me) {
        List<Long> ids = body.get("ids");
        if (ids == null) throw ApiException.bad("ids 가 필요합니다");
        boardService.setBoardOrder(me.id(), ids, guard.getVisibleBoardIds());
        return Map.of("ok", true);
    }

    @PutMapping("/boards/{id}/members")
    public List<BoardMemberDto> setMembers(@PathVariable Long id, @RequestBody BoardMembersRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getBoardChecked(id);
        return boardService.setMembers(id, req, me);
    }

    // ---------- 보기 탭 ----------
    @PostMapping("/boards/{id}/views")
    public ViewDto addView(@PathVariable Long id, @Valid @RequestBody ViewRequest req) { guard.getBoardChecked(id); return boardService.insertView(id, req); }

    @PostMapping("/boards/{id}/views/reorder")
    public void reorderViews(@PathVariable Long id, @RequestBody ReorderRequest req) { guard.getBoardChecked(id); boardService.setViewOrder(id, req.ids()); }

    @PutMapping("/views/{id}")
    public ViewDto updateView(@PathVariable Long id, @Valid @RequestBody ViewRequest req) { guard.getViewChecked(id); return boardService.updateView(id, req); }

    @DeleteMapping("/views/{id}")
    public void deleteView(@PathVariable Long id) { guard.getViewChecked(id); boardService.deleteView(id); }

    // ---------- 기본 컬럼 설정 ----------
    @PutMapping("/boards/{id}/column-config")
    public void columnConfig(@PathVariable Long id, @Valid @RequestBody ColumnConfigRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getBoardChecked(id);
        boardService.setColumnConfig(id, req.config(), me);
    }

    // ---------- 자동화 규칙 ----------
    @PutMapping("/boards/{id}/automations")
    public void automations(@PathVariable Long id, @RequestBody AutomationRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getBoardChecked(id);
        boardService.setAutomations(id, req.rules(), me);
    }

    // ---------- 연결 컬럼 ----------
    @GetMapping("/columns/{id}/options")
    public List<PickOption> options(@PathVariable Long id, @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "30") int limit) {
        guard.getColumnChecked(id);
        return relations.getOptions(id, q, Math.max(1, Math.min(limit, 200)));
    }

    /** 사람 컬럼 후보: 컬럼 설정의 후보 워크스페이스 항목 (예: 고객사직원정보의 직원 이름) */
    @GetMapping("/columns/{id}/people-options")
    public List<PickOption> peopleOptions(@PathVariable Long id, @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "50") int limit) {
        guard.getColumnChecked(id);
        return relations.getPeopleOptions(id, q, Math.max(1, Math.min(limit, 500)));
    }

    /** 고르기 창에서 찾는 이름이 없을 때 대상 워크스페이스에 새 항목으로 추가 (예: 고객사직원정보에 새 직원) */
    @PostMapping("/columns/{id}/options")
    public PickOption addOption(@PathVariable Long id, @RequestBody Map<String, String> body, @AuthenticationPrincipal UserPrincipal me) {
        guard.getColumnChecked(id);
        return itemService.insertRelationTarget(id, body.get("name"), me);
    }

    /** 텍스트·드롭다운 컬럼 → 연결 컬럼 변환 미리보기 / 실행 */
    @GetMapping("/columns/{id}/convert-preview")
    public com.inticube.teamboard.service.ColumnConvertService.Preview convertPreview(@PathVariable Long id, @RequestParam Long refBoardId,
                                                                                   @RequestParam(required = false) Long refColumnId) {
        guard.getColumnChecked(id);
        return convert.getPreview(id, refBoardId, refColumnId);
    }

    @PostMapping("/columns/{id}/convert")
    public com.inticube.teamboard.service.ColumnConvertService.Result convert(@PathVariable Long id, @RequestBody ConvertRequest req,
                                                                           @AuthenticationPrincipal UserPrincipal me) {
        guard.getColumnChecked(id);
        return convert.updateConvert(id, req.refBoardId(), req.refColumnId(), req.createMissing(), me);
    }

    @GetMapping("/items/{id}/backlinks")
    public List<Backlink> backlinks(@PathVariable Long id) {
        guard.getItemChecked(id);
        return relations.getBacklinks(id, itemService.getItem(id).getBoard().getId());
    }

    // ---------- v3.0: 키 설정·유형·버전 ----------
    @PutMapping("/boards/{id}/key-settings")
    public void keySettings(@PathVariable Long id, @Valid @RequestBody KeySettingsRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getBoardChecked(id); boardService.setKeySettings(id, req, me);
    }

    @PutMapping("/boards/{id}/types")
    public List<TypeDto> types(@PathVariable Long id, @RequestBody List<@Valid TypeRequest> req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getBoardChecked(id); return boardService.setTypes(id, req, me);
    }

    @PostMapping("/boards/{id}/versions")
    public VersionDto addVersion(@PathVariable Long id, @Valid @RequestBody VersionRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getBoardChecked(id); return boardService.insertVersion(id, req, me);
    }

    @PostMapping("/boards/{id}/versions/reorder")
    public void reorderVersions(@PathVariable Long id, @RequestBody ReorderRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getBoardChecked(id); boardService.setVersionOrder(id, req.ids(), me);
    }

    @PutMapping("/versions/{id}")
    public VersionDto updateVersion(@PathVariable Long id, @Valid @RequestBody VersionRequest req, @AuthenticationPrincipal UserPrincipal me) {
        return boardService.updateVersion(id, req, me);
    }

    @DeleteMapping("/versions/{id}")
    public void deleteVersion(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { boardService.deleteVersion(id, me); }

    // ---------- v3.0: 관계·감시자·시간 기록 ----------
    @GetMapping("/items/{id}/links")
    public List<LinkDto> links(@PathVariable Long id) { guard.getItemChecked(id); return maint.getLinks(id); }

    @PostMapping("/items/{id}/links")
    public LinkDto addLink(@PathVariable Long id, @RequestBody LinkRequest req, @AuthenticationPrincipal UserPrincipal me) { guard.getItemChecked(id); return maint.insertLink(id, req, me); }

    @DeleteMapping("/links/{id}")
    public void deleteLink(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { maint.deleteLink(id, me); }

    @GetMapping("/me/watching")
    public List<Long> myWatching(@AuthenticationPrincipal UserPrincipal me) { return maint.getMyWatching(me.id()); }

    @PostMapping("/items/{id}/watch")
    public List<WatcherDto> watch(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) {
        guard.getItemChecked(id); maint.insertWatch(id, userService.getUser(me.id())); return maint.getWatchers(id);
    }

    @DeleteMapping("/items/{id}/watch")
    public List<WatcherDto> unwatch(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) {
        guard.getItemChecked(id); maint.deleteWatch(id, me.id()); return maint.getWatchers(id);
    }

    @PostMapping("/items/{id}/time")
    public TimeEntryDto addTime(@PathVariable Long id, @Valid @RequestBody TimeEntryRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getItemChecked(id); return maint.insertTime(id, req, me);
    }

    @DeleteMapping("/time/{id}")
    public void deleteTime(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { maint.deleteTime(id, me); }

    // ---------- v3.0: 위키 ----------
    @GetMapping("/boards/{id}/wiki")
    public List<WikiSummary> wikiList(@PathVariable Long id) { guard.getBoardChecked(id); return maint.getWikiList(id); }

    @PostMapping("/boards/{id}/wiki")
    public WikiDto wikiCreate(@PathVariable Long id, @Valid @RequestBody WikiRequest req, @AuthenticationPrincipal UserPrincipal me) { guard.getBoardChecked(id); return maint.insertWiki(id, req, me); }

    @PostMapping("/boards/{id}/wiki/reorder")
    public void wikiReorder(@PathVariable Long id, @RequestBody ReorderRequest req) { guard.getBoardChecked(id); maint.setWikiOrder(id, req.ids()); }

    @GetMapping("/wiki/{id}")
    public WikiDto wikiGet(@PathVariable Long id) { return maint.getWiki(id); }

    @PutMapping("/wiki/{id}")
    public WikiDto wikiUpdate(@PathVariable Long id, @Valid @RequestBody WikiRequest req, @AuthenticationPrincipal UserPrincipal me) { return maint.updateWiki(id, req, me); }

    @DeleteMapping("/wiki/{id}")
    public void wikiDelete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { maint.deleteWiki(id, me); }

    // ---------- 다른 워크스페이스로 이동 ----------
    @PostMapping("/items/{id}/move")
    public ItemDto move(@PathVariable Long id, @RequestBody MoveRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getItemChecked(id);
        return itemService.updateItemMove(id, req, me);
    }
}
