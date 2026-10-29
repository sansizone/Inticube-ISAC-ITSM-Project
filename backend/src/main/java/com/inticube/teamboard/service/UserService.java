package com.inticube.teamboard.service;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.dao.AppUserDao;
import com.inticube.teamboard.domain.AppUser;
import com.inticube.teamboard.domain.Role;
import com.inticube.teamboard.web.ApiException;
import com.inticube.teamboard.web.dto.Dtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {
    /** 아이디 규칙: 영문·숫자·._- 3~50자, 또는 이메일 주소(회사 계정, 50자 이내) */
    public static final String USERNAME_PATTERN =
            "[A-Za-z0-9._-]{3,50}|(?=.{3,50}$)[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+";

    private final AppUserDao userDao;
    private final PasswordEncoder encoder;
    private final TotpService totp;
    private final WsGroupService wsGroups;

    public AppUser getUser(Long id) {
        AppUser u = userDao.selectById(id);
        if (u == null) throw ApiException.notFound("사용자");
        return u;
    }

    @Transactional(readOnly = true)
    public MeDto getMe(Long id) {
        AppUser u = getUser(id);
        return MeDto.of(u, totp.getSetupRequired(u));
    }

    public AppUser updatePassword(UserPrincipal me, String current, String next) {
        AppUser u = getUser(me.id());
        if (!encoder.matches(current, u.getPasswordHash()))
            throw ApiException.bad("현재 비밀번호가 일치하지 않습니다.");
        if (encoder.matches(next, u.getPasswordHash()))
            throw ApiException.bad("새 비밀번호가 현재 비밀번호와 같습니다.");
        PasswordPolicy.validate(next, u.getUsername());
        u.setPasswordHash(encoder.encode(next));
        u.setMustChangePassword(false);
        userDao.update(u);
        return u;
    }

    @Transactional(readOnly = true)
    public List<UserSummary> getActiveUsers() {
        return userDao.selectActiveOrderByName().stream().map(UserSummary::of).toList();
    }

    // ---------- 관리자 ----------
    @Transactional(readOnly = true)
    public List<UserAdminDto> getAllUsers() {
        return userDao.selectAllOrderByName().stream().map(UserAdminDto::of).toList();
    }

    public TempPasswordResponse insertUser(UserCreateRequest req) {
        String username = req.username().trim();
        if (!username.matches(USERNAME_PATTERN))
            throw ApiException.bad("아이디는 영문/숫자/._- 3~50자 또는 이메일 주소(50자 이내)만 사용할 수 있습니다.");
        if (userDao.selectExistsByUsername(username))
            throw new ApiException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
        String temp = PasswordPolicy.temporary();
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setName(req.name().trim());
        u.setDepartment(req.department());
        u.setRole(req.role() == null ? Role.MEMBER : req.role());
        u.setPasswordHash(encoder.encode(temp));
        u.setMustChangePassword(true);
        userDao.insert(u);
        wsGroups.insertUserToDefault(u.getId()); // 새 계정은 첫 워크스페이스 그룹 멤버로
        return new TempPasswordResponse(username, temp);
    }

    public UserAdminDto updateUser(Long id, UserUpdateRequest req, UserPrincipal me) {
        AppUser u = getUser(id);
        boolean demoting = req.role() != null && req.role() != Role.ADMIN && u.getRole() == Role.ADMIN;
        boolean deactivating = Boolean.FALSE.equals(req.active()) && u.isActive();
        if (u.getId().equals(me.id()) && (demoting || deactivating))
            throw ApiException.bad("본인 계정의 관리자 권한 해제나 비활성화는 할 수 없습니다.");
        if ((demoting || deactivating) && u.getRole() == Role.ADMIN && userDao.selectActiveAdminCount() <= 1)
            throw ApiException.bad("최소 1명의 활성 관리자가 필요합니다.");
        if (req.username() != null && !req.username().isBlank() && !req.username().trim().equals(u.getUsername())) {
            String username = req.username().trim();
            if (!username.matches(USERNAME_PATTERN))
                throw ApiException.bad("아이디는 영문/숫자/._- 3~50자 또는 이메일 주소(50자 이내)만 사용할 수 있습니다.");
            if (userDao.selectExistsByUsername(username))
                throw new ApiException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
            u.setUsername(username);
        }
        u.setName(req.name().trim());
        u.setDepartment(req.department());
        if (req.role() != null) u.setRole(req.role());
        if (req.active() != null) u.setActive(req.active());
        userDao.update(u);
        return UserAdminDto.of(u);
    }

    /** 비활성 계정 일괄 활성화: 아이디·부서 지정 + 임시 비밀번호 발급 (하나라도 틀리면 전체 취소) */
    public List<BulkActivateResult> batchActivateUsers(BulkActivateRequest req) {
        List<BulkActivateItem> list = req.users() == null ? List.of() : req.users();
        if (list.isEmpty()) throw ApiException.bad("활성화할 사용자를 선택하세요.");
        if (list.size() > 500) throw ApiException.bad("한 번에 500명까지 처리할 수 있습니다.");
        Set<String> seen = new HashSet<>();
        List<BulkActivateResult> out = new ArrayList<>();
        for (BulkActivateItem it : list) {
            AppUser u = getUser(it.id());
            String username = it.username() == null || it.username().isBlank() ? u.getUsername() : it.username().trim();
            if (!username.matches(USERNAME_PATTERN))
                throw ApiException.bad(u.getName() + ": 아이디는 영문/숫자/._- 3~50자 또는 이메일 주소(50자 이내)만 사용할 수 있습니다.");
            if (!seen.add(username.toLowerCase()))
                throw ApiException.bad(u.getName() + ": 아이디 '" + username + "' 가 목록 안에서 중복됩니다.");
            if (!username.equals(u.getUsername()) && userDao.selectExistsByUsername(username))
                throw new ApiException(HttpStatus.CONFLICT, u.getName() + ": 이미 사용 중인 아이디입니다 (" + username + ").");
            u.setUsername(username);
            if (it.department() != null) u.setDepartment(it.department().isBlank() ? null : it.department().trim());
            String temp = PasswordPolicy.temporary();
            u.setPasswordHash(encoder.encode(temp));
            u.setMustChangePassword(true);
            u.setActive(true);
            userDao.update(u);
            out.add(new BulkActivateResult(u.getId(), u.getName(), username, temp));
        }
        return out;
    }

    public TempPasswordResponse updatePasswordReset(Long id) {
        AppUser u = getUser(id);
        String temp = PasswordPolicy.temporary();
        u.setPasswordHash(encoder.encode(temp));
        u.setMustChangePassword(true);
        userDao.update(u);
        return new TempPasswordResponse(u.getUsername(), temp);
    }

    /**
     * 외부 데이터 이관용 비활성 계정 생성 (로그인 불가, 관리자가 아이디 변경·활성화·비밀번호 초기화 후 사용).
     * 아이디는 m0001 형식으로 자동 부여.
     */
    public AppUser insertInactiveUser(String name) {
        int n = userDao.selectMaxImportSeq() + 1;
        String username;
        do { username = String.format("m%04d", n++); } while (userDao.selectExistsByUsername(username));
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setName(name.trim().length() > 50 ? name.trim().substring(0, 50) : name.trim());
        u.setRole(Role.MEMBER);
        u.setActive(false);
        u.setMustChangePassword(true);
        u.setPasswordHash(encoder.encode(PasswordPolicy.temporary()));
        userDao.insert(u);
        wsGroups.insertUserToDefault(u.getId()); // 새 계정은 첫 워크스페이스 그룹 멤버로
        return u;
    }
}
