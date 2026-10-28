package com.inticube.teamboard.service;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.dao.AppUserDao;
import com.inticube.teamboard.dao.NotificationDao;
import com.inticube.teamboard.dao.PostDao;
import com.inticube.teamboard.domain.*;
import com.inticube.teamboard.web.ApiException;
import com.inticube.teamboard.web.dto.Dtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.inticube.teamboard.dao.Rows.lng;
import static com.inticube.teamboard.dao.Rows.lngOr0;

/** 게시판: 카테고리, 게시글, 댓글, 첨부 */
@Service
@RequiredArgsConstructor
@Transactional
public class PostService {
    private static final Pattern MENTION = Pattern.compile("@([A-Za-z0-9._-]{3,50}(?:@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)?)");  // @아이디 또는 @이메일아이디

    private final PostDao postDao;
    private final MentionResolver mentions;
    private final AppUserDao userDao;
    private final NotificationDao notiDao;
    private final NotificationService notifier;
    private final FileStorageService storage;

    // ---------- 카테고리 ----------
    @Transactional(readOnly = true)
    public List<CategoryDto> getCategories() {
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> r : postDao.selectCountByCategory()) counts.put(lng(r, "category_id"), lngOr0(r, "cnt"));
        return postDao.selectCategories().stream()
                .map(c -> toDto(c, counts.getOrDefault(c.getId(), 0L))).toList();
    }

    public CategoryDto insertCategory(CategoryRequest req) {
        PostCategory c = new PostCategory();
        c.setName(req.name().trim());
        c.setDescription(req.description());
        c.setAdminOnlyWrite(req.adminOnlyWrite());
        c.setSortOrder(postDao.selectCategoryMaxSort() + 1);
        postDao.insertCategory(c);
        return toDto(c, 0);
    }

    public CategoryDto updateCategory(Long id, CategoryRequest req) {
        PostCategory c = category(id);
        c.setName(req.name().trim());
        c.setDescription(req.description());
        c.setAdminOnlyWrite(req.adminOnlyWrite());
        postDao.updateCategory(c);
        return toDto(c, postDao.selectCountInCategory(id));
    }

    public void deleteCategory(Long id) {
        PostCategory c = category(id);
        if (postDao.selectCountInCategory(id) > 0) throw ApiException.bad("게시글이 있는 게시판은 삭제할 수 없습니다. 글을 먼저 옮기거나 삭제하세요.");
        postDao.deleteCategory(c.getId());
    }

    public void setCategoryOrder(List<Long> ids) {
        int order = 1;
        for (Long id : ids) { PostCategory c = category(id); c.setSortOrder(order++); postDao.updateCategory(c); }
    }

    private PostCategory category(Long id) {
        PostCategory c = postDao.selectCategoryById(id);
        if (c == null) throw ApiException.notFound("게시판");
        return c;
    }

    private static CategoryDto toDto(PostCategory c, long count) {
        return new CategoryDto(c.getId(), c.getName(), c.getDescription(), c.getSortOrder(), c.isAdminOnlyWrite(), count);
    }

    // ---------- 게시글 목록/상세 ----------
    @Transactional(readOnly = true)
    public PostPage getPostList(Long categoryId, String q, int page, int size) {
        size = Math.min(Math.max(size, 5), 100);
        page = Math.max(page, 0);
        String raw = q == null ? "" : q.trim().replace("\\", "").replace("%", "").replace("_", "");
        boolean hasQuery = !raw.isEmpty();
        String like = hasQuery ? "%" + raw.toLowerCase() + "%" : null;
        // 검색이 아닐 때는 상단 고정글을 따로 보여주고 일반 목록에서는 제외
        List<Post> rows = postDao.selectPosts(categoryId, like, !hasQuery, page * size, size);
        long total = postDao.selectPostCount(categoryId, like, !hasQuery);
        List<Post> pinned = hasQuery ? List.of() : postDao.selectPinned(categoryId);
        List<Post> allPosts = new ArrayList<>(pinned);
        allPosts.addAll(rows);
        Map<Long, long[]> counts = counts(allPosts);
        return new PostPage(pinned.stream().map(p -> summary(p, counts)).toList(),
                rows.stream().map(p -> summary(p, counts)).toList(),
                page, size, total, (int) ((total + size - 1) / size));
    }

    private Map<Long, long[]> counts(List<Post> list) {
        Map<Long, long[]> m = new HashMap<>();
        if (list.isEmpty()) return m;
        List<Long> ids = list.stream().map(Post::getId).toList();
        for (Map<String, Object> r : postDao.selectCommentCountByPosts(ids)) m.computeIfAbsent(lng(r, "post_id"), k -> new long[2])[0] = lngOr0(r, "cnt");
        for (Map<String, Object> r : postDao.selectAttachmentCountByPosts(ids)) m.computeIfAbsent(lng(r, "post_id"), k -> new long[2])[1] = lngOr0(r, "cnt");
        return m;
    }

    private static PostSummary summary(Post p, Map<Long, long[]> counts) {
        long[] c = counts.getOrDefault(p.getId(), new long[2]);
        return new PostSummary(p.getId(), p.getCategory().getId(), p.getCategory().getName(), p.getTitle(),
                UserSummary.of(p.getAuthor()), p.isPinned(), p.getViewCount(), c[0], c[1], p.getCreatedAt(), p.getUpdatedAt());
    }

    /** countView: 같은 세션에서 처음 볼 때만 조회수 증가 */
    public PostDetail getPostDetail(Long id, boolean countView) {
        Post p = post(id);
        if (countView) {
            postDao.updateViewCount(id);
            p.setViewCount(p.getViewCount() + 1);
        }
        Post prev = postDao.selectPrev(p.getCategory().getId(), id);
        Post next = postDao.selectNext(p.getCategory().getId(), id);
        return new PostDetail(p.getId(), p.getCategory().getId(), p.getCategory().getName(), p.getTitle(), p.getContent(),
                UserSummary.of(p.getAuthor()), p.isPinned(), p.getViewCount(), p.getCreatedAt(), p.getUpdatedAt(),
                postDao.selectAttachmentsByPost(id).stream().map(PostAttachmentDto::of).toList(),
                postDao.selectCommentsByPost(id).stream().map(PostCommentDto::of).toList(),
                prev == null ? null : prev.getId(), prev == null ? null : prev.getTitle(),
                next == null ? null : next.getId(), next == null ? null : next.getTitle());
    }

    // ---------- 작성/수정/삭제 ----------
    public PostDetail insertPost(PostRequest req, UserPrincipal me) {
        PostCategory c = category(req.categoryId() == null ? -1L : req.categoryId());
        checkWrite(c, me);
        if (req.pinned() && !me.isAdmin()) throw new ApiException(HttpStatus.FORBIDDEN, "상단 고정은 관리자만 할 수 있습니다.");
        AppUser author = user(me.id());
        Post p = new Post();
        p.setCategory(c);
        p.setTitle(req.title().trim());
        p.setContent(req.content());
        p.setPinned(req.pinned());
        p.setAuthor(author);
        postDao.insertPost(p);
        notifyMentions(p, req.content(), author, "게시글");
        if (c.isAdminOnlyWrite()) {
            for (AppUser u : userDao.selectActiveOrderByName())
                notifier.insertNotifyPost(u, author, NotificationType.NOTICE, "[" + c.getName() + "] 새 글: " + p.getTitle(), p);
        }
        return getPostDetail(p.getId(), false);
    }

    public PostDetail updatePost(Long id, PostRequest req, UserPrincipal me) {
        Post p = post(id);
        checkOwner(p.getAuthor().getId(), me);
        if (req.categoryId() != null && !req.categoryId().equals(p.getCategory().getId())) {
            PostCategory c = category(req.categoryId());
            checkWrite(c, me);
            p.setCategory(c);
        }
        if (req.pinned() != p.isPinned() && !me.isAdmin()) throw new ApiException(HttpStatus.FORBIDDEN, "상단 고정은 관리자만 할 수 있습니다.");
        p.setTitle(req.title().trim());
        p.setContent(req.content());
        p.setPinned(req.pinned());
        p.setUpdatedAt(LocalDateTime.now());
        postDao.updatePost(p);
        return getPostDetail(id, false);
    }

    public void deletePost(Long id, UserPrincipal me) {
        Post p = post(id);
        checkOwner(p.getAuthor().getId(), me);
        postDao.selectAttachmentsByPost(id).forEach(a -> storage.delete(a.getStoredName()));
        postDao.deleteAttachmentsByPost(id);
        postDao.deleteCommentsByPost(id);
        notiDao.deleteByPost(id);
        postDao.deletePost(p.getId());
    }

    public void setPinned(Long id, boolean pinned, UserPrincipal me) {
        if (!me.isAdmin()) throw ApiException.forbidden();
        Post p = post(id);
        p.setPinned(pinned);
        postDao.updatePost(p);
    }

    // ---------- 첨부 ----------
    public PostAttachmentDto insertAttachment(Long postId, MultipartFile file, UserPrincipal me) {
        if (file == null || file.isEmpty()) throw ApiException.bad("빈 파일은 업로드할 수 없습니다.");
        Post p = post(postId);
        checkOwner(p.getAuthor().getId(), me);
        PostAttachment a = new PostAttachment();
        a.setPost(p);
        a.setOriginalName(FileStorageService.cleanName(file.getOriginalFilename()));
        a.setContentType(file.getContentType());
        a.setFileSize(file.getSize());
        a.setStoredName(storage.store(file));
        a.setUploader(user(me.id()));
        postDao.insertAttachment(a);
        return PostAttachmentDto.of(a);
    }

    @Transactional(readOnly = true)
    public ItemService.Download getDownload(Long attachmentId) {
        PostAttachment a = postDao.selectAttachmentById(attachmentId);
        if (a == null) throw ApiException.notFound("파일");
        return new ItemService.Download(a.getOriginalName(), a.getContentType(), a.getFileSize(), storage.load(a.getStoredName()));
    }

    public void deleteAttachment(Long attachmentId, UserPrincipal me) {
        PostAttachment a = postDao.selectAttachmentById(attachmentId);
        if (a == null) throw ApiException.notFound("파일");
        checkOwner(post(a.getPost().getId()).getAuthor().getId(), me);
        storage.delete(a.getStoredName());
        postDao.deleteAttachment(a.getId());
    }

    // ---------- 댓글 ----------
    public PostCommentDto insertComment(Long postId, PostCommentRequest req, UserPrincipal me) {
        Post p = post(postId);
        AppUser author = user(me.id());
        PostComment parent = null;
        if (req.parentId() != null) {
            parent = postDao.selectCommentById(req.parentId());
            if (parent == null) throw ApiException.notFound("댓글");
            if (!parent.getPost().getId().equals(postId)) throw ApiException.bad("잘못된 댓글입니다.");
            if (parent.getParentId() != null) throw ApiException.bad("답글에는 답글을 달 수 없습니다.");
        }
        PostComment c = new PostComment();
        c.setPost(p);
        c.setParentId(parent == null ? null : parent.getId());
        c.setAuthor(author);
        c.setContent(req.content().trim());
        postDao.insertComment(c);

        String preview = req.content().length() > 60 ? req.content().substring(0, 60) + "…" : req.content();
        Set<Long> notified = notifyMentions(p, req.content(), author, "댓글");
        if (notified.add(p.getAuthor().getId()))
            notifier.insertNotifyPost(p.getAuthor(), author, NotificationType.POST_COMMENT,
                    author.getName() + "님이 '" + p.getTitle() + "'에 댓글을 남겼습니다: " + preview, p);
        if (parent != null && notified.add(parent.getAuthor().getId()))
            notifier.insertNotifyPost(parent.getAuthor(), author, NotificationType.POST_COMMENT,
                    author.getName() + "님이 회원님의 댓글에 답글을 남겼습니다: " + preview, p);
        return PostCommentDto.of(c);
    }

    /** 댓글 수정 (작성자 본인만). 새로 추가된 @언급에만 알림 */
    public PostCommentDto updateComment(Long commentId, String content, UserPrincipal me) {
        PostComment c = postDao.selectCommentById(commentId);
        if (c == null || c.isDeleted()) throw ApiException.notFound("댓글");
        if (!c.getAuthor().getId().equals(me.id())) throw ApiException.forbidden();
        Set<Long> before = new HashSet<>();
        for (AppUser x : mentions.getMentionedUsers(c.getContent())) before.add(x.getId());
        c.setContent(content.trim());
        c.setUpdatedAt(LocalDateTime.now());
        postDao.updateCommentContent(c);
        Post p = post(c.getPost().getId());
        AppUser actor = user(me.id());
        for (AppUser u : mentions.getMentionedUsers(c.getContent()))
            if (!before.contains(u.getId()))
                notifier.insertNotifyPost(u, actor, NotificationType.POST_MENTION,
                        actor.getName() + "님이 '" + p.getTitle() + "' 댓글에서 회원님을 언급했습니다.", p);
        return PostCommentDto.of(c);
    }

    public void deleteComment(Long commentId, UserPrincipal me) {
        PostComment c = postDao.selectCommentById(commentId);
        if (c == null) throw ApiException.notFound("댓글");
        checkOwner(c.getAuthor().getId(), me);
        if (c.getParentId() == null && postDao.selectReplyExists(c.getId())) {
            c.setDeleted(true);
            c.setContent("");
            postDao.updateComment(c);
        } else {
            postDao.deleteComment(c.getId());
        }
    }

    // ---------- 내부 ----------
    private Set<Long> notifyMentions(Post p, String text, AppUser actor, String where) {
        Set<Long> notified = new HashSet<>();
        for (AppUser u : mentions.getMentionedUsers(text)) {
            notifier.insertNotifyPost(u, actor, NotificationType.POST_MENTION,
                    actor.getName() + "님이 '" + p.getTitle() + "' " + where + "에서 회원님을 언급했습니다.", p);
            notified.add(u.getId());
        }
        return notified;
    }

    private Post post(Long id) {
        Post p = postDao.selectPostById(id);
        if (p == null) throw ApiException.notFound("게시글");
        return p;
    }

    private AppUser user(Long id) {
        AppUser u = userDao.selectById(id);
        if (u == null) throw ApiException.notFound("사용자");
        return u;
    }

    private void checkWrite(PostCategory c, UserPrincipal me) {
        if (c.isAdminOnlyWrite() && !me.isAdmin())
            throw new ApiException(HttpStatus.FORBIDDEN, "'" + c.getName() + "'은(는) 관리자만 글을 쓸 수 있습니다.");
    }

    private void checkOwner(Long authorId, UserPrincipal me) {
        if (!me.isAdmin() && !authorId.equals(me.id())) throw ApiException.forbidden();
    }
}
