package com.inticube.teamboard.dao;

import com.inticube.teamboard.domain.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 업무 부속: 업데이트(댓글·활동), 첨부파일, 관계, 감시자, 시간 기록 */
@Mapper
public interface ItemExtraDao {
    ItemUpdate selectUpdateById(@Param("id") Long id);
    List<ItemUpdate> selectUpdatesByItem(@Param("itemId") Long itemId);
    List<ItemUpdate> selectUpdatesByParent(@Param("parentId") Long parentId);
    List<Map<String, Object>> selectCommentCountByBoard(@Param("boardId") Long boardId);
    int insertUpdate(ItemUpdate u);
    int batchInsertUpdates(@Param("list") List<ItemUpdate> list);
    List<Map<String, Object>> selectUpdateIdByExternal(@Param("ids") Collection<Long> itemIds);
    int updateParent(@Param("id") Long id, @Param("parentId") Long parentId);
    ItemUpdate selectLastSystemUpdate(@Param("itemId") Long itemId);
    int updateUpdateContent(ItemUpdate u);
    int deleteUpdate(@Param("id") Long id);
    int deleteUpdatesByParent(@Param("parentId") Long parentId);
    int deleteUpdatesByItems(@Param("ids") Collection<Long> ids);

    Attachment selectAttachmentById(@Param("id") Long id);
    List<Attachment> selectAttachmentsByItem(@Param("itemId") Long itemId);
    List<Attachment> selectAttachmentsByItems(@Param("ids") Collection<Long> ids);
    List<Map<String, Object>> selectFileCountByBoard(@Param("boardId") Long boardId);
    int insertAttachment(Attachment a);
    int deleteAttachment(@Param("id") Long id);
    int deleteAttachmentsByItems(@Param("ids") Collection<Long> ids);

    ItemLink selectLinkById(@Param("id") Long id);
    List<ItemLink> selectLinksByItem(@Param("itemId") Long itemId);
    List<ItemLink> selectLinksByBoard(@Param("boardId") Long boardId);
    boolean selectLinkExists(@Param("fromId") Long fromId, @Param("toId") Long toId, @Param("type") String type);
    int insertLink(ItemLink l);
    int deleteLink(@Param("id") Long id);
    int deleteLinksByItems(@Param("ids") Collection<Long> ids);

    List<ItemWatcher> selectWatchersByItem(@Param("itemId") Long itemId);
    boolean selectWatcherExists(@Param("itemId") Long itemId, @Param("userId") Long userId);
    List<Long> selectWatchingItemIds(@Param("userId") Long userId);
    int insertWatcher(@Param("itemId") Long itemId, @Param("userId") Long userId);
    int deleteWatcher(@Param("itemId") Long itemId, @Param("userId") Long userId);
    int deleteWatchersByItems(@Param("ids") Collection<Long> ids);

    TimeEntry selectTimeById(@Param("id") Long id);
    List<TimeEntry> selectTimesByItem(@Param("itemId") Long itemId);
    List<Map<String, Object>> selectTimeSumByBoard(@Param("boardId") Long boardId);
    int insertTime(TimeEntry t);
    int updateTimeBoard(@Param("boardId") Long boardId, @Param("ids") Collection<Long> ids);
    int deleteTime(@Param("id") Long id);
    int deleteTimesByItems(@Param("ids") Collection<Long> ids);

    // ---------- 보존·논리 삭제 (v3.6) ----------
    int updateUpdateDeleted(@Param("id") Long id, @Param("by") Long by, @Param("at") java.time.LocalDateTime at);
    int insertUpdateRev(@Param("updateId") Long updateId, @Param("content") String content, @Param("by") Long by, @Param("at") java.time.LocalDateTime at);
    List<java.util.Map<String, Object>> selectUpdateRevs(@Param("updateId") Long updateId);
    int updateAttachmentDeleted(@Param("id") Long id, @Param("by") Long by, @Param("at") java.time.LocalDateTime at);
    int updateTimeDeleted(@Param("id") Long id, @Param("by") Long by, @Param("at") java.time.LocalDateTime at);
    List<java.util.Map<String, Object>> selectAttachmentsByHash(@Param("boardId") Long boardId, @Param("hash") String hash);
    java.math.BigDecimal selectTimeSumByUserDate(@Param("userId") Long userId, @Param("date") java.time.LocalDate date);
    java.math.BigDecimal selectTimeSumByItem(@Param("itemId") Long itemId);
    long selectTimeCountAllByItem(@Param("itemId") Long itemId);
}
