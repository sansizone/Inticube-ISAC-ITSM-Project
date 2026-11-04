package com.inticube.teamboard.web;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.service.ItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 휴지통 (시스템 관리자 전용: /api/admin/**) — 삭제된 업무 조회·복원·영구 삭제 */
@RestController
@RequestMapping("/api/admin/trash")
@RequiredArgsConstructor
public class TrashController {
    private final ItemService itemService;

    @GetMapping
    public ItemService.TrashPage list(@RequestParam(required = false) Long boardId, @RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return itemService.getTrash(boardId, q, page, size);
    }

    @PostMapping("/{id}/restore")
    public void restore(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { itemService.restoreItem(id, me); }

    @DeleteMapping("/{id}")
    public void purge(@PathVariable Long id) { itemService.purgeItem(id); }
}
