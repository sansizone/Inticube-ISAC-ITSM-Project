package com.inticube.teamboard.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 점검: 동시 수정 시 변경이 사라지는 문제 */
@DisplayName("동시 수정")
class ConcurrentEditTest extends ApiTestBase {

    @Test
    @DisplayName("서로 다른 항목을 동시에 고치고 댓글·시간 기록을 달아도 어느 변경도 사라지지 않는다")
    void noLostUpdate() {
        Api m = user("conc_member");
        Board b = board(admin, Map.of("conc_member", "MEMBER"));
        long id = item(admin, b, "동시");
        String day = LocalDate.now().minusDays(1).toString();
        for (int round = 0; round < 10; round++) {
            String name = "동시-" + round, due = LocalDate.now().plusDays(10 + round).toString(), pr = List.of("HIGH", "LOW", "MEDIUM").get(round % 3);
            int r = round;
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(() -> admin.patch("/api/items/" + id, Map.of("name", name))),
                    CompletableFuture.runAsync(() -> m.patch("/api/items/" + id, Map.of("dueDate", due))),
                    CompletableFuture.runAsync(() -> admin.patch("/api/items/" + id, Map.of("priority", pr))),
                    CompletableFuture.runAsync(() -> m.post("/api/items/" + id + "/updates", Map.of("content", "c" + r))),
                    CompletableFuture.runAsync(() -> admin.post("/api/items/" + id + "/time", Map.of("hours", 0.1, "workDate", day)))
            ).join();
            var it = admin.get("/api/items/" + id).get("item");
            assertThat(it.path("name").asText()).as("round " + round).isEqualTo(name);
            assertThat(it.path("dueDate").asText()).as("round " + round).isEqualTo(due);
            assertThat(it.path("priority").asText()).as("round " + round).isEqualTo(pr);
        }
    }

    @Test
    @DisplayName("편집하는 사이 다른 사람이 같은 칸을 고쳤으면 덮어쓰지 않고 409")
    void staleOverwriteRejected() {
        Api m = user("conc_member2");
        Board b = board(admin, Map.of("conc_member2", "MEMBER"));
        long id = item(admin, b, "설명 충돌");
        admin.patch("/api/items/" + id, Map.of("description", "원본"));
        assertThat(m.patch("/api/items/" + id, Map.of("description", "B 수정", "base", Map.of("description", "원본"))).ok()).isTrue();
        assertThat(admin.patch("/api/items/" + id, Map.of("description", "A 수정", "base", Map.of("description", "원본"))).status()).isEqualTo(409);
        assertThat(admin.get("/api/items/" + id).get("description").asText()).isEqualTo("B 수정");
        assertThat(admin.patch("/api/items/" + id, Map.of("description", "A 수정", "base", Map.of("description", "B 수정"))).ok()).isTrue();
    }
}
