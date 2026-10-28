package com.inticube.teamboard.config;

import com.inticube.teamboard.dao.AppUserDao;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DbUserDetailsService implements UserDetailsService {
    private final AppUserDao userDao;

    @Override
    public UserDetails loadUserByUsername(String username) {
        var u = userDao.selectByUsername(username);
        if (u == null) throw new UsernameNotFoundException(username);
        return UserPrincipal.of(u);
    }
}
