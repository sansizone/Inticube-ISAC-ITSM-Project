package com.inticube.teamboard.dao;

import com.inticube.teamboard.domain.WsGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 워크스페이스 그룹 · 그룹 멤버 · 워크스페이스의 그룹 지정 (TB_WS_GROUP, TB_WS_GROUP_MEMBER, TB_BOARD.WS_GROUP_ID) */
@Mapper
public interface WsGroupDao {
    long selectTableCount();
    List<WsGroup> selectAll();
    WsGroup selectById(@Param("id") Long id);
    int insert(WsGroup g);
    int update(WsGroup g);
    int delete(@Param("id") Long id);
    int selectMaxSort();

    /** [{group_id, user_id, member_role, username, name, department, is_active}] */
    List<Map<String, Object>> selectMembers(@Param("groupId") Long groupId);
    /** [{group_id, member_role}] 내가 속한 그룹 */
    List<Map<String, Object>> selectRolesByUser(@Param("userId") Long userId);
    String selectRole(@Param("groupId") Long groupId, @Param("userId") Long userId);
    int insertMember(@Param("groupId") Long groupId, @Param("userId") Long userId, @Param("role") String role);
    int updateMemberRole(@Param("groupId") Long groupId, @Param("userId") Long userId, @Param("role") String role);
    int deleteMember(@Param("groupId") Long groupId, @Param("userId") Long userId);
    int deleteMembersByGroup(@Param("groupId") Long groupId);
    /** [{group_id, cnt}] */
    List<Map<String, Object>> selectMemberCounts();

    /** [{id, ws_group_id}] 전체 워크스페이스의 그룹 */
    List<Map<String, Object>> selectBoardGroups();
    Long selectBoardGroup(@Param("boardId") Long boardId);
    int updateBoardGroup(@Param("boardId") Long boardId, @Param("groupId") Long groupId);
    int updateBoardGroupWhereNull(@Param("groupId") Long groupId);
    long selectBoardCount(@Param("groupId") Long groupId);
}
