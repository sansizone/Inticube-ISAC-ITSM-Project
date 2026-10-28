package com.inticube.teamboard.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.service.MondayImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** monday.com 엑셀 내보내기 파일 → 새 워크스페이스 이관 */
@RestController
@RequestMapping("/api/import/monday")
@RequiredArgsConstructor
public class MondayImportController {
    private final MondayImportService service;
    private final ObjectMapper json;

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MondayImportService.Preview preview(@RequestParam("file") MultipartFile file) {
        return service.getPreview(file);
    }

    /** options: {"boardName": "...", "columns": {"헤더": "STATUS"...}, "people": {"먼데이 이름": 사용자ID|null}} */
    @PostMapping(value = "/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MondayImportService.Result apply(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "options", required = false) String options,
                                            @AuthenticationPrincipal UserPrincipal me) {
        MondayImportService.Options opt;
        try {
            opt = options == null || options.isBlank() ? new MondayImportService.Options(null, null, null, null, null)
                    : json.readValue(options, MondayImportService.Options.class);
        } catch (Exception e) {
            throw ApiException.bad("가져오기 설정을 읽을 수 없습니다.");
        }
        return service.insertWorkspace(file, opt, me);
    }
}
