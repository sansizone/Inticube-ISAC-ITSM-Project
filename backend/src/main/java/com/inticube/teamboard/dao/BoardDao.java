package com.inticube.teamboard.dao;

import com.inticube.teamboard.domain.Board;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BoardDao {
    Board selectById(@Param("id") Long id);
    List<Board> selectAllOrderByName();
    List<Board> selectAll();
    List<Long> selectPublicIds();
    List<Board> selectPublicNull();
    long selectCount();
    int insert(Board b);
    int update(Board b);
    int updateNextSeq(@Param("id") Long id, @Param("nextSeq") Integer nextSeq);
    int updateDescriptionNull(@Param("description") String description);
    int delete(@Param("id") Long id);
}
