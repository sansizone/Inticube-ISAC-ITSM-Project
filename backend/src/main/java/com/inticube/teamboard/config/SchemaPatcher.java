package com.inticube.teamboard.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

/**
 * v1.0 에서 만들어진 DB 호환 패치.
 * v1.0 은 열거형 컬럼에 CHECK 제약이 생성되어 v1.1 의 새 알림 유형(게시판 알림 등) 저장이 실패하므로,
 * 기동 시 해당 CHECK 제약을 찾아 제거한다. 이미 없으면 아무 것도 하지 않는다.
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class SchemaPatcher implements ApplicationRunner {
    private static final String[][] ENUM_COLUMNS = {
            {"TB_NOTIFICATION", "NOTI_TYPE"},
            {"TB_ITEM", "PRIORITY"},
            {"TB_APP_USER", "USER_ROLE"},
    };
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        String product;
        try (Connection c = dataSource.getConnection()) {
            var md = c.getMetaData();
            product = md.getDatabaseProductName().toLowerCase();
            // 접속 확인용 로그 (URL 의 비밀번호 파라미터는 제거)
            log.info("DB 연결 확인: {} {} / {} / 사용자 {}", md.getDatabaseProductName(), md.getDatabaseProductVersion(),
                    md.getURL().replaceAll("(?i)password=[^&;]*", "password=****"), md.getUserName());
        } catch (Exception e) {
            log.warn("DB 종류 확인 실패: {}", e.getMessage());
            return;
        }
        if (product.contains("postgres")) {
            // 3.1: 댓글 수정 시각 — 업그레이드 SQL 을 아직 안 돌린 DB 도 기동되도록 없을 때만 추가
            for (String sql : new String[]{
                    "ALTER TABLE tb_item_update ADD COLUMN IF NOT EXISTS updated_at timestamp",
                    "ALTER TABLE tb_post_comment ADD COLUMN IF NOT EXISTS updated_at timestamp"}) {
                try { jdbc.execute(sql); } catch (Exception e) { log.warn("컬럼 보강 실패 ({}): {}", sql, e.getMessage()); }
            }
        }
        for (String[] tc : ENUM_COLUMNS) {
            try {
                for (String name : findCheckConstraints(product, tc[0], tc[1])) {
                    String sql = "ALTER TABLE " + tc[0] + " DROP CONSTRAINT " + quote(product, name);
                    jdbc.execute(sql);
                    log.info("구버전 CHECK 제약 제거: {}.{} ({})", tc[0], tc[1], name);
                }
            } catch (Exception e) {
                log.warn("CHECK 제약 확인/제거 실패 {}.{}: {}", tc[0], tc[1], e.getMessage());
            }
        }
    }

    private List<String> findCheckConstraints(String product, String table, String column) {
        if (product.contains("oracle")) {
            // NOT NULL 도 'C' 타입이므로 IN ( 조건을 가진 것만
            return jdbc.queryForList(
                    "select c.constraint_name from user_constraints c join user_cons_columns cc " +
                    "on c.constraint_name = cc.constraint_name and c.table_name = cc.table_name " +
                    "where c.constraint_type = 'C' and c.table_name = ? and cc.column_name = ? " +
                    "and upper(c.search_condition_vc) like '% IN %'", String.class, table, column);
        }
        if (product.contains("postgres")) {
            return jdbc.queryForList(
                    "select con.conname from pg_constraint con join pg_class rel on rel.oid = con.conrelid " +
                    "join pg_namespace ns on ns.oid = rel.relnamespace and ns.nspname = current_schema() " +
                    "join pg_attribute att on att.attrelid = rel.oid and att.attnum = any(con.conkey) " +
                    "where con.contype = 'c' and rel.relname = ? and att.attname = ?",
                    String.class, table.toLowerCase(), column.toLowerCase());
        }
        return List.of();
    }

    private static String quote(String product, String name) {
        return product.contains("postgres") ? "\"" + name + "\"" : "\"" + name + "\"";
    }
}
