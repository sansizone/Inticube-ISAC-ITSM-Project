package com.inticube.teamboard.web;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.service.DutyService;
import com.inticube.teamboard.web.dto.Dtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DutyController {
    private final DutyService dutyService;
    private final com.inticube.teamboard.service.AccessGuard guard;

    @GetMapping("/boards/{boardId}/duties")
    public List<DutyDto> list(@PathVariable Long boardId) { guard.getBoardChecked(boardId); return dutyService.getDutyList(boardId); }

    @PostMapping("/boards/{boardId}/duties")
    public DutyDto create(@PathVariable Long boardId, @Valid @RequestBody DutyRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getBoardChecked(boardId);
        return dutyService.insertDuty(boardId, req, me);
    }

    @PutMapping("/duties/{id}")
    public DutyDto update(@PathVariable Long id, @Valid @RequestBody DutyRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getDutyChecked(id);
        return dutyService.updateDuty(id, req, me);
    }

    @PostMapping("/duties/{id}/move")
    public void move(@PathVariable Long id, @RequestBody DutyMoveRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getDutyChecked(id);
        dutyService.updateDutyMove(id, req.parentId(), req.position(), me);
    }

    @DeleteMapping("/duties/{id}")
    public Map<String, Integer> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) {
        guard.getDutyChecked(id);
        return Map.of("deleted", dutyService.deleteDuty(id, me));
    }
}
