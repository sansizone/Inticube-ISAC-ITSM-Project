package com.inticube.teamboard.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 테스트 공통: 실제 서버를 임의 포트로 띄우고 HTTP 로 호출한다 (점검 보고서의 재현 절차를 그대로 옮기기 쉬움).
 * 모든 테스트 클래스가 서버·DB 하나를 같이 쓰므로, 각 테스트는 자기 워크스페이스를 새로 만들어 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "teamboard.init-admin-password=" + ApiTestBase.PW,
        "teamboard.upload-dir=target/test-uploads",
        "logging.file.name=target/test-server.log",
        "teamboard.due-reminder-cron=-"
})
abstract class ApiTestBase {
    static final String PW = "Test#Pass1234";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    Api admin;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        TestDb db = TestDb.get();
        r.add("spring.datasource.url", () -> db.url);
        r.add("spring.datasource.username", () -> db.user);
        r.add("spring.datasource.password", () -> db.password);
    }

    @BeforeEach
    void loginAdmin() {
        jdbc.update("UPDATE tb_app_user SET must_change_pw = false WHERE username = 'admin'");
        admin = login("admin");
    }

    String base() { return "http://localhost:" + port; }

    Api login(String username) { return new Api(base(), username, PW); }

    /** 일반 사용자 계정 (없으면 관리자 화면 API 로 만든 뒤 비밀번호를 정해 둠) */
    Api user(String username) {
        Long id = userId(username);
        if (id == null) {
            Api.Res r = admin.post("/api/admin/users", Map.of("username", username, "name", username, "role", "MEMBER"));
            assertThat(r.ok()).as("사용자 생성 " + r).isTrue();
        }
        jdbc.update("UPDATE tb_app_user SET password_hash = ?, must_change_pw = false, is_active = true WHERE username = ?", encoder.encode(PW), username);
        return login(username);
    }

    Long userId(String username) {
        List<Long> l = jdbc.queryForList("SELECT id FROM tb_app_user WHERE username = ?", Long.class, username);
        return l.isEmpty() ? null : l.get(0);
    }

    static String uniq(String prefix) { return prefix + "-" + UUID.randomUUID().toString().substring(0, 8); }

    // ---------- 워크스페이스·업무 픽스처 ----------

    /** 비공개 워크스페이스. members: username → OWNER/MEMBER */
    Board board(Api owner, Map<String, String> members) {
        Api.Res r = owner.post("/api/boards", Map.of("name", uniq("WS"), "isPublic", false));
        assertThat(r.ok()).as("워크스페이스 생성 " + r).isTrue();
        long id = r.get("id").asLong();
        if (!members.isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            list.add(Map.of("userId", userId(owner.username), "role", "OWNER"));
            members.forEach((u, role) -> list.add(Map.of("userId", userId(u), "role", role)));
            Api.Res m = owner.put("/api/boards/" + id + "/members", Map.of("members", list, "isPublic", false));
            assertThat(m.ok()).as("멤버 지정 " + m).isTrue();
        }
        return reload(owner, id);
    }

    Board reload(Api a, long id) { return new Board(a.get("/api/boards/" + id).body()); }

    long item(Api a, Board b, String name) {
        Api.Res r = a.post("/api/groups/" + b.groupId() + "/items", Map.of("name", name));
        assertThat(r.ok()).as("업무 생성 " + r).isTrue();
        return r.get("id").asLong();
    }

    long column(Api a, Board b, String name, String type) {
        Api.Res r = a.post("/api/boards/" + b.id() + "/columns", Map.of("name", name, "type", type));
        assertThat(r.ok()).as("컬럼 생성 " + r).isTrue();
        return r.get("id").asLong();
    }

    /** 업무 활동 기록(시스템 로그) 내용 */
    List<String> activity(Api a, long itemId) {
        List<String> out = new ArrayList<>();
        for (JsonNode u : a.get("/api/items/" + itemId).get("updates")) if (u.path("system").asBoolean()) out.add(u.path("content").asText());
        return out;
    }

    record Board(JsonNode n) {
        long id() { return n.path("id").asLong(); }
        long groupId() { return n.path("groups").get(0).path("id").asLong(); }
        long status(String label) {
            for (JsonNode s : n.path("statuses")) if (s.path("label").asText().equals(label)) return s.path("id").asLong();
            throw new IllegalArgumentException("상태 없음: " + label);
        }
        long doneStatus() {
            for (JsonNode s : n.path("statuses")) if (s.path("done").asBoolean()) return s.path("id").asLong();
            throw new IllegalArgumentException("완료 상태 없음");
        }
        long type(String name) {
            for (JsonNode t : n.path("types")) if (t.path("name").asText().equals(name)) return t.path("id").asLong();
            throw new IllegalArgumentException("유형 없음: " + name);
        }
        long col(String name) {
            for (JsonNode c : n.path("columns")) if (c.path("name").asText().equals(name)) return c.path("id").asLong();
            throw new IllegalArgumentException("컬럼 없음: " + name);
        }
    }
}
