package com.inticube.teamboard.dao;

import com.inticube.teamboard.domain.Item;
import com.inticube.teamboard.domain.ItemValue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface ItemDao {
    Long lockById(@Param("id") Long id);
    int updateTouch(@Param("id") Long id, @Param("at") java.time.LocalDateTime at, @Param("respondedAt") java.time.LocalDateTime respondedAt);
    Item selectById(@Param("id") Long id);
    List<Item> selectByIds(@Param("ids") Collection<Long> ids);
    List<Item> selectByBoard(@Param("boardId") Long boardId);
    /** 휴지통 포함 전체 (워크스페이스 삭제용) */
    List<Item> selectByBoardAll(@Param("boardId") Long boardId);

    // ---------- 휴지통 ----------
    int updateSoftDelete(@Param("ids") Collection<Long> ids, @Param("by") Long by, @Param("reason") String reason, @Param("at") java.time.LocalDateTime at);
    int updateRestore(@Param("ids") Collection<Long> ids, @Param("at") java.time.LocalDateTime at);
    int updateGroupOfTrashed(@Param("fromGroupId") Long fromGroupId, @Param("toGroupId") Long toGroupId);
    List<java.util.Map<String, Object>> selectTrash(@Param("boardId") Long boardId, @Param("q") String q, @Param("offset") int offset, @Param("limit") int limit);
    long selectTrashCount(@Param("boardId") Long boardId, @Param("q") String q);
    List<Item> selectByGroup(@Param("groupId") Long groupId);
    List<Item> selectByParent(@Param("parentId") Long parentId);
    List<Item> selectByParents(@Param("ids") Collection<Long> ids);
    List<Item> selectByAssignee(@Param("userId") Long userId);
    List<Item> selectByNameLike(@Param("pattern") String pattern);
    Item selectByBoardAndKey(@Param("boardId") Long boardId, @Param("keyNum") Integer keyNum);
    List<Item> selectWithoutKey(@Param("boardId") Long boardId);
    List<Item> selectOpenDueBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
    List<Item> selectOpenWithSla();
    int selectMaxSort(@Param("groupId") Long groupId);
    int selectMaxKeyNum(@Param("boardId") Long boardId);
    List<Map<String, Object>> selectCountByBoard();
    List<Map<String, Object>> selectRowsByBoard(@Param("boardId") Long boardId);
    List<Map<String, Object>> selectNames(@Param("ids") Collection<Long> ids);
    List<Map<String, Object>> selectPickRows(@Param("boardId") Long boardId);
    List<Long> selectIdsByBoard(@Param("boardId") Long boardId);
    int insert(Item i);
    int update(Item i);
    int updateGroupAndSort(@Param("id") Long id, @Param("groupId") Long groupId, @Param("sortOrder") int sortOrder);
    int updateGroupOfChildren(@Param("parentId") Long parentId, @Param("groupId") Long groupId);
    int updateTypeOfChildren(@Param("parentId") Long parentId, @Param("typeId") Long typeId);
    int updateKeyNum(@Param("id") Long id, @Param("keyNum") Integer keyNum);
    int updateClearStatus(@Param("ids") Collection<Long> ids);
    int updateClearType(@Param("ids") Collection<Long> ids);
    int updateClearVersion(@Param("versionId") Long versionId);
    int updateClearDuty(@Param("ids") Collection<Long> ids);
    int updateClearParent(@Param("ids") Collection<Long> ids);
    int deleteByIds(@Param("ids") Collection<Long> ids);
    // 사용자 정의 컬럼 값
    List<ItemValue> selectValuesByItem(@Param("itemId") Long itemId);
    List<ItemValue> selectValuesByItems(@Param("ids") Collection<Long> ids);
    List<ItemValue> selectValuesByColumns(@Param("ids") Collection<Long> ids);
    List<ItemValue> selectValuesByItemAndColumns(@Param("itemId") Long itemId, @Param("ids") Collection<Long> ids);
    List<Map<String, Object>> selectValueTuplesByColumns(@Param("ids") Collection<Long> ids);
    List<Map<String, Object>> selectValueTuplesByColumn(@Param("columnId") Long columnId);
    List<Map<String, Object>> selectValueTuplesByColumnAndItems(@Param("columnId") Long columnId, @Param("ids") Collection<Long> ids);
    List<Map<String, Object>> selectValueTuplesLike(@Param("ids") Collection<Long> ids, @Param("pattern") String pattern);
    int insertValue(ItemValue v);
    int batchInsertValues(@Param("list") List<ItemValue> list);
    int updateValue(ItemValue v);
    int deleteValue(@Param("id") Long id);
    int deleteValuesByItems(@Param("ids") Collection<Long> ids);
    int deleteValuesByColumn(@Param("columnId") Long columnId);
}
