package com.inticube.teamboard.service;

import com.inticube.teamboard.config.AppProperties;
import com.inticube.teamboard.web.ApiException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Locale;
import java.util.UUID;

/** 첨부파일 디스크 저장소. 저장명은 UUID 로만 구성하여 경로 조작을 차단 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {
    private final AppProperties props;
    private Path root;

    @PostConstruct
    void init() throws IOException {
        root = Paths.get(props.getUploadDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("첨부파일 저장 경로: {}", root);
    }

    public String store(MultipartFile file) {
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extension(original);
        if (props.getDeniedExtensions().contains(ext))
            throw ApiException.bad("업로드할 수 없는 파일 형식입니다: ." + ext);
        String stored = UUID.randomUUID().toString().replace("-", "");
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, resolve(stored), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("파일 저장 실패", e);
        }
        return stored;
    }

    public Resource load(String stored) {
        Path p = resolve(stored);
        if (!Files.exists(p)) throw ApiException.notFound("파일");
        return new FileSystemResource(p);
    }

    public void delete(String stored) {
        try {
            Files.deleteIfExists(resolve(stored));
        } catch (IOException e) {
            log.warn("파일 삭제 실패: {}", stored, e);
        }
    }

    private Path resolve(String stored) {
        if (!stored.matches("[a-f0-9]{32}")) throw ApiException.bad("잘못된 파일 식별자");
        Path p = root.resolve(stored).normalize();
        if (!p.startsWith(root)) throw ApiException.bad("잘못된 파일 경로");
        return p;
    }

    public static String extension(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT).trim();
    }

    /** 표시용 파일명 정리: 경로 구분자 제거 */
    public static String cleanName(String name) {
        if (name == null || name.isBlank()) return "file";
        String n = name.replace("\\", "/");
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\r\\n\\t\"]", "_");
        return n.length() > 255 ? n.substring(n.length() - 255) : n;
    }
}
