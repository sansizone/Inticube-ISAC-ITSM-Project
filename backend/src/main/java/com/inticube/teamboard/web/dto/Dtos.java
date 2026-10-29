package com.inticube.teamboard.web.dto;

import com.inticube.teamboard.domain.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** API 요청/응답 DTO 모음 */
public final class Dtos {
    private Dtos() {}

    // ---------- 사용자 ----------
    /** avatar: 프로필 사진 버전(없으면 null) — /api/users/{id}/avatar?v=버전 */
    public record UserSummary(Long id, String username, String name, String department, Long avatar) {
        public UserSummary(Long id, String username, String name, String department) {
            this(id, username, name, department, com.inticube.teamboard.service.AvatarService.version(id));
        }
        public static UserSummary of(AppUser u) {
            return u == null ? null : new UserSummary(u.getId(), u.getUsername(), u.getName(), u.getDepartment());
        }
    }

    /** mfaEnabled: OTP 설정됨, mfaSetupRequired: 정책상 OTP 를 설정해야 계속 쓸 수 있음 */
    public record MeDto(Long id, String username, String name, String department, Role role, boolean mustChangePassword,
                        boolean mfaEnabled, boolean mfaSetupRequired, Long avatar) {
        public static MeDto of(AppUser u) { return of(u, false); }
        public static MeDto of(AppUser u, boolean setupRequired) {
            return new MeDto(u.getId(), u.getUsername(), u.getName(), u.getDepartment(), u.getRole(), u.isMustChangePassword(), u.isTotpEnabled(), setupRequired,
                    com.inticube.teamboard.service.AvatarService.version(u.getId()));
        }
    }

    public record UserAdminDto(Long id, String username, String name, String department, Role role,
                               boolean active, boolean mustChangePassword, LocalDateTime createdAt, boolean mfaEnabled, Long avatar) {
        public static UserAdminDto of(AppUser u) {
            return new UserAdminDto(u.getId(), u.getUsername(), u.getName(), u.getDepartment(), u.getRole(),
                    u.isActive(), u.isMustChangePassword(), u.getCreatedAt(), u.isTotpEnabled(),
                    com.inticube.teamboard.service.AvatarService.version(u.getId()));
        }
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    /** 1단계(비밀번호) 통과 후 OTP 가 필요하면 mfaRequired=true, me=null */
    public record LoginResponse(boolean mfaRequired, MeDto me) {}
    public record MfaCodeRequest(@NotBlank @Size(max = 20) String code) {}
    public record MfaSetupResponse(String secret, String otpauthUrl, String issuer, String account) {}
    public record MfaEnableResponse(List<String> recoveryCodes) {}
    public record MfaDisableRequest(@NotBlank String password, @NotBlank @Size(max = 20) String code) {}
    public record PasswordChangeRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
    public record UserCreateRequest(@NotBlank @Size(max = 50) String username,
                                    @NotBlank @Size(max = 50) String name,
                                    @Size(max = 100) String department,
                                    Role role) {}
    public record UserUpdateRequest(@NotBlank @Size(max = 50) String name, @Size(max = 100) String department,
                                    Role role, Boolean active, @Size(max = 50) String username) {}
    public record TempPasswordResponse(String username, String temporaryPassword) {}
    public record BulkActivateItem(Long id, String username, String department) {}
    public record BulkActivateRequest(List<BulkActivateItem> users) {}
    public record BulkActivateResult(Long id, String name, String username, String temporaryPassword) {}

    // ---------- 보드 ----------
    public record StatusDto(Long id, String label, String color, int sortOrder, boolean done) {
        public static StatusDto of(BoardStatus s) {
            return s == null ? null : new StatusDto(s.getId(), s.getLabel(), s.getColor(), s.getSortOrder(), s.isDone());
        }
    }

    public record GroupDto(Long id, String name, String color, int sortOrder) {
        public static GroupDto of(BoardGroup g) {
            return new GroupDto(g.getId(), g.getName(), g.getColor(), g.getSortOrder());
        }
    }

    /** values: 사용자 정의 컬럼 값 (컬럼 ID → 값). 목록 응답에서는 여러 줄 텍스트 컬럼은 제외 */
    public record ItemDto(Long id, Long groupId, Long dutyId, Long parentId, String name, Long statusId, UserSummary assignee,
                          Priority priority, LocalDate startDate, LocalDate dueDate, int sortOrder,
                          long commentCount, long fileCount, LocalDateTime createdAt, LocalDateTime updatedAt,
                          Map<Long, String> values,
                          Integer keyNum, Long typeId, Long versionId, java.math.BigDecimal estimateHours, java.math.BigDecimal spentHours,
                          Integer progress, LocalDateTime respondedAt, LocalDateTime resolvedAt, String recurRule,
                          Long createdById) {}

    // ---------- v3.0 유지보수 ----------
    public record TypeDto(Long id, String name, String color, String icon, Integer responseHours, Integer resolveHours, int sortOrder, boolean isDefault) {
        public static TypeDto of(BoardType t) {
            return new TypeDto(t.getId(), t.getName(), t.getColor(), t.getIcon(), t.getResponseHours(), t.getResolveHours(), t.getSortOrder(), t.isDefaultType());
        }
    }
    public record TypeRequest(Long id, @NotBlank @Size(max = 50) String name, @Size(max = 20) String color, @Size(max = 30) String icon,
                              Integer responseHours, Integer resolveHours, boolean isDefault) {}

    public record VersionDto(Long id, String name, String description, LocalDate startDate, LocalDate dueDate, String status, int sortOrder) {
        public static VersionDto of(BoardVersion v) {
            return new VersionDto(v.getId(), v.getName(), v.getDescription(), v.getStartDate(), v.getDueDate(), v.getStatus(), v.getSortOrder());
        }
    }
    public record VersionRequest(@NotBlank @Size(max = 100) String name, @Size(max = 1000) String description, LocalDate startDate, LocalDate dueDate, String status) {}

    /** 관계. other = 상대 업무 요약, outgoing = 내가 from 쪽인지 */
    public record LinkDto(Long id, String type, boolean outgoing, Long itemId, Long boardId, String boardName, String key, String name, StatusDto status) {}
    public record LinkRequest(Long toItemId, String type) {}
    /** 워크스페이스 전체 관계 (표·목록의 '차단됨' 표시용) */
    public record LinkEdge(Long id, Long fromItemId, Long toItemId, String type) {}

    public record WatcherDto(Long userId, String name, String username) {}

    public record TimeEntryDto(Long id, UserSummary user, LocalDate workDate, java.math.BigDecimal hours, String comment, LocalDateTime createdAt) {
        public static TimeEntryDto of(TimeEntry t) {
            return new TimeEntryDto(t.getId(), UserSummary.of(t.getUser()), t.getWorkDate(), t.getHours(), t.getComment(), t.getCreatedAt());
        }
    }
    public record TimeEntryRequest(LocalDate workDate, java.math.BigDecimal hours, @Size(max = 500) String comment) {}

    public record WikiSummary(Long id, Long parentId, String title, int sortOrder, UserSummary updatedBy, LocalDateTime updatedAt) {
        public static WikiSummary of(WikiPage p) {
            return new WikiSummary(p.getId(), p.getParentId(), p.getTitle(), p.getSortOrder(), UserSummary.of(p.getUpdatedBy()), p.getUpdatedAt());
        }
    }
    public record WikiDto(Long id, Long boardId, Long parentId, String title, String content, int sortOrder,
                          UserSummary createdBy, UserSummary updatedBy, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record WikiRequest(@Size(max = 200) String title, @Size(max = 200000) String content, Long parentId) {}
    public record KeySettingsRequest(@Size(max = 10) String keyPrefix, Boolean slaBusinessHours) {}

    /** RELATION 컬럼은 refBoardId(대상 워크스페이스)·refColumnId(표시 컬럼, null=업무명) */
    public record ColumnDto(Long id, String name, String type, String scope, int sortOrder, boolean visible, Long refBoardId, Long refColumnId) {
        public static ColumnDto of(BoardColumn c) {
            return new ColumnDto(c.getId(), c.getName(), c.getType(), c.getScope(), c.getSortOrder(), c.isVisible(), c.getRefBoardId(), c.getRefColumnId());
        }
    }

    public record ColumnRequest(@NotBlank @Size(max = 100) String name, String type, String scope, Boolean visible, Long refBoardId, Long refColumnId) {}

    /** 워크스페이스(= 보드) 요약. myRole: OWNER / MEMBER / null */
    public record BoardSummary(Long id, String name, String description, String ownerName,
                               long itemCount, long doneCount, boolean isPublic, long memberCount, String myRole, boolean favorite,
                               /** 진행률(%) 표시 여부 — 고객사·솔루션 같은 목록형 워크스페이스는 끔 (컬럼 설정 JSON 의 showProgress) */
                               boolean showProgress) {}

    public record BoardMemberDto(Long userId, String username, String name, String department, boolean active, String role) {}
    public record BoardMemberItem(Long userId, String role) {}
    public record BoardMembersRequest(List<BoardMemberItem> members, Boolean isPublic) {}

    /** 보기 탭. config = 필터·정렬·표시 컬럼 JSON (프론트 정의) */
    public record ViewDto(Long id, String name, String type, String config, int sortOrder) {
        public static ViewDto of(BoardView v) { return new ViewDto(v.getId(), v.getName(), v.getType(), v.getConfig(), v.getSortOrder()); }
    }
    public record ViewRequest(@Size(max = 50) String name, String type, @Size(max = 20000) String config) {}
    public record ColumnConfigRequest(@Size(max = 20000) String config) {}
    public record MoveRequest(Long boardId, Long groupId) {}
    /** 여러 업무 한꺼번에: 이동(boardId·groupId)·복제·삭제·내보내기 */
    public record BulkRequest(java.util.List<Long> ids, Long boardId, Long groupId) {}
    /** done: 처리한 업무 수, skipped: 건너뛴 수(상위와 함께 처리된 하위 업무 등) */
    public record BulkResult(int done, int skipped) {}
    public record PickOption(Long id, String label, String sub) {}
    /** 이 업무를 연결 컬럼으로 참조하는 다른 업무 */
    public record Backlink(Long columnId, String columnName, Long boardId, String boardName, List<PickOption> items) {}

    /** refBoards: 연결 컬럼 대상 워크스페이스 이름, refLabels: 컬럼 ID → (연결된 업무 ID → 표시값) */
    public record BoardDetail(Long id, String name, String description, Long ownerId, String ownerName,
                              boolean isPublic, boolean canManage, String columnConfig,
                              String keyPrefix, boolean slaBusinessHours,
                              List<StatusDto> statuses, List<GroupDto> groups, List<DutyDto> duties,
                              List<ColumnDto> columns, List<ViewDto> views,
                              List<TypeDto> types, List<VersionDto> versions, List<LinkEdge> links,
                              Map<Long, String> refBoards, Map<Long, Map<Long, String>> refLabels,
                              List<ItemDto> items) {}

    /** 담당업무 노드 (평면 목록 — 프론트에서 parentId 로 트리 구성). itemCount 는 해당 노드에 직접 지정된 업무 수 */
    public record DutyDto(Long id, Long parentId, String name, int sortOrder, UserSummary primary, UserSummary secondary, long itemCount) {}

    public record DutyRequest(@NotBlank @Size(max = 100) String name, Long parentId, Long primaryId, Long secondaryId) {}

    public record DutyMoveRequest(Long parentId, int position) {}

    public record BoardRequest(@NotBlank @Size(max = 100) String name, @Size(max = 1000) String description, Boolean isPublic) {}
    public record StatusRequest(Long id, @NotBlank @Size(max = 50) String label,
                                @NotBlank @Size(max = 20) String color, boolean done) {}
    public record GroupRequest(@NotBlank @Size(max = 100) String name, @Size(max = 20) String color) {}
    public record ItemCreateRequest(@NotBlank @Size(max = 300) String name, Long parentId) {}
    public record ReorderRequest(List<Long> ids) {}
    /** 양식 보기 접수: values 키는 "description" 또는 컬럼 ID */
    public record FormSubmitRequest(@Size(max = 300) String name, Map<String, Object> values) {}
    /** 자동화 규칙 저장 */
    public record AutomationRule(Long statusId, String action, Long groupId, Long boardId) {}
    public record AutomationRequest(List<AutomationRule> rules) {}

    // ---------- 아이템 상세 ----------
    /** authorName: 외부에서 가져온 댓글의 원래 작성자 이름 (없으면 null) */
    public record UpdateDto(Long id, Long parentId, UserSummary author, String authorName, String content, boolean system, LocalDateTime createdAt, LocalDateTime updatedAt) {
        public static UpdateDto of(ItemUpdate u) {
            return new UpdateDto(u.getId(), u.getParentId(), UserSummary.of(u.getAuthor()), u.getAuthorName(), u.getContent(), u.isSystem(), u.getCreatedAt(), u.getUpdatedAt());
        }
    }

    public record AttachmentDto(Long id, String originalName, String contentType, long fileSize,
                                String uploaderName, Long uploaderId, LocalDateTime createdAt) {
        public static AttachmentDto of(Attachment a) {
            return new AttachmentDto(a.getId(), a.getOriginalName(), a.getContentType(), a.getFileSize(),
                    a.getUploader().getName(), a.getUploader().getId(), a.getCreatedAt());
        }
    }

    public record ItemDetail(ItemDto item, String description, Long boardId, String boardName, String groupName,
                             UserSummary createdBy, LocalDateTime createdAt,
                             List<UpdateDto> updates, List<AttachmentDto> attachments, Map<Long, String> values,
                             Map<Long, Map<Long, String>> refLabels,
                             List<LinkDto> links, List<WatcherDto> watchers, boolean watching, List<TimeEntryDto> timeEntries) {}

    public record UpdateRequest(@NotBlank @Size(max = 10000) String content, Long parentId) {}

    // ---------- 알림/대시보드/검색 ----------
    public record NotificationDto(Long id, NotificationType type, String message, Long boardId, Long itemId,
                                  Long postId, boolean read, LocalDateTime createdAt) {
        public static NotificationDto of(Notification n) {
            return new NotificationDto(n.getId(), n.getType(), n.getMessage(), n.getBoardId(), n.getItemId(),
                    n.getPostId(), n.isRead(), n.getCreatedAt());
        }
    }

    public record NotificationList(long unreadCount, List<NotificationDto> items) {}

    public record MyItemDto(Long id, Long boardId, String boardName, String groupName, String name,
                            StatusDto status, Priority priority, LocalDate startDate, LocalDate dueDate,
                            UserSummary assignee, String key) {
        public static MyItemDto of(Item i) {
            return new MyItemDto(i.getId(), i.getBoard().getId(), i.getBoard().getName(), i.getGroup().getName(),
                    i.getName(), StatusDto.of(i.getStatus()), i.getPriority(), i.getStartDate(), i.getDueDate(),
                    UserSummary.of(i.getAssignee()), keyOf(i));
        }
    }

    public static String keyOf(Item i) {
        if (i.getKeyNum() == null) return null;
        String p = i.getBoard().getKeyPrefix();
        return (p == null || p.isBlank() ? "TB" : p) + "-" + i.getKeyNum();
    }

    public record MyDashboard(long openCount, long overdueCount, long dueThisWeekCount, long doneCount,
                              List<MyItemDto> overdue, List<MyItemDto> dueSoon, List<MyItemDto> open,
                              List<BoardSummary> boards,
                              /** 이번 주(월~일) 완료 건수, 지난주 완료 건수, 이번 주 요일별(월~일) 완료 건수 */
                              long doneThisWeek, long doneLastWeek, List<Long> doneByDay) {}

    // ---------- 게시판 ----------
    public record CategoryDto(Long id, String name, String description, int sortOrder, boolean adminOnlyWrite, long postCount) {}

    public record CategoryRequest(@NotBlank @Size(max = 50) String name, @Size(max = 300) String description,
                                  boolean adminOnlyWrite) {}

    public record PostSummary(Long id, Long categoryId, String categoryName, String title, UserSummary author,
                              boolean pinned, long viewCount, long commentCount, long fileCount,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record PostPage(List<PostSummary> pinned, List<PostSummary> content, int page, int size,
                           long totalElements, int totalPages) {}

    public record PostAttachmentDto(Long id, String originalName, long fileSize, Long uploaderId, LocalDateTime createdAt) {
        public static PostAttachmentDto of(PostAttachment a) {
            return new PostAttachmentDto(a.getId(), a.getOriginalName(), a.getFileSize(), a.getUploader().getId(), a.getCreatedAt());
        }
    }

    public record PostCommentDto(Long id, Long parentId, UserSummary author, String content, boolean deleted, LocalDateTime createdAt, LocalDateTime updatedAt) {
        public static PostCommentDto of(PostComment c) {
            return new PostCommentDto(c.getId(), c.getParentId(), c.isDeleted() ? null : UserSummary.of(c.getAuthor()),
                    c.isDeleted() ? "" : c.getContent(), c.isDeleted(), c.getCreatedAt(), c.isDeleted() ? null : c.getUpdatedAt());
        }
    }

    public record PostDetail(Long id, Long categoryId, String categoryName, String title, String content,
                             UserSummary author, boolean pinned, long viewCount,
                             LocalDateTime createdAt, LocalDateTime updatedAt,
                             List<PostAttachmentDto> attachments, List<PostCommentDto> comments,
                             Long prevId, String prevTitle, Long nextId, String nextTitle) {}

    public record PostRequest(Long categoryId, @NotBlank @Size(max = 200) String title,
                              @NotBlank @Size(max = 200000) String content, boolean pinned) {}

    public record PostCommentRequest(@NotBlank @Size(max = 5000) String content, Long parentId) {}
}
