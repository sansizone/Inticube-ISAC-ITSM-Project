package com.inticube.teamboard.config;

import com.inticube.teamboard.dao.AppUserDao;
import com.inticube.teamboard.dao.PostDao;
import com.inticube.teamboard.domain.AppUser;
import com.inticube.teamboard.domain.PostCategory;
import com.inticube.teamboard.domain.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 최초 기동 시 관리자 계정·기본 게시판 생성 */
@Slf4j
@Component
@org.springframework.core.annotation.Order(5)
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {
    private final AppUserDao userDao;
    private final PostDao postDao;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        insertDefaultCategories();
        updateAdminResetIfRequested();
        if (userDao.selectCount() > 0) return;
        AppUser admin = new AppUser();
        admin.setUsername("admin");
        admin.setName("관리자");
        admin.setRole(Role.ADMIN);
        admin.setPasswordHash(encoder.encode(props.getInitAdminPassword()));
        admin.setMustChangePassword(true);
        userDao.insert(admin);
        log.warn("초기 관리자 계정(admin)이 생성되었습니다. 최초 로그인 후 비밀번호를 반드시 변경하세요.");
    }

    /**
     * 관리자 비밀번호 분실 복구: 환경변수 TEAMBOARD_RESET_ADMIN_PASSWORD 를 지정하고 기동하면
     * admin 계정을 그 비밀번호로 바꾸고 활성화 + 다음 로그인 때 비밀번호 변경을 요구한다.
     * 복구 후에는 환경변수를 반드시 지우고 다시 기동할 것.
     */
    private void updateAdminResetIfRequested() {
        String pw = System.getenv("TEAMBOARD_RESET_ADMIN_PASSWORD");
        if (pw == null || pw.isBlank()) return;
        AppUser a = userDao.selectByUsername("admin");
        if (a == null) return;
        a.setPasswordHash(encoder.encode(pw));
        a.setActive(true);
        a.setMustChangePassword(true);
        userDao.update(a);
        log.warn("TEAMBOARD_RESET_ADMIN_PASSWORD 로 admin 비밀번호를 초기화했습니다. 로그인 후 비밀번호를 바꾸고, 환경변수를 지운 뒤 다시 기동하세요.");
    }

    /** 게시판 카테고리가 없으면 기본 게시판 생성 */
    private void insertDefaultCategories() {
        if (postDao.selectCategoryCount() > 0) return;
        String[][] defaults = {
                {"공지사항", "팀 공지 (관리자 작성)", "true"},
                {"자유게시판", "자유롭게 이야기를 나누는 공간", "false"},
                {"자료실", "문서·양식·참고자료 공유", "false"},
        };
        for (int i = 0; i < defaults.length; i++) {
            PostCategory c = new PostCategory();
            c.setName(defaults[i][0]);
            c.setDescription(defaults[i][1]);
            c.setAdminOnlyWrite(Boolean.parseBoolean(defaults[i][2]));
            c.setSortOrder(i + 1);
            postDao.insertCategory(c);
        }
    }
}
