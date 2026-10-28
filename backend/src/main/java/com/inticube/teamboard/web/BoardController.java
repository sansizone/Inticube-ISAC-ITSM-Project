package com.inticube.teamboard.web;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.service.BoardService;
import com.inticube.teamboard.service.ItemService;
import com.inticube.teamboard.service.UserService;
import com.inticube.teamboard.web.dto.Dtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BoardController {
    private final BoardService boardService;
    private final ItemService itemService;
    private final UserService userService;
    private final com.inticube.teamboard.service.AccessGuard guard;

    @GetMapping("/users")
    public List<UserSummary> users() { return userService.getActiveUsers(); }

    @GetMapping("/boards")
    public List<BoardSummary> boards() { return boardService.getBoardList(); }

    @PostMapping("/boards")
    public BoardSummary create(@Valid @RequestBody BoardRequest req, @AuthenticationPrincipal UserPrincipal me) {
        return boardService.insertBoard(req, me);
    }

    @GetMapping("/boards/{id}")
    public BoardDetail detail(@PathVariable Long id) { guard.getBoardChecked(id); return boardService.getBoardDetail(id); }

    @PutMapping("/boards/{id}")
    public void update(@PathVariable Long id, @Valid @RequestBody BoardRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getBoardChecked(id);
        boardService.updateBoard(id, req, me);
    }

    @DeleteMapping("/boards/{id}")
    public void delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { guard.getBoardChecked(id); boardService.deleteBoard(id, me); }

    @PutMapping("/boards/{id}/statuses")
    public List<StatusDto> statuses(@PathVariable Long id, @RequestBody List<@Valid StatusRequest> req,
                                    @AuthenticationPrincipal UserPrincipal me) {
        guard.getBoardChecked(id);
        return boardService.setStatuses(id, req, me);
    }

    @PostMapping("/boards/{id}/groups")
    public GroupDto addGroup(@PathVariable Long id, @Valid @RequestBody GroupRequest req) { guard.getBoardChecked(id); return boardService.insertGroup(id, req); }

    @PostMapping("/boards/{id}/groups/reorder")
    public void reorderGroups(@PathVariable Long id, @RequestBody ReorderRequest req) { guard.getBoardChecked(id); boardService.setGroupOrder(id, req.ids()); }

    @PutMapping("/groups/{id}")
    public GroupDto updateGroup(@PathVariable Long id, @Valid @RequestBody GroupRequest req) { guard.getGroupChecked(id); return boardService.updateGroup(id, req); }

    @DeleteMapping("/groups/{id}")
    public void deleteGroup(@PathVariable Long id) { guard.getGroupChecked(id); boardService.deleteGroup(id); }

    @PostMapping("/groups/{id}/items")
    public ItemDto addItem(@PathVariable Long id, @Valid @RequestBody ItemCreateRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getGroupChecked(id);
        return itemService.insertItem(id, req, me);
    }

    @PostMapping("/groups/{id}/items/reorder")
    public void reorderItems(@PathVariable Long id, @RequestBody ReorderRequest req) { guard.getGroupChecked(id); itemService.setItemOrder(id, req.ids()); }

    @GetMapping("/search")
    public List<MyItemDto> search(@RequestParam String q) { return itemService.getSearch(q); }

    @GetMapping("/dashboard/me")
    public MyDashboard dashboard(@AuthenticationPrincipal UserPrincipal me) { return itemService.getMyDashboard(me); }
}
