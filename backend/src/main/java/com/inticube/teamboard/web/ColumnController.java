package com.inticube.teamboard.web;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.service.BoardService;
import com.inticube.teamboard.web.dto.Dtos.ColumnDto;
import com.inticube.teamboard.web.dto.Dtos.ColumnRequest;
import com.inticube.teamboard.web.dto.Dtos.ReorderRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 워크스페이스별 사용자 정의 컬럼 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ColumnController {
    private final BoardService boardService;
    private final com.inticube.teamboard.service.AccessGuard guard;

    @PostMapping("/boards/{boardId}/columns")
    public ColumnDto add(@PathVariable Long boardId, @Valid @RequestBody ColumnRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getBoardChecked(boardId);
        return boardService.insertColumn(boardId, req, me);
    }

    @PostMapping("/boards/{boardId}/columns/reorder")
    public void reorder(@PathVariable Long boardId, @RequestBody ReorderRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getBoardChecked(boardId);
        boardService.setColumnOrder(boardId, req.ids(), me);
    }

    @PutMapping("/columns/{id}")
    public ColumnDto update(@PathVariable Long id, @Valid @RequestBody ColumnRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getColumnChecked(id);
        return boardService.updateColumn(id, req, me);
    }

    @DeleteMapping("/columns/{id}")
    public void delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) {
        guard.getColumnChecked(id);
        boardService.deleteColumn(id, me);
    }
}
