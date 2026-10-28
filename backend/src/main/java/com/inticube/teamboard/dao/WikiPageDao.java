package com.inticube.teamboard.dao;

import com.inticube.teamboard.domain.WikiPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WikiPageDao {
    WikiPage selectById(@Param("id") Long id);
    List<WikiPage> selectByBoard(@Param("boardId") Long boardId);
    List<WikiPage> selectByParent(@Param("parentId") Long parentId);
    long selectCountByBoard(@Param("boardId") Long boardId);
    int selectMaxSort(@Param("boardId") Long boardId);
    int insert(WikiPage p);
    int update(WikiPage p);
    int updateParent(@Param("id") Long id, @Param("parentId") Long parentId);
    int updateSort(@Param("id") Long id, @Param("sortOrder") int sortOrder);
    int delete(@Param("id") Long id);
    int deleteByBoard(@Param("boardId") Long boardId);
}
