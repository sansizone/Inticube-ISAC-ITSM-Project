package com.inticube.teamboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 워크스페이스 자동화 규칙과 양식 보기 설정 JSON 읽기 (DB 컬럼 추가 없이 JSON 에 저장)
 * - 자동화: tb_board.column_config 의 "automations": [{ statusId, action: "group"|"board", groupId, boardId }]
 * - 양식: tb_board_view.config: { groupId, statusId, fields: [{ key, required }] }
 */
public final class Automations {
    private Automations() {}
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Rule(Long statusId, String action, Long groupId, Long boardId) {}
    public record Field(String key, boolean required) {}
    public record Form(Long groupId, Long statusId, List<Field> fields) {}

    public static List<Rule> read(String columnConfig) {
        List<Rule> out = new ArrayList<>();
        if (columnConfig == null || columnConfig.isBlank()) return out;
        try {
            for (JsonNode n : JSON.readTree(columnConfig).path("automations"))
                out.add(new Rule(id(n, "statusId"), n.path("action").asText(""), id(n, "groupId"), id(n, "boardId")));
        } catch (Exception ignore) { /* 잘못된 설정은 규칙 없음으로 */ }
        return out;
    }

    public static Form readForm(String viewConfig) {
        List<Field> fields = new ArrayList<>();
        Long groupId = null, statusId = null;
        if (viewConfig != null && !viewConfig.isBlank()) {
            try {
                JsonNode c = JSON.readTree(viewConfig);
                groupId = id(c, "groupId");
                statusId = id(c, "statusId");
                for (JsonNode f : c.path("fields")) {
                    String key = f.path("key").asText("");
                    if (key.equals("description") || key.matches("\\d{1,18}")) fields.add(new Field(key, f.path("required").asBoolean(false)));
                }
            } catch (Exception ignore) { /* 기본값 */ }
        }
        return new Form(groupId, statusId, fields);
    }

    private static Long id(JsonNode n, String key) {
        JsonNode v = n.path(key);
        if (v.isNumber() && v.canConvertToLong() && v.asLong() > 0) return Long.valueOf(v.asLong());
        if (v.isTextual() && v.asText().matches("\\d{1,18}")) return Long.valueOf(v.asText());
        return null;
    }
}
