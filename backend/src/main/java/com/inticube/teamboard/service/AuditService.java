package com.inticube.teamboard.service;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.dao.AuditLogDao;
import com.inticube.teamboard.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 감사 로그 — 누가, 언제, 무엇을 했는지 영구 보관 (TB_AUDIT_LOG, 수정·삭제 불가).
 * - 모든 기록은 로그 파일(AUDIT)에도 같은 내용을 남긴다
 * - 테이블이 아직 없으면(3.4 업그레이드 SQL 미실행) DB 기록은 건너뛰고 로그 파일에만 남긴다
 * - 호출한 쪽 트랜잭션 안에서 기록하므로, 작업이 실패해 되돌려지면 감사 기록도 함께 되돌려진다
 */
@Slf4j
@Service
@Order(5)
@RequiredArgsConstructor
public class AuditService implements ApplicationRunner {
    private static final org.slf4j.Logger AUDIT = org.slf4j.LoggerFactory.getLogger("AUDIT");
    private static final int DETAIL_MAX = 4000;
    private static final int EXPORT_MAX = 20000;

    private final AuditLogDao dao;
    private volatile boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        try {
            enabled = dao.selectTableCount() > 0;
        } catch (Exception e) {
            enabled = false;
        }
        if (!enabled) log.warn("감사 로그 테이블(tb_audit_log)이 없습니다. deploy/postgres-upgrade-3.4.sql 실행 전까지는 로그 파일(AUDIT)에만 기록합니다.");
    }

    public boolean isEnabled() { return enabled; }

    /** 지금 요청한 사용자 (로그인 정보가 없으면 null) */
    public static UserPrincipal currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof UserPrincipal p ? p : null;
    }

    /** 지금 로그인한 사용자 이름으로 기록 */
    public void record(String action, Long boardId, String boardName, String targetType, Long targetId, String targetName, String detail) {
        Long actorId = null; String actorName = null;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) { actorId = p.id(); actorName = p.name(); }
        recordAs(actorId, actorName, action, boardId, boardName, targetType, targetId, targetName, detail);
    }

    /** 사용자를 직접 지정해 기록 (로그인처럼 아직 인증 정보가 없을 때) */
    public void recordAs(Long actorId, String actorName, String action, Long boardId, String boardName,
                         String targetType, Long targetId, String targetName, String detail) {
        String d = detail == null ? null : (detail.length() > DETAIL_MAX ? detail.substring(0, DETAIL_MAX) + "…" : detail);
        String ip = clientIp();
        AUDIT.info("{} user={} board={} target={}:{} name=\"{}\" detail=\"{}\" ip={}", action, actorId, boardId, targetType, targetId,
                targetName, d == null ? "" : d.replace('\n', ' '), ip);
        if (!enabled) return;
        Map<String, Object> row = new HashMap<>();
        row.put("createdAt", LocalDateTime.now());
        row.put("actorId", actorId);
        row.put("actorName", cut(actorName, 100));
        row.put("action", action);
        row.put("boardId", boardId);
        row.put("boardName", cut(boardName, 200));
        row.put("targetType", targetType);
        row.put("targetId", targetId);
        row.put("targetName", cut(targetName, 300));
        row.put("detail", d);
        row.put("clientIp", ip);
        dao.insert(row);
    }

    // ───────────────────────── 조회

    public record AuditLogDto(Long id, LocalDateTime createdAt, Long actorId, String actorName, String action,
                              Long boardId, String boardName, String targetType, Long targetId, String targetName,
                              String detail, String clientIp) {}
    public record AuditPage(boolean enabled, List<AuditLogDto> content, long total, int page, int size) {}

    public AuditPage getPage(LocalDate from, LocalDate to, Long actorId, String action, String q, int page, int size) {
        if (!enabled) return new AuditPage(false, List.of(), 0, 0, size);
        int sz = Math.max(10, Math.min(size, 200));
        int pg = Math.max(0, page);
        LocalDateTime f = from == null ? null : from.atStartOfDay();
        LocalDateTime t = to == null ? null : to.plusDays(1).atStartOfDay();
        String qq = q == null || q.isBlank() ? null : q.trim();
        long total = dao.selectCount(f, t, actorId, action, qq);
        List<AuditLogDto> list = dao.selectPage(f, t, actorId, action, qq, pg * sz, sz).stream().map(AuditService::toDto).toList();
        return new AuditPage(true, list, total, pg, sz);
    }

    public byte[] getExcel(LocalDate from, LocalDate to, Long actorId, String action, String q) {
        if (!enabled) throw ApiException.bad("감사 로그 테이블이 없습니다. 업그레이드 SQL(3.4)을 먼저 실행하세요.");
        LocalDateTime f = from == null ? null : from.atStartOfDay();
        LocalDateTime t = to == null ? null : to.plusDays(1).atStartOfDay();
        String qq = q == null || q.isBlank() ? null : q.trim();
        List<AuditLogDto> list = dao.selectPage(f, t, actorId, action, qq, 0, EXPORT_MAX).stream().map(AuditService::toDto).toList();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("감사 로그");
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont(); bold.setBold(true); head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex()); head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            String[] cols = {"일시", "사용자", "종류", "워크스페이스", "대상", "내용", "IP"};
            Row h = sh.createRow(0);
            for (int c = 0; c < cols.length; c++) { Cell cell = h.createCell(c); cell.setCellValue(cols[c]); cell.setCellStyle(head); }
            int r = 1;
            for (AuditLogDto a : list) {
                Row row = sh.createRow(r++);
                row.createCell(0).setCellValue(a.createdAt() == null ? "" : a.createdAt().format(fmt));
                row.createCell(1).setCellValue(nz(a.actorName()));
                row.createCell(2).setCellValue(label(a.action()));
                row.createCell(3).setCellValue(nz(a.boardName()));
                row.createCell(4).setCellValue(nz(a.targetName()));
                row.createCell(5).setCellValue(nz(a.detail()));
                row.createCell(6).setCellValue(nz(a.clientIp()));
            }
            int[] widths = {20, 12, 16, 22, 36, 70, 16};
            for (int c = 0; c < widths.length; c++) sh.setColumnWidth(c, widths[c] * 256);
            sh.createFreezePane(0, 1);
            wb.write(out);
            record("AUDIT_EXPORT", null, null, null, null, null, "감사 로그 엑셀 내보내기 " + list.size() + "건");
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 종류 코드 → 화면 표시 이름 (화면 쪽 목록과 같게 유지) */
    public static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("ITEM_CREATE", "업무 생성");
        LABELS.put("ITEM_UPDATE", "업무 변경");
        LABELS.put("ITEM_DELETE", "업무 삭제(휴지통)");
        LABELS.put("ITEM_RESTORE", "업무 복원");
        LABELS.put("ITEM_PURGE", "업무 영구 삭제");
        LABELS.put("ITEM_MOVE", "업무 이동·복제");
        LABELS.put("COMMENT_DELETE", "댓글 삭제");
        LABELS.put("FILE_ADD", "파일 첨부");
        LABELS.put("FILE_DELETE", "파일 삭제");
        LABELS.put("PHONE_REVEAL", "개인정보 열람");
        LABELS.put("BOARD_CREATE", "워크스페이스 생성");
        LABELS.put("BOARD_UPDATE", "워크스페이스 설정");
        LABELS.put("BOARD_MEMBERS", "워크스페이스 멤버");
        LABELS.put("BOARD_DELETE", "워크스페이스 삭제");
        LABELS.put("WSGROUP_CREATE", "그룹 생성");
        LABELS.put("WSGROUP_UPDATE", "그룹 설정");
        LABELS.put("WSGROUP_MEMBERS", "그룹 멤버");
        LABELS.put("WSGROUP_DELETE", "그룹 삭제");
        LABELS.put("USER_CREATE", "사용자 생성");
        LABELS.put("USER_UPDATE", "사용자 변경");
        LABELS.put("USER_PW_RESET", "비밀번호 초기화");
        LABELS.put("USER_PW_CHANGE", "비밀번호 변경");
        LABELS.put("LOGIN", "로그인");
        LABELS.put("LOGIN_FAIL", "로그인 실패");
        LABELS.put("AUDIT_EXPORT", "감사 로그 내보내기");
    }
    public static String label(String action) { return LABELS.getOrDefault(action, action); }

    // ───────────────────────── 내부

    private static AuditLogDto toDto(Map<String, Object> m) {
        return new AuditLogDto(lng(m, "id"), ts(get(m, "created_at", "createdAt")), lng(m, "actor_id", "actorId"),
                str(m, "actor_name", "actorName"), str(m, "action"), lng(m, "board_id", "boardId"), str(m, "board_name", "boardName"),
                str(m, "target_type", "targetType"), lng(m, "target_id", "targetId"), str(m, "target_name", "targetName"),
                str(m, "detail"), str(m, "client_ip", "clientIp"));
    }
    private static Object get(Map<String, Object> m, String... keys) {
        for (String k : keys) { if (m.containsKey(k)) return m.get(k); if (m.containsKey(k.toUpperCase())) return m.get(k.toUpperCase()); }
        return null;
    }
    private static String str(Map<String, Object> m, String... keys) { Object v = get(m, keys); return v == null ? null : v.toString(); }
    private static Long lng(Map<String, Object> m, String... keys) { Object v = get(m, keys); return v instanceof Number n ? n.longValue() : null; }
    private static LocalDateTime ts(Object v) {
        if (v instanceof LocalDateTime l) return l;
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        return null;
    }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String cut(String s, int max) { return s == null || s.length() <= max ? s : s.substring(0, max); }

    private static String clientIp() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
                HttpServletRequest req = sra.getRequest();
                String xff = req.getHeader("X-Forwarded-For");
                String ip = xff != null && !xff.isBlank() ? xff.split(",")[0].trim() : req.getRemoteAddr();
                return ip == null ? null : (ip.length() > 64 ? ip.substring(0, 64) : ip);
            }
        } catch (Exception ignored) { /* 요청 밖(스케줄러 등) */ }
        return null;
    }
}
