package com.inticube.teamboard.web;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.service.ItemService;
import com.inticube.teamboard.service.NotificationService;
import com.inticube.teamboard.web.dto.Dtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ItemController {
    private final ItemService itemService;
    private final NotificationService notificationService;
    private final com.inticube.teamboard.service.AccessGuard guard;

    @GetMapping("/items/{id}")
    public ItemDetail detail(@PathVariable Long id) { guard.getItemChecked(id); return itemService.getItemDetail(id); }

    @PatchMapping("/items/{id}")
    public ItemDto patch(@PathVariable Long id, @RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal me) {
        guard.getItemChecked(id);
        return itemService.updateItem(id, body, me);
    }

    // ---------- 여러 업무 일괄 처리 (선택한 모든 업무에 권한이 있어야 함) ----------
    private void checkAll(BulkRequest req) {
        if (req.ids() == null || req.ids().isEmpty()) throw ApiException.bad("업무를 선택하세요.");
        req.ids().forEach(guard::getItemChecked);
    }

    @PostMapping("/items/bulk-delete")
    public BulkResult bulkDelete(@RequestBody BulkRequest req) { checkAll(req); return itemService.deleteItems(req.ids()); }

    @PostMapping("/items/bulk-move")
    public BulkResult bulkMove(@RequestBody BulkRequest req, @AuthenticationPrincipal UserPrincipal me) {
        checkAll(req);
        return itemService.updateItemsMove(req.ids(), req.boardId(), req.groupId(), me);
    }

    @PostMapping("/items/bulk-copy")
    public BulkResult bulkCopy(@RequestBody BulkRequest req, @AuthenticationPrincipal UserPrincipal me) {
        checkAll(req);
        return itemService.insertItemsCopy(req.ids(), me);
    }

    @DeleteMapping("/items/{id}")
    public void delete(@PathVariable Long id) { guard.getItemChecked(id); itemService.deleteItem(id); }

    @PostMapping("/items/{id}/updates")
    public UpdateDto addUpdate(@PathVariable Long id, @Valid @RequestBody UpdateRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getItemChecked(id);
        return itemService.insertUpdate(id, req.content(), req.parentId(), me);
    }

    /** 댓글 수정 (본인만) */
    @PutMapping("/updates/{id}")
    public UpdateDto editUpdate(@PathVariable Long id, @Valid @RequestBody UpdateRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getUpdateChecked(id);
        return itemService.updateUpdate(id, req.content(), me);
    }

    @DeleteMapping("/updates/{id}")
    public void deleteUpdate(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { guard.getUpdateChecked(id); itemService.deleteUpdate(id, me); }

    @PostMapping(value = "/items/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentDto upload(@PathVariable Long id, @RequestParam("file") MultipartFile file, @AuthenticationPrincipal UserPrincipal me) {
        guard.getItemChecked(id);
        return itemService.insertAttachment(id, file, me);
    }

    @GetMapping("/attachments/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        guard.getAttachmentChecked(id);
        ItemService.Download d = itemService.getDownload(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(d.size())
                .body(d.resource());
    }

    @DeleteMapping("/attachments/{id}")
    public void deleteAttachment(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { guard.getAttachmentChecked(id); itemService.deleteAttachment(id, me); }

    // ---------- 알림 ----------
    @GetMapping("/notifications")
    public NotificationList notifications(@AuthenticationPrincipal UserPrincipal me) { return notificationService.getList(me.id()); }

    /** 양식 보기로 접수 */
    @PostMapping("/views/{id}/submit")
    public ItemDto submitForm(@PathVariable Long id, @Valid @RequestBody FormSubmitRequest req, @AuthenticationPrincipal UserPrincipal me) {
        return itemService.insertFormItem(id, req, me);
    }

    @GetMapping("/notifications/talk")
    public java.util.List<com.inticube.teamboard.web.dto.Dtos.NotificationDto> notificationTalk(@AuthenticationPrincipal UserPrincipal me) {
        return notificationService.getTalkList(me.id());
    }

    @PostMapping("/notifications/{id}/read")
    public void read(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { notificationService.updateRead(id, me.id()); }

    @PostMapping("/notifications/read-all")
    public void readAll(@AuthenticationPrincipal UserPrincipal me) { notificationService.updateAllRead(me.id()); }
}
