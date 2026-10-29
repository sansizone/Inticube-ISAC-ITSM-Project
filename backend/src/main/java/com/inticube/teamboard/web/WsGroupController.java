package com.inticube.teamboard.web;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.service.AccessGuard;
import com.inticube.teamboard.service.WsGroupService;
import com.inticube.teamboard.service.WsGroupService.*;
import com.inticube.teamboard.web.dto.Dtos.ReorderRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 워크스페이스 그룹 (그룹 > 워크스페이스) 과 그룹별 멤버 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WsGroupController {
    private final WsGroupService groups;
    private final AccessGuard guard;

    @GetMapping("/ws-groups")
    public List<GroupDto> list(@AuthenticationPrincipal UserPrincipal me) { return groups.getGroups(me); }

    @PostMapping("/ws-groups")
    public GroupDto create(@RequestBody GroupRequest req, @AuthenticationPrincipal UserPrincipal me) { return groups.insertGroup(req, me); }

    @PutMapping("/ws-groups/{id}")
    public void update(@PathVariable Long id, @RequestBody GroupRequest req, @AuthenticationPrincipal UserPrincipal me) { groups.updateGroup(id, req, me); }

    @PostMapping("/ws-groups/reorder")
    public void reorder(@RequestBody ReorderRequest req, @AuthenticationPrincipal UserPrincipal me) { groups.setOrder(req.ids(), me); }

    /** 내 그룹 고정 (사이드바에서 처음 보여 줄 그룹) */
    @PutMapping("/ws-groups/{id}/default")
    public void setDefault(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { groups.setDefault(id, me); }

    /** 고정 해제 */
    @DeleteMapping("/ws-groups/{id}/default")
    public void clearDefault(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { groups.setDefault(null, me); }

    @DeleteMapping("/ws-groups/{id}")
    public void delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { groups.deleteGroup(id, me); }

    @GetMapping("/ws-groups/{id}/members")
    public List<MemberDto> members(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { return groups.getMembers(id, me); }

    @PutMapping("/ws-groups/{id}/members")
    public List<MemberDto> setMembers(@PathVariable Long id, @RequestBody List<MemberRequest> req, @AuthenticationPrincipal UserPrincipal me) {
        return groups.setMembers(id, req, me);
    }

    public record BoardGroupRequest(Long groupId) {}

    /** 워크스페이스를 다른 그룹으로 옮기기 */
    @PutMapping("/boards/{id}/ws-group")
    public void moveBoard(@PathVariable Long id, @RequestBody BoardGroupRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getBoardChecked(id);
        groups.setBoardGroup(id, req.groupId(), me);
    }
}
