package com.inticube.teamboard.dao;

import com.inticube.teamboard.domain.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 워크스페이스 구성 요소: 그룹·상태·사용자 정의 컬럼·유형·버전·보기 탭·멤버 */
@Mapper
public interface BoardPartsDao {
    // 그룹
    List<BoardGroup> selectGroupsByBoard(@Param("boardId") Long boardId);
    BoardGroup selectGroupById(@Param("id") Long id);
    int selectGroupMaxSort(@Param("boardId") Long boardId);
    int insertGroup(BoardGroup g);
    int updateGroup(BoardGroup g);
    int deleteGroup(@Param("id") Long id);
    int deleteGroupsByBoard(@Param("boardId") Long boardId);
    // 상태
    List<BoardStatus> selectStatusesByBoard(@Param("boardId") Long boardId);
    BoardStatus selectStatusById(@Param("id") Long id);
    int insertStatus(BoardStatus s);
    int updateStatus(BoardStatus s);
    int deleteStatuses(@Param("ids") Collection<Long> ids);
    int deleteStatusesByBoard(@Param("boardId") Long boardId);
    // 사용자 정의 컬럼
    List<BoardColumn> selectColumnsByBoard(@Param("boardId") Long boardId);
    BoardColumn selectColumnById(@Param("id") Long id);
    List<BoardColumn> selectColumnsByRefBoardAndType(@Param("refBoardId") Long refBoardId, @Param("type") String type);
    int selectColumnMaxSort(@Param("boardId") Long boardId);
    int insertColumn(BoardColumn c);
    int updateColumn(BoardColumn c);
    int deleteColumn(@Param("id") Long id);
    int deleteColumnsByBoard(@Param("boardId") Long boardId);
    // 유형
    List<BoardType> selectTypesByBoard(@Param("boardId") Long boardId);
    BoardType selectTypeById(@Param("id") Long id);
    List<Long> selectBoardIdsWithTypes();
    int insertType(BoardType t);
    int updateType(BoardType t);
    int deleteTypes(@Param("ids") Collection<Long> ids);
    int deleteTypesByBoard(@Param("boardId") Long boardId);
    // 버전
    List<BoardVersion> selectVersionsByBoard(@Param("boardId") Long boardId);
    BoardVersion selectVersionById(@Param("id") Long id);
    int selectVersionMaxSort(@Param("boardId") Long boardId);
    int insertVersion(BoardVersion v);
    int updateVersion(BoardVersion v);
    int deleteVersion(@Param("id") Long id);
    int deleteVersionsByBoard(@Param("boardId") Long boardId);
    // 보기 탭
    List<BoardView> selectViewsByBoard(@Param("boardId") Long boardId);
    BoardView selectViewById(@Param("id") Long id);
    long selectViewCount(@Param("boardId") Long boardId);
    int selectViewMaxSort(@Param("boardId") Long boardId);
    List<Long> selectBoardIdsWithViews();
    int insertView(BoardView v);
    int updateView(BoardView v);
    int deleteView(@Param("id") Long id);
    int deleteViewsByBoard(@Param("boardId") Long boardId);
    // 멤버
    List<BoardMember> selectMembersByBoard(@Param("boardId") Long boardId);
    List<BoardMember> selectMembersByUser(@Param("userId") Long userId);
    BoardMember selectMember(@Param("boardId") Long boardId, @Param("userId") Long userId);
    boolean selectMemberExists(@Param("boardId") Long boardId, @Param("userId") Long userId);
    List<Long> selectBoardIdsByUser(@Param("userId") Long userId);
    List<Map<String, Object>> selectMemberCountByBoard();
    int insertMember(BoardMember m);
    int updateMemberRole(@Param("id") Long id, @Param("role") String role);
    int deleteMember(@Param("id") Long id);
    int deleteMembersByBoard(@Param("boardId") Long boardId);
    // 즐겨찾기
    List<Long> selectFavoriteBoardIds(@Param("userId") Long userId);
    boolean selectFavoriteExists(@Param("boardId") Long boardId, @Param("userId") Long userId);
    int insertFavorite(@Param("boardId") Long boardId, @Param("userId") Long userId);
    int deleteFavorite(@Param("boardId") Long boardId, @Param("userId") Long userId);
    int deleteFavoritesByBoard(@Param("boardId") Long boardId);
    // 사이드바 정렬 순서 (사용자별)
    List<Map<String, Object>> selectBoardOrders(@Param("userId") Long userId);
    int batchInsertBoardOrders(@Param("userId") Long userId, @Param("ids") List<Long> ids);
    int deleteBoardOrdersByUser(@Param("userId") Long userId);
    int deleteBoardOrdersByBoard(@Param("boardId") Long boardId);
}
