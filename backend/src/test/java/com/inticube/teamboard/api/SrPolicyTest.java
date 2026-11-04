package com.inticube.teamboard.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** 점검 3~10: SR 통제 (완료 잠금, 접수일시, 합의완료일, 증적 보존, 완료 조건, 생성 상태, 증빙 품질, 시간 기록) */
@DisplayName("SR 통제 규칙")
class SrPolicyTest extends ApiTestBase {
    static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    Board b;
    Api m;

    @BeforeEach
    void setUp() {
        m = user("sr_member");
        Board fresh = board(admin, Map.of("sr_member", "MEMBER"));
        for (String[] c : new String[][]{{"처리완료일", "DATETIME"}, {"합의완료일", "DATE"}, {"장애등급", "DROPDOWN"}, {"작업시간 (H)", "NUMBER"},
                {"접수유형", "DROPDOWN"}, {"작업유형", "DROPDOWN"}}) column(admin, fresh, c[0], c[1]);
        Api.Res r = admin.post("/api/boards/" + fresh.id() + "/sr-policy/defaults", null);
        assertThat(r.ok()).as(r.toString()).isTrue();
        b = reload(admin, fresh.id());
    }

    Api.Res patch(Api a, long id, Map<String, Object> body) { return a.patch("/api/items/" + id, body); }
    Map<String, Object> vals(Object... kv) { Map<String, Object> v = new HashMap<>(); for (int i = 0; i < kv.length; i += 2) v.put(String.valueOf(b.col((String) kv[i])), kv[i + 1]); return Map.of("values", v); }
    String value(long id, String col) { return m.get("/api/items/" + id).get("values").path(String.valueOf(b.col(col))).asText(""); }
    static byte[] png() { byte[] x = new byte[400]; new Random().nextBytes(x); x[0] = (byte) 0x89; x[1] = 'P'; x[2] = 'N'; x[3] = 'G'; return x; }

    /** 완료 조건을 모두 채운 업무 */
    long ready(String type) {
        long id = item(m, b, uniq("SR"));
        patch(m, id, Map.of("typeId", b.type(type)));
        patch(m, id, vals("접수일시", LocalDateTime.now().minusHours(30).format(DT), "접수유형", "전화", "작업유형", "점검", "처리내역", "조치 완료", "영향도", "상", "긴급도", "중"));
        patch(m, id, Map.of("assigneeId", userId("sr_member")));
        assertThat(m.upload("/api/items/" + id + "/attachments", "증빙.png", png()).ok()).isTrue();
        return id;
    }

    @Test
    @DisplayName("4. 접수일시는 미래 불가, 없으면 상태를 바꿀 수 없고, 등록 지연이 자동 계산된다")
    void receivedAt() {
        long id = item(m, b, "접수");
        assertThat(patch(m, id, Map.of("statusId", b.status("처리중"))).status()).isEqualTo(400);
        assertThat(patch(m, id, vals("접수일시", LocalDateTime.now().plusDays(1).format(DT))).status()).isEqualTo(400);
        assertThat(patch(m, id, vals("접수일시", LocalDateTime.now().minusHours(30).format(DT))).ok()).isTrue();
        assertThat(Double.parseDouble(value(id, "등록 지연 (H)"))).isBetween(29.0, 31.0);
        assertThat(patch(m, id, vals("등록 지연 (H)", "0")).status()).as("자동 컬럼 직접 수정").isEqualTo(400);
    }

    @Test
    @DisplayName("7·9. 필수 항목·증빙이 없으면 완료 불가, 장애등급은 영향도×긴급도로 자동 산출")
    void completionRequirements() {
        long id = item(m, b, "완료조건");
        patch(m, id, vals("접수일시", LocalDateTime.now().minusHours(1).format(DT)));
        Api.Res r = patch(m, id, Map.of("statusId", b.doneStatus()));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.message()).contains("접수유형").contains("처리내역");
        long ok = ready("장애");
        assertThat(value(ok, "장애등급")).isEqualTo("2등급");
        assertThat(patch(m, ok, vals("장애등급", "1등급")).status()).isEqualTo(400);
        assertThat(patch(m, ok, vals("영향도", "아주큼")).status()).isEqualTo(400);
        long noFile = item(m, b, "증빙없음");
        patch(m, noFile, vals("접수일시", LocalDateTime.now().minusHours(1).format(DT), "접수유형", "전화", "작업유형", "점검", "처리내역", "완료"));
        patch(m, noFile, Map.of("assigneeId", userId("sr_member")));
        assertThat(patch(m, noFile, Map.of("statusId", b.doneStatus())).message()).contains("증빙");
    }

    @Test
    @DisplayName("3. 완료하면 처리완료일이 서버 시각으로 기록되고, 완료 SR은 재개(사유 필수) 외에 수정 불가")
    void doneLock() {
        long id = ready("요청");
        assertThat(patch(m, id, Map.of("statusId", b.doneStatus())).ok()).isTrue();
        assertThat(value(id, "처리완료일")).startsWith(LocalDate.now().toString());
        assertThat(patch(m, id, vals("처리완료일", "2020-01-01 00:00")).status()).isEqualTo(400);
        assertThat(patch(m, id, Map.of("name", "고침")).status()).isEqualTo(400);
        assertThat(patch(m, id, Map.of("description", "고침")).status()).isEqualTo(400);
        assertThat(m.post("/api/items/" + id + "/time", Map.of("hours", 1)).status()).isEqualTo(400);
        long att = m.get("/api/items/" + id).get("attachments").get(0).path("id").asLong();
        assertThat(m.delete("/api/attachments/" + att).status()).isEqualTo(400);
        assertThat(patch(m, id, Map.of("statusId", b.status("처리중"))).status()).as("사유 없는 재개").isEqualTo(400);
        assertThat(patch(m, id, Map.of("statusId", b.status("처리중"), "reopenReason", "고객 재요청")).ok()).isTrue();
        assertThat(value(id, "처리완료일")).isEmpty();
        assertThat(activity(m, id)).anyMatch(s -> s.contains("재개 사유: 고객 재요청"));
    }

    @Test
    @DisplayName("5. 합의완료일 변경은 사유 필수, 최초 합의일은 따로 보존")
    void agreedDate() {
        long id = item(m, b, "합의");
        assertThat(patch(m, id, vals("합의완료일", "2026-12-10")).ok()).isTrue();
        assertThat(patch(m, id, vals("합의완료일", "2026-12-20")).status()).isEqualTo(400);
        Map<String, Object> body = new HashMap<>(vals("합의완료일", "2026-12-20"));
        body.put("changeReason", "고객 요청");
        assertThat(patch(m, id, body).ok()).isTrue();
        assertThat(value(id, "최초 합의완료일")).isEqualTo("2026-12-10");
    }

    @Test
    @DisplayName("6. 댓글 수정·삭제, 첨부·시간 기록 삭제는 원본을 보존한다")
    void preserve() {
        long id = item(m, b, "보존");
        long c = m.post("/api/items/" + id + "/updates", Map.of("content", "원본 댓글")).get("id").asLong();
        m.put("/api/updates/" + c, Map.of("content", "수정 댓글"));
        assertThat(m.get("/api/updates/" + c + "/revisions").body().get(0).path("content").asText()).isEqualTo("원본 댓글");
        m.delete("/api/updates/" + c);
        JsonNode u = null;
        for (JsonNode x : m.get("/api/items/" + id).get("updates")) if (x.path("id").asLong() == c) u = x;
        assertThat(u).isNotNull();
        assertThat(u.path("deleted").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT content FROM tb_item_update WHERE id = ?", String.class, c)).isEqualTo("수정 댓글");
        long att = m.upload("/api/items/" + id + "/attachments", "a.png", png()).get("id").asLong();
        assertThat(m.delete("/api/attachments/" + att).ok()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_attachment WHERE id = ? AND deleted_at IS NOT NULL", Long.class, att)).isEqualTo(1L);
        long t = m.post("/api/items/" + id + "/time", Map.of("hours", 1)).get("id").asLong();
        assertThat(m.delete("/api/time/" + t).ok()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_time_entry WHERE id = ? AND deleted_at IS NOT NULL", Long.class, t)).isEqualTo(1L);
    }

    @Test
    @DisplayName("8. 복제본은 '접수'(없으면 첫) 상태로 만들어지고 자동 값은 복사되지 않으며, 엑셀 가져오기는 관리자만")
    void copyAndImport() {
        long id = ready("요청");
        patch(m, id, Map.of("statusId", b.doneStatus()));
        assertThat(m.post("/api/items/bulk-copy", Map.of("ids", new long[]{id})).ok()).isTrue();
        JsonNode copy = null;
        for (JsonNode it : m.get("/api/boards/" + b.id()).get("items")) if (it.path("name").asText().endsWith("(복사)")) copy = it;
        assertThat(copy).isNotNull();
        assertThat(copy.path("statusId").asLong()).as("첫 상태(접수)").isEqualTo(b.n().path("statuses").get(0).path("id").asLong());
        assertThat(copy.path("values").path(String.valueOf(b.col("처리완료일"))).asText("")).isEmpty();
        assertThat(m.upload("/api/boards/" + b.id() + "/excel/import?dryRun=true", "a.xlsx", new byte[100]).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("9. 확장자와 내용이 다른 파일·너무 작은 파일은 거부, 같은 내용이면 경고")
    void evidenceQuality() {
        long id = item(m, b, "증빙");
        assertThat(m.upload("/api/items/" + id + "/attachments", "가짜.pdf", new byte[200]).status()).isEqualTo(400);
        assertThat(m.upload("/api/items/" + id + "/attachments", "작음.txt", "hi".getBytes()).status()).isEqualTo(400);
        byte[] f = png();
        assertThat(m.upload("/api/items/" + id + "/attachments", "1.png", f).get("warning").isNull()).isTrue();
        assertThat(m.upload("/api/items/" + id + "/attachments", "2.png", f).get("warning").asText()).contains("같은 내용");
    }

    @Test
    @DisplayName("10. 시간 기록: 미래 날짜·1건 상한·1인 1일 상한, 작업시간 = 합계")
    void timeEntries() {
        long id = item(m, b, "시간");
        String day = LocalDate.now().minusDays(3).toString();
        assertThat(m.post("/api/items/" + id + "/time", Map.of("hours", 1, "workDate", LocalDate.now().plusDays(1).toString())).status()).isEqualTo(400);
        assertThat(m.post("/api/items/" + id + "/time", Map.of("hours", 13, "workDate", day)).status()).isEqualTo(400);
        assertThat(m.post("/api/items/" + id + "/time", Map.of("hours", 10, "workDate", day)).ok()).isTrue();
        assertThat(m.post("/api/items/" + id + "/time", Map.of("hours", 7, "workDate", day)).status()).isEqualTo(400);
        assertThat(m.post("/api/items/" + id + "/time", Map.of("hours", 2.5, "workDate", day)).ok()).isTrue();
        assertThat(value(id, "작업시간 (H)")).isEqualTo("12.5");
    }
}
