package com.inticube.teamboard.dao;

import com.inticube.teamboard.domain.BoardDuty;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface BoardDutyDao {
    List<BoardDuty> selectByBoard(@Param("boardId") Long boardId);
    BoardDuty selectById(@Param("id") Long id);
    List<Map<String, Object>> selectItemCounts(@Param("boardId") Long boardId);
    int insert(BoardDuty d);
    int update(BoardDuty d);
    int deleteByIds(@Param("ids") Collection<Long> ids);
    int deleteByBoard(@Param("boardId") Long boardId);
}
