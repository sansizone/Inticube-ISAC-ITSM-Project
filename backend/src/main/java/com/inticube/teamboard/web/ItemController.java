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

    @DeleteMapping("/items/{id}")
    public void delete(@PathVariable Long id) { guard.getItemChecked(id); itemService.deleteItem(id); }

    @PostMapping("/items/{id}/updates")
    public UpdateDto addUpdate(@PathVariable Long id, @Valid @RequestBody UpdateRequest req, @AuthenticationPrincipal UserPrincipal me) {
        guard.getItemChecked(id);
        return itemService.insertUpdate(id, req.content(), req.parentId(), me);
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

    @PostMapping("/notifications/{id}/read")
    public void read(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { notificationService.updateRead(id, me.id()); }

    @PostMapping("/notifications/read-all")
    public void readAll(@AuthenticationPrincipal UserPrincipal me) { notificationService.updateAllRead(me.id()); }
}
