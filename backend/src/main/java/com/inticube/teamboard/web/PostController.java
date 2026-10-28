package com.inticube.teamboard.web;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.service.ItemService;
import com.inticube.teamboard.service.PostService;
import com.inticube.teamboard.web.dto.Dtos.*;
import jakarta.servlet.http.HttpSession;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PostController {
    private static final String VIEWED = "TB_VIEWED_POSTS";
    private final PostService postService;

    @GetMapping("/post-categories")
    public List<CategoryDto> categories() { return postService.getCategories(); }

    @PostMapping("/admin/post-categories")
    public CategoryDto createCategory(@Valid @RequestBody CategoryRequest req) { return postService.insertCategory(req); }

    @PutMapping("/admin/post-categories/{id}")
    public CategoryDto updateCategory(@PathVariable Long id, @Valid @RequestBody CategoryRequest req) { return postService.updateCategory(id, req); }

    @DeleteMapping("/admin/post-categories/{id}")
    public void deleteCategory(@PathVariable Long id) { postService.deleteCategory(id); }

    @PostMapping("/admin/post-categories/reorder")
    public void reorderCategories(@RequestBody ReorderRequest req) { postService.setCategoryOrder(req.ids()); }

    @GetMapping("/posts")
    public PostPage list(@RequestParam(required = false) Long categoryId, @RequestParam(required = false) String q,
                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return postService.getPostList(categoryId, q, page, size);
    }

    @GetMapping("/posts/{id}")
    @SuppressWarnings("unchecked")
    public PostDetail detail(@PathVariable Long id, HttpSession session) {
        Set<Long> viewed = (Set<Long>) session.getAttribute(VIEWED);
        if (viewed == null) { viewed = new HashSet<>(); }
        boolean first = viewed.add(id);
        session.setAttribute(VIEWED, viewed);
        return postService.getPostDetail(id, first);
    }

    @PostMapping("/posts")
    public PostDetail create(@Valid @RequestBody PostRequest req, @AuthenticationPrincipal UserPrincipal me) {
        return postService.insertPost(req, me);
    }

    @PutMapping("/posts/{id}")
    public PostDetail update(@PathVariable Long id, @Valid @RequestBody PostRequest req, @AuthenticationPrincipal UserPrincipal me) {
        return postService.updatePost(id, req, me);
    }

    @DeleteMapping("/posts/{id}")
    public void delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { postService.deletePost(id, me); }

    @PutMapping("/posts/{id}/pinned")
    public void pin(@PathVariable Long id, @RequestBody Map<String, Boolean> body, @AuthenticationPrincipal UserPrincipal me) {
        postService.setPinned(id, Boolean.TRUE.equals(body.get("pinned")), me);
    }

    @PostMapping(value = "/posts/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PostAttachmentDto upload(@PathVariable Long id, @RequestParam("file") MultipartFile file, @AuthenticationPrincipal UserPrincipal me) {
        return postService.insertAttachment(id, file, me);
    }

    @GetMapping("/post-attachments/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        ItemService.Download d = postService.getDownload(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(d.size())
                .body(d.resource());
    }

    @DeleteMapping("/post-attachments/{id}")
    public void deleteAttachment(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { postService.deleteAttachment(id, me); }

    @PostMapping("/posts/{id}/comments")
    public PostCommentDto comment(@PathVariable Long id, @Valid @RequestBody PostCommentRequest req, @AuthenticationPrincipal UserPrincipal me) {
        return postService.insertComment(id, req, me);
    }

    @DeleteMapping("/post-comments/{id}")
    public void deleteComment(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal me) { postService.deleteComment(id, me); }
}
