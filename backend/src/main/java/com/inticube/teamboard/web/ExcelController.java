package com.inticube.teamboard.web;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.service.BoardService;
import com.inticube.teamboard.service.ExcelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/boards/{boardId}/excel")
@RequiredArgsConstructor
public class ExcelController {
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final ExcelService excelService;
    private final BoardService boardService;
    private final com.inticube.teamboard.service.AccessGuard guard;

    /** template=true: 빈 양식, false: 현재 업무 포함 */
    @GetMapping
    public ResponseEntity<byte[]> export(@PathVariable Long boardId, @RequestParam(defaultValue = "false") boolean template,
                                         @RequestParam(required = false) java.util.List<Long> ids) {
        guard.getBoardChecked(boardId);
        byte[] body = excelService.getExport(boardId, template, ids == null || ids.isEmpty() ? null : new java.util.HashSet<>(ids));
        String boardName = boardService.getBoard(boardId).getName().replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
        String name = boardName + (template ? "_업무양식.xlsx" : (ids != null && !ids.isEmpty() ? "_선택업무_" : "_업무_") + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(XLSX)
                .body(body);
    }

    /** dryRun=true 면 검증·미리보기만, false 면 오류가 없을 때 저장 */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExcelService.ImportResult importTasks(@PathVariable Long boardId, @RequestParam("file") MultipartFile file,
                                                 @RequestParam(defaultValue = "true") boolean dryRun,
                                                 @AuthenticationPrincipal UserPrincipal me) {
        guard.getBoardChecked(boardId);
        return excelService.batchImport(boardId, file, dryRun, me);
    }
}
