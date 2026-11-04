package com.inticube.teamboard.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 점검: 반복 업무 날짜 계산 오류 (월말 밀림, 지난 날짜로 생성) */
@DisplayName("반복 업무 다음 회차 날짜")
class RecurrenceTest extends ApiTestBase {

    /** 마감 due, 규칙 rule 로 times 번 완료했을 때 생긴 회차들의 마감일 */
    List<String> run(String due, String rule, int times) {
        Board b = board(admin, Map.of());
        long id = item(admin, b, "반복");
        admin.patch("/api/items/" + id, Map.of("dueDate", due, "recurRule", rule));
        List<String> out = new ArrayList<>();
        for (int k = 0; k < times; k++) {
            assertThat(admin.patch("/api/items/" + id, Map.of("statusId", b.doneStatus())).ok()).isTrue();
            JsonNode next = null;
            for (JsonNode it : admin.get("/api/boards/" + b.id()).get("items"))
                if (it.path("name").asText().equals("반복") && (next == null || it.path("id").asLong() > next.path("id").asLong())) next = it;
            out.add(next.path("dueDate").asText());
            id = next.path("id").asLong();
        }
        return out;
    }

    @Test
    @DisplayName("매월 31일은 말일로 보정되고 다음 달에 다시 31일로 돌아온다")
    void monthEnd() {
        int y = LocalDate.now().getYear() + 1;
        assertThat(run(y + "-12-31", "MONTHLY:1", 4)).containsExactly((y + 1) + "-01-31", (y + 1) + "-02-" + (LocalDate.of(y + 1, 2, 1).lengthOfMonth()), (y + 1) + "-03-31", (y + 1) + "-04-30");
    }

    @Test
    @DisplayName("마감이 지난 뒤 완료해도 다음 회차는 오늘 이후 (매주·매월)")
    void catchUp() {
        LocalDate today = LocalDate.now();
        assertThat(LocalDate.parse(run(today.minusWeeks(5).toString(), "WEEKLY:1", 1).get(0))).isAfterOrEqualTo(today);
        assertThat(LocalDate.parse(run(today.minusMonths(4).toString(), "MONTHLY:1", 1).get(0))).isAfterOrEqualTo(today);
    }

    @Test
    @DisplayName("매년 2월 29일은 평년에 28일, 윤년에 다시 29일")
    void leapDay() {
        assertThat(run("2028-02-29", "YEARLY:4", 1)).containsExactly("2032-02-29");
        assertThat(run("2028-02-29", "YEARLY:1", 1)).containsExactly("2029-02-28");
    }
}
