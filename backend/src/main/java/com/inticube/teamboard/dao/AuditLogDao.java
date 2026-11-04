package com.inticube.teamboard.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 감사 로그 (TB_AUDIT_LOG) — 넣기·조회만 있음 (수정·삭제는 DB 트리거로도 막혀 있음) */
@Mapper
public interface AuditLogDao {
    long selectTableCount();
    int insert(Map<String, Object> row);
    List<Map<String, Object>> selectPage(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                         @Param("actorId") Long actorId, @Param("action") String action,
                                         @Param("q") String q, @Param("offset") int offset, @Param("limit") int limit);
    long selectCount(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                     @Param("actorId") Long actorId, @Param("action") String action, @Param("q") String q);
}
