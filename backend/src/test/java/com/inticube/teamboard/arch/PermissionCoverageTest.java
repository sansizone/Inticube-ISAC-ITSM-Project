package com.inticube.teamboard.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 규칙 고정: 모든 변경 API(POST·PUT·PATCH·DELETE)는 권한을 검사해야 한다.
 * 컨트롤러 메서드 안에서 guard.* 를 부르거나, /api/admin/** (관리자 전용) 이거나,
 * permission-allowlist.txt 에 '어디서 검사하는지' 근거와 함께 올라 있어야 한다.
 * 새 API 를 만들고 권한 검사를 빠뜨리면 이 테스트가 실패한다.
 */
@DisplayName("변경 API 권한 검사 누락 방지")
class PermissionCoverageTest {
    private static final Pattern MAPPING = Pattern.compile(
            "@(Post|Put|Patch|Delete)Mapping(?:\\((?:value\\s*=\\s*)?\"([^\"]*)\"[^)]*\\))?\\s*(?:@[A-Za-z]+(?:\\([^)]*\\))?\\s*)*public[^{;]*\\{");

    @Test
    void everyMutatingEndpointChecksPermission() throws Exception {
        Set<String> allowed = new HashSet<>();
        for (String line : Files.readAllLines(Paths.get("src/test/resources/permission-allowlist.txt"), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            assertThat(line).as("허용 목록에는 근거(| 뒤)가 있어야 합니다: " + line).contains("|");
            allowed.add(line.substring(0, line.indexOf('|')).trim());
        }
        List<String> missing = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (var files = Files.list(Paths.get("src/main/java/com/inticube/teamboard/web"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String s = Files.readString(f, StandardCharsets.UTF_8);
                if (!s.contains("@RestController")) continue;
                Matcher base = Pattern.compile("@RestController[\\s\\S]*?@RequestMapping\\(\"([^\"]*)\"\\)").matcher(s);
                String prefix = base.find() ? base.group(1) : "";
                Matcher m = MAPPING.matcher(s);
                while (m.find()) {
                    String body = body(s, m.end());
                    String key = m.group(1).toUpperCase() + " " + prefix + (m.group(2) == null ? "" : m.group(2));
                    seen.add(key);
                    if (body.contains("guard.") || key.split(" ")[1].startsWith("/api/admin/")) continue;
                    if (!allowed.contains(key)) missing.add(key + "  (" + f.getFileName() + ")");
                }
            }
        }
        assertThat(missing).as("권한 검사가 보이지 않는 변경 API — 컨트롤러에서 guard 로 검사하거나, 서비스에서 검사한다면 permission-allowlist.txt 에 근거를 추가하세요").isEmpty();
        List<String> stale = allowed.stream().filter(k -> !seen.contains(k)).sorted().toList();
        assertThat(stale).as("permission-allowlist.txt 에 없어진 API 가 남아 있습니다. 지워 주세요").isEmpty();
    }

    private static String body(String s, int start) {
        int depth = 1, i = start;
        while (depth > 0 && i < s.length()) { char c = s.charAt(i++); if (c == '{') depth++; else if (c == '}') depth--; }
        return s.substring(start, i);
    }
}
