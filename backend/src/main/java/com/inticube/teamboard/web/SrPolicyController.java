package com.inticube.teamboard.web;

import com.inticube.teamboard.domain.Board;
import com.inticube.teamboard.service.AccessGuard;
import com.inticube.teamboard.service.AuditService;
import com.inticube.teamboard.service.SrPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** SR 통제 규칙 (워크스페이스별). 조회는 멤버, 변경은 소유자·관리자 */
@RestController
@RequestMapping("/api/boards/{boardId}/sr-policy")
@RequiredArgsConstructor
public class SrPolicyController {
    private final SrPolicyService srPolicy;
    private final AccessGuard guard;
    private final AuditService audit;

    @GetMapping
    public SrPolicyService.Policy get(@PathVariable Long boardId) {
        guard.getBoardChecked(boardId);
        return srPolicy.get(boardId);
    }

    @PutMapping
    public SrPolicyService.Policy put(@PathVariable Long boardId, @RequestBody Map<String, Object> body) {
        Board b = guard.getBoardChecked(boardId);
        guard.setManageChecked(b);
        srPolicy.setPolicy(boardId, body);
        audit.record("BOARD_UPDATE", boardId, b.getName(), "BOARD", boardId, b.getName(),
                "SR 통제 규칙 " + (Boolean.TRUE.equals(body.get("enabled")) ? "사용" : "사용 안 함"));
        return srPolicy.get(boardId);
    }

    /** 이름으로 컬럼을 찾아 연결하고 없는 컬럼(접수일시·영향도 등)은 만들어 규칙을 켬 */
    @PostMapping("/defaults")
    public SrPolicyService.Policy defaults(@PathVariable Long boardId) {
        Board b = guard.getBoardChecked(boardId);
        guard.setManageChecked(b);
        srPolicy.setupDefaults(boardId);
        audit.record("BOARD_UPDATE", boardId, b.getName(), "BOARD", boardId, b.getName(), "SR 통제 규칙 기본값 적용");
        return srPolicy.get(boardId);
    }
}
