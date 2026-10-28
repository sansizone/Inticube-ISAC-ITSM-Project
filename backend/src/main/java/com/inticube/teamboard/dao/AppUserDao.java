package com.inticube.teamboard.dao;

import com.inticube.teamboard.domain.AppUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface AppUserDao {
    AppUser selectById(@Param("id") Long id);
    AppUser selectByUsername(@Param("username") String username);
    List<AppUser> selectAllOrderByName();
    List<AppUser> selectActiveOrderByName();
    List<AppUser> selectByIds(@Param("ids") Collection<Long> ids);
    List<AppUser> selectActiveByUsernames(@Param("names") Collection<String> names);
    long selectCount();
    boolean selectExistsByUsername(@Param("username") String username);
    long selectActiveAdminCount();
    int selectMaxImportSeq();
    int insert(AppUser u);
    int update(AppUser u);
}
