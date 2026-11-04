package com.inticube.teamboard.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 점검: 잘못된 요청에 500(서버 오류)이 나는 문제 → 4xx 와 안내 메시지 */
@DisplayName("잘못된 요청은 500 이 아니라 4xx")
class BadRequestTest extends ApiTestBase {

    @Test
    void malformedRequests() {
        Board b = board(admin, Map.of());
        long id = item(admin, b, "요청 형식");
        String[][] cases = {
                {"PATCH", "/api/items/" + id, "{"},                                    // 깨진 JSON
                {"PATCH", "/api/items/" + id, "{\"dueDate\":\"2026-13-45\"}"},         // 없는 날짜
                {"PATCH", "/api/items/" + id, "{\"priority\":\"ZZZ\"}"},               // 없는 값
                {"PATCH", "/api/items/" + id, "{\"estimateHours\":\"abc\"}"},
                {"PATCH", "/api/items/" + id, "{\"values\":\"x\"}"},
                {"POST", "/api/ws-groups/reorder", "{}"},                              // 목록 없음
                {"POST", "/api/admin/post-categories/reorder", "{}"},
                {"POST", "/api/boards/" + b.id() + "/groups/reorder", "{\"ids\":null}"},
                {"POST", "/api/items/bulk-delete", "{}"},
                {"POST", "/api/items/" + id + "/time", "{\"hours\":\"abc\"}"},
                {"POST", "/api/groups/" + b.groupId() + "/items", "[]"},
                {"GET", "/api/items/abc", null},
                {"GET", "/api/items/99999999999999999999", null},
                {"GET", "/api/admin/audit-logs?from=bad", null},
        };
        for (String[] c : cases) {
            Api.Res r = admin.raw(c[0], c[1], c[2], "application/json");
            assertThat(r.status()).as(c[0] + " " + c[1] + " " + c[2] + " → " + r).isLessThan(500);
        }
        assertThat(admin.raw("PATCH", "/api/items/" + id, "{", "application/json").message()).isNotBlank();
    }
}
