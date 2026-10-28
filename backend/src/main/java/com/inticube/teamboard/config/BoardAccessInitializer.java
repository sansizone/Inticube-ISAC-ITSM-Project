package com.inticube.teamboard.config;

import com.inticube.teamboard.dao.BoardDao;
import com.inticube.teamboard.dao.BoardPartsDao;
import com.inticube.teamboard.domain.Board;
import com.inticube.teamboard.domain.BoardMember;
import com.inticube.teamboard.domain.BoardView;
import com.inticube.teamboard.service.BoardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 기동 시 데이터 이관 (스키마 변경은 하지 않음 — DDL 은 deploy/postgres-upgrade-*.sql 로 먼저 적용).
 * <ul>
 *   <li>v2.0: '워크스페이스 묶음' 제거 → 각 워크스페이스(보드)가 직접 멤버·공개 여부를 가진다.
 *       v1.9 기본 워크스페이스에 있던 보드, 또는 v1.8 이하 보드 → 모든 사용자 공개,
 *       그 밖의 v1.9 워크스페이스에 있던 보드 → 비공개, 그 워크스페이스 멤버를 보드 멤버로 복사.
 *       만든 사람은 OWNER 멤버. 보기 탭이 없는 보드에는 기본 탭 생성</li>
 *   <li>v3.0: 키 접두어·업무 번호·기본 유형·로드맵/위키 탭</li>
 * </ul>
 * 구 테이블(TB_WORKSPACE, TB_WORKSPACE_MEMBER)과 TB_BOARD.WORKSPACE_ID 는 읽기만 하고 남겨 둔다.
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class BoardAccessInitializer implements ApplicationRunner {
    private final BoardDao boardDao;
    private final BoardPartsDao partsDao;
    private final BoardService boardService;
    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Board> pending = boardDao.selectPublicNull();
        if (!pending.isEmpty()) {
            boolean legacy = exists("select count(*) from information_schema.tables where table_schema = current_schema() and table_name = 'tb_workspace_member'")
                    && exists("select count(*) from information_schema.columns where table_schema = current_schema() and table_name = 'tb_board' and column_name = 'workspace_id'");
            int pub = 0, priv = 0;
            for (Board b : pending) {
                Long wsId = null;
                boolean def = true;
                if (legacy) {
                    wsId = jdbc.queryForObject("select workspace_id from tb_board where id = ?", Long.class, b.getId());
                    if (wsId != null) {
                        List<Boolean> d = jdbc.queryForList("select is_default from tb_workspace where id = ?", Boolean.class, wsId);
                        def = d.isEmpty() || Boolean.TRUE.equals(d.get(0));
                    }
                }
                Set<Long> added = new HashSet<>();
                if (!def) {
                    for (Map<String, Object> r : jdbc.queryForList("select user_id, member_role from tb_workspace_member where workspace_id = ?", wsId)) {
                        Long uid = ((Number) r.get("user_id")).longValue();
                        if (added.add(uid)) member(b.getId(), uid, (String) r.get("member_role"));
                    }
                }
                Long owner = b.getOwner() == null ? null : b.getOwner().getId();
                if (owner != null) {
                    if (added.add(owner)) member(b.getId(), owner, "OWNER");
                    else {
                        BoardMember m = partsDao.selectMember(b.getId(), owner);
                        if (m != null) partsDao.updateMemberRole(m.getId(), "OWNER");
                    }
                }
                b.setPublicBoard(def);
                boardDao.update(b);
                if (def) pub++; else priv++;
            }
            log.info("v2.0 워크스페이스 접근 설정 이관: 공개 {}개, 멤버 전용 {}개", pub, priv);
        }
        // 예전 가져오기 때 자동으로 넣은 설명 문구 제거
        int cleared = boardDao.updateDescriptionNull("monday.com 에서 가져옴");
        if (cleared > 0) log.info("가져오기 자동 설명 문구 제거: 워크스페이스 {}개", cleared);
        Set<Long> withViews = new HashSet<>(partsDao.selectBoardIdsWithViews());
        int seeded = 0;
        List<Board> all = boardDao.selectAll();
        for (Board b : all) if (!withViews.contains(b.getId())) { boardService.insertDefaultViews(b.getId()); seeded++; }
        if (seeded > 0) log.info("기본 보기 탭 생성: 워크스페이스 {}개", seeded);

        // v3.0: 키 접두어·업무 번호·기본 유형·로드맵/위키 탭
        Set<Long> withTypes = new HashSet<>(partsDao.selectBoardIdsWithTypes());
        int keyed = 0, typed = 0, tabs = 0;
        for (Board b : all) {
            if (b.getKeyPrefix() == null || b.getKeyPrefix().isBlank()) {
                b.setKeyPrefix(boardService.getUniquePrefix(BoardService.getSuggestedPrefix(b.getName(), b.getId())));
                boardDao.update(b);
            }
            keyed += boardService.batchAssignKeys(b);
            if (!withTypes.contains(b.getId())) { boardService.insertDefaultTypes(b); typed++; }
            List<BoardView> vs = partsDao.selectViewsByBoard(b.getId());
            for (String[] nv : new String[][]{{"로드맵", "roadmap"}, {"위키", "wiki"}}) {
                if (vs.stream().noneMatch(v -> v.getType().equals(nv[1]))) {
                    BoardView v = new BoardView();
                    v.setBoardId(b.getId()); v.setName(nv[0]); v.setType(nv[1]); v.setSortOrder(partsDao.selectViewMaxSort(b.getId()) + 1);
                    partsDao.insertView(v); tabs++;
                }
            }
        }
        if (keyed + typed + tabs > 0) log.info("v3.0 이관: 업무 키 {}건 부여, 기본 유형 생성 {}개, 보기 탭 추가 {}개", keyed, typed, tabs);
    }

    private void member(Long boardId, Long userId, String role) {
        boardService.insertMember(boardId, userId, "OWNER".equals(role) ? "OWNER" : "MEMBER");
    }

    private boolean exists(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n != null && n > 0;
    }
}
