package com.inticube.teamboard.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 점검 1: 이력이 사라지거나 남지 않는 문제 · 감사 로그 */
@DisplayName("이력·감사 로그")
class AuditTrailTest extends ApiTestBase {

    @Test
    @DisplayName("연속으로 고쳐도 변경마다 이력이 남는다 (10분 내 합치기·되돌리면 삭제 없음)")
    void everyChangeIsLogged() {
        Board b = board(admin, Map.of());
        long id = item(admin, b, "이력 원본");
        admin.patch("/api/items/" + id, Map.of("name", "이력 A"));
        admin.patch("/api/items/" + id, Map.of("name", "이력 B"));
        admin.patch("/api/items/" + id, Map.of("name", "이력 원본"));  // 원래대로 되돌려도 지워지면 안 됨
        admin.patch("/api/items/" + id, Map.of("description", "설명 추가"));
        List<String> logs = activity(admin, id);
        assertThat(logs).anyMatch(s -> s.contains("이력 원본 → 이력 A"))
                .anyMatch(s -> s.contains("이력 A → 이력 B"))
                .anyMatch(s -> s.contains("이력 B → 이력 원본"))
                .anyMatch(s -> s.contains("설명"));
    }

    @Test
    @DisplayName("댓글을 지워도 지운 사실이 활동 기록에 남는다")
    void commentDeleteLeavesTrace() {
        Board b = board(admin, Map.of());
        long id = item(admin, b, "댓글");
        long c = admin.post("/api/items/" + id + "/updates", Map.of("content", "지울 댓글")).get("id").asLong();
        assertThat(admin.delete("/api/updates/" + c).ok()).isTrue();
        assertThat(activity(admin, id)).anyMatch(s -> s.startsWith("댓글 삭제"));
    }

    @Test
    @DisplayName("업무 변경·생성이 감사 로그에 남고, 감사 로그는 관리자만 보며 DB에서도 고칠 수 없다")
    void auditLog() {
        Api user = user("audit_viewer");
        Board b = board(admin, Map.of());
        long id = item(admin, b, uniq("감사"));
        admin.patch("/api/items/" + id, Map.of("name", "감사 수정"));
        JsonNode page = admin.get("/api/admin/audit-logs?size=200").body();
        assertThat(page.path("enabled").asBoolean()).isTrue();
        assertThat(page.path("content").findValues("targetId")).extracting(JsonNode::asLong).contains(id);
        assertThat(user.get("/api/admin/audit-logs").status()).isEqualTo(403);
        assertThatThrownBy(() -> jdbc.update("UPDATE tb_audit_log SET detail = 'x'")).hasMessageContaining("tb_audit_log");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM tb_audit_log")).hasMessageContaining("tb_audit_log");
    }

    @Test
    @DisplayName("로그인 실패도 감사 로그에 남는다")
    void loginFailureAudited() {
        new Api(base(), "admin", PW); // 정상 로그인
        try { new Api(base(), "admin", "wrong-password"); } catch (IllegalStateException expected) { /* 실패가 정상 */ }
        JsonNode page = admin.get("/api/admin/audit-logs?action=LOGIN_FAIL&size=10").body();
        assertThat(page.path("total").asLong()).isGreaterThan(0);
    }
}
