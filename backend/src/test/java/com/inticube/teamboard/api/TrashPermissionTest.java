package com.inticube.teamboard.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 점검 2: 업무 삭제·이동 권한, 영구 삭제 대신 휴지통 */
@DisplayName("업무 삭제·이동 권한과 휴지통")
class TrashPermissionTest extends ApiTestBase {

    @Test
    @DisplayName("일반 멤버는 남이 등록한 업무를 삭제·다른 워크스페이스로 이동할 수 없다")
    void memberCannotRemoveOthersItem() {
        Api m = user("trash_member");
        Board b = board(admin, Map.of("trash_member", "MEMBER"));
        Board other = board(m, Map.of());
        long id = item(admin, b, "남의 업무");
        assertThat(m.delete("/api/items/" + id + "?reason=test").status()).isEqualTo(403);
        assertThat(m.post("/api/items/bulk-delete", Map.of("ids", new long[]{id}, "reason", "test")).status()).isEqualTo(403);
        assertThat(m.post("/api/items/" + id + "/move", Map.of("boardId", other.id(), "groupId", other.groupId())).status()).isEqualTo(403);
        assertThat(m.post("/api/items/bulk-move", Map.of("ids", new long[]{id}, "boardId", other.id(), "groupId", other.groupId())).status()).isEqualTo(403);
        assertThat(admin.get("/api/items/" + id).ok()).isTrue();
    }

    @Test
    @DisplayName("삭제는 사유가 필수이고, 휴지통으로 가서 관리자가 복원할 수 있다")
    void softDeleteAndRestore() {
        Api m = user("trash_owner");
        Board b = board(admin, Map.of("trash_owner", "MEMBER"));
        long id = item(m, b, "내 업무");
        assertThat(m.delete("/api/items/" + id).status()).isEqualTo(400);           // 사유 없음
        assertThat(m.delete("/api/items/" + id + "?reason=중복").ok()).isTrue();     // 등록한 사람은 삭제 가능
        assertThat(m.get("/api/items/" + id).status()).isEqualTo(404);             // 목록·상세에서 사라짐
        Long deletedAt = jdbc.queryForObject("SELECT COUNT(*) FROM tb_item WHERE id = ? AND deleted_at IS NOT NULL", Long.class, id);
        assertThat(deletedAt).as("DB에는 남아 있어야 함 (영구 삭제 금지)").isEqualTo(1L);
        assertThat(m.get("/api/admin/trash").status()).isEqualTo(403);             // 휴지통은 관리자만
        assertThat(admin.get("/api/admin/trash?size=500").get("content").findValues("id")).anyMatch(n -> n.asLong() == id);
        assertThat(admin.post("/api/admin/trash/" + id + "/restore", null).ok()).isTrue();
        assertThat(m.get("/api/items/" + id).ok()).isTrue();
    }
}
