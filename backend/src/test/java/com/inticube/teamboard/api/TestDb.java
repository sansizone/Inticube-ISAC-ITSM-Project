package com.inticube.teamboard.api;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 테스트용 빈 DB. 운영과 같은 절차로 만든다: deploy/postgres-teamboard-schema.sql → postgres-upgrade-(3.0 이후).sql 순서.
 * - 기본: 내장 PostgreSQL(zonky) 을 띄움 (설치 불필요, root 계정으로는 실행 불가)
 * - TEST_DB_URL(관리 계정으로 접속할 기존 PostgreSQL, 예: jdbc:postgresql://localhost:5432/postgres)
 *   + TEST_DB_USER / TEST_DB_PASSWORD 를 주면 그 서버에 임시 DB(isac_test_시각)를 만들어 씀
 */
final class TestDb {
    private static TestDb instance;
    final String url, user, password;

    private TestDb(String url, String user, String password) { this.url = url; this.user = user; this.password = password; }

    static synchronized TestDb get() {
        if (instance == null) instance = create();
        return instance;
    }

    private static TestDb create() {
        try {
            String adminUrl = System.getenv("TEST_DB_URL"), user, pw;
            if (adminUrl != null && !adminUrl.isBlank()) {
                user = System.getenv().getOrDefault("TEST_DB_USER", "postgres");
                pw = System.getenv().getOrDefault("TEST_DB_PASSWORD", "");
            } else {
                EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { pg.close(); } catch (Exception ignore) { } }));
                adminUrl = pg.getJdbcUrl("postgres", "postgres");
                user = "postgres"; pw = "postgres";
            }
            String db = "isac_test_" + System.currentTimeMillis();
            try (Connection c = DriverManager.getConnection(adminUrl, user, pw); Statement st = c.createStatement()) {
                st.execute("CREATE DATABASE " + db + " ENCODING 'UTF8' TEMPLATE template0");
            }
            String admin = adminUrl, u = user, p = pw;
            // 끝나면 임시 DB 삭제 (외부 서버에 테스트 DB 가 쌓이지 않게. 내장 DB 는 통째로 사라짐)
            if (System.getenv("TEST_DB_URL") != null) Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (Connection c = DriverManager.getConnection(admin, u, p); Statement st = c.createStatement()) {
                    st.execute("DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
                } catch (Exception ignore) { /* 다음에 수동 정리 */ }
            }));
            String dbUrl = adminUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
            try (Connection c = DriverManager.getConnection(dbUrl, user, pw); Statement st = c.createStatement()) {
                for (Path f : scripts()) st.execute(Files.readString(f));  // pgjdbc 가 여러 문장·$$ 함수 본문을 처리
            }
            String sep = dbUrl.contains("?") ? "&" : "?";
            return new TestDb(dbUrl + sep + "currentSchema=teamboard", user, pw);
        } catch (Exception e) {
            throw new IllegalStateException("테스트 DB 준비 실패: " + e.getMessage(), e);
        }
    }

    /** 스키마 + 3.0 이후 업그레이드 (버전 순) */
    static List<Path> scripts() throws Exception {
        Path dir = Paths.get("..", "deploy");
        if (!Files.isDirectory(dir)) dir = Paths.get("deploy");
        Pattern v = Pattern.compile("postgres-upgrade-(\\d+)\\.(\\d+)\\.sql");
        List<Path> ups;
        try (var s = Files.list(dir)) {
            ups = s.filter(p -> { Matcher m = v.matcher(p.getFileName().toString()); return m.matches() && ver(m) > 300; })
                    .sorted(Comparator.comparingInt(p -> { Matcher m = v.matcher(p.getFileName().toString()); m.matches(); return ver(m); }))
                    .toList();
        }
        List<Path> all = new java.util.ArrayList<>();
        all.add(dir.resolve("postgres-teamboard-schema.sql"));
        all.addAll(ups);
        return all;
    }
    private static int ver(Matcher m) { return Integer.parseInt(m.group(1)) * 100 + Integer.parseInt(m.group(2)); }
}
