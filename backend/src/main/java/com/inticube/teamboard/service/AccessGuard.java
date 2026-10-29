package com.inticube.teamboard.service;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.dao.*;
import com.inticube.teamboard.domain.*;
import com.inticube.teamboard.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

/**
 * 워크스페이스(보드) 접근 확인. 관리자는 전체, 공개 워크스페이스는 모든 사용자, 비공개는 멤버만.
 * 그 안의 그룹·업무·댓글·첨부·컬럼·담당업무·보기도 같은 기준.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccessGuard {
    private final BoardDao boardDao;
    private final BoardPartsDao partsDao;
    private final ItemDao itemDao;
    private final ItemExtraDao extraDao;
    private final BoardDutyDao dutyDao;

    public UserPrincipal getMe() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof UserPrincipal p) return p;
        throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
    }

    /** 내가 볼 수 있는 워크스페이스(보드) ID. 관리자는 null(전체) */
    public Set<Long> getVisibleBoardIds() {
        UserPrincipal me = getMe();
        if (me.isAdmin()) return null;
        Set<Long> ids = new HashSet<>(boardDao.selectPublicIds());
        ids.addAll(partsDao.selectBoardIdsByUser(me.id()));
        return ids;
    }

    public boolean getCanSee(Board b) {
        UserPrincipal me = getMe();
        return me.isAdmin() || b.isPublic() || partsDao.selectMemberExists(b.getId(), me.id());
    }

    public static boolean isVisible(Board b, Set<Long> ids) {
        return ids == null || ids.contains(b.getId());
    }

    /** 설정·컬럼·멤버 변경 권한: 관리자, 만든 사람, OWNER 멤버 */
    public boolean getCanManage(Board b) {
        UserPrincipal me = getMe();
        if (me.isAdmin() || (b.getOwner() != null && me.id().equals(b.getOwner().getId()))) return true;
        BoardMember m = partsDao.selectMember(b.getId(), me.id());
        return m != null && "OWNER".equals(m.getRole());
    }

    /** 업무 삭제: 시스템 관리자, 워크스페이스 관리자, 그 업무를 등록한 사람만 */
    public void setDeleteItemChecked(com.inticube.teamboard.domain.Item i) {
        UserPrincipal me = getMe();
        if (me.isAdmin()) return;
        if (i.getCreatedBy() != null && me.id().equals(i.getCreatedBy().getId())) return;
        if (getCanManage(i.getBoard())) return;
        throw new ApiException(HttpStatus.FORBIDDEN, "'" + i.getName() + "' 은(는) 등록한 사람이나 관리자만 삭제할 수 있습니다.");
    }

    public void setManageChecked(Board b) {
        if (!getCanManage(b)) throw new ApiException(HttpStatus.FORBIDDEN, "워크스페이스 관리자만 변경할 수 있습니다.");
    }

    private Board check(Board b) {
        if (!getCanSee(b)) throw new ApiException(HttpStatus.FORBIDDEN, "이 워크스페이스의 멤버만 볼 수 있습니다.");
        return b;
    }

    public Board getBoardChecked(Long boardId) {
        Board b = boardDao.selectById(boardId);
        if (b == null) throw ApiException.notFound("워크스페이스");
        return check(b);
    }

    public void getGroupChecked(Long groupId) {
        BoardGroup g = partsDao.selectGroupById(groupId);
        if (g == null) throw ApiException.notFound("그룹");
        check(g.getBoard());
    }

    public Item getItemChecked(Long itemId) {
        Item i = itemDao.selectById(itemId);
        if (i == null) throw ApiException.notFound("업무");
        check(i.getBoard());
        return i;
    }

    public void getUpdateChecked(Long updateId) {
        ItemUpdate u = extraDao.selectUpdateById(updateId);
        if (u == null) throw ApiException.notFound("댓글");
        check(u.getItem().getBoard());
    }

    public void getAttachmentChecked(Long attachmentId) {
        Attachment a = extraDao.selectAttachmentById(attachmentId);
        if (a == null) throw ApiException.notFound("첨부파일");
        check(a.getItem().getBoard());
    }

    public BoardColumn getColumnChecked(Long columnId) {
        BoardColumn c = partsDao.selectColumnById(columnId);
        if (c == null) throw ApiException.notFound("컬럼");
        check(c.getBoard());
        return c;
    }

    public void getDutyChecked(Long dutyId) {
        BoardDuty d = dutyDao.selectById(dutyId);
        if (d == null) throw ApiException.notFound("담당업무");
        getBoardChecked(d.getBoard().getId());
    }

    public void getViewChecked(Long viewId) {
        BoardView v = partsDao.selectViewById(viewId);
        if (v == null) throw ApiException.notFound("보기");
        getBoardChecked(v.getBoardId());
    }
}
