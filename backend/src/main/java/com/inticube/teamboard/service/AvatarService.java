package com.inticube.teamboard.service;

import com.inticube.teamboard.config.AppProperties;
import com.inticube.teamboard.web.ApiException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 프로필 사진. 업로드 경로(teamboard.upload-dir) 아래 avatars/{사용자ID}.png 로 저장.
 * 받은 이미지는 디코딩 후 가운데를 정사각형으로 잘라 256px PNG 로 다시 그려 저장한다(메타데이터·악성 페이로드 제거).
 * DB 컬럼 없이 파일 수정 시각을 버전으로 써서 DTO 에 실어 보낸다(브라우저 캐시 무효화용).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {
    public static final int SIZE = 256;
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    /** 사용자ID → 버전(파일 수정 시각 ms). DTO 정적 팩토리에서 조회 */
    private static final Map<Long, Long> VERSIONS = new ConcurrentHashMap<>();

    private final AppProperties props;
    private Path dir;

    public static Long version(Long userId) { return userId == null ? null : VERSIONS.get(userId); }

    @PostConstruct
    void init() throws IOException {
        dir = Paths.get(props.getUploadDir()).toAbsolutePath().normalize().resolve("avatars");
        Files.createDirectories(dir);
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.matches("\\d+\\.png")) {
                    try { VERSIONS.put(Long.parseLong(n.substring(0, n.length() - 4)), Files.getLastModifiedTime(p).toMillis()); }
                    catch (IOException ignored) { /* 건너뜀 */ }
                }
            });
        }
        log.info("프로필 사진 경로: {} ({}개)", dir, VERSIONS.size());
    }

    public Path file(Long userId) {
        Path p = dir.resolve(userId + ".png");
        if (!VERSIONS.containsKey(userId) || !Files.exists(p)) throw ApiException.notFound("프로필 사진");
        return p;
    }

    public long store(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.bad("이미지 파일을 선택하세요.");
        if (file.getSize() > MAX_BYTES) throw ApiException.bad("프로필 사진은 5MB 이하만 올릴 수 있습니다.");
        BufferedImage src;
        try (InputStream in = file.getInputStream()) {
            src = ImageIO.read(in);
        } catch (IOException e) {
            src = null;
        }
        if (src == null) throw ApiException.bad("이미지를 읽을 수 없습니다. JPG·PNG·GIF 파일을 올려 주세요.");
        if ((long) src.getWidth() * src.getHeight() > 40_000_000L) throw ApiException.bad("이미지 해상도가 너무 큽니다.");

        int side = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - side) / 2, y = (src.getHeight() - side) / 2;
        int out = Math.min(SIZE, side);
        BufferedImage dst = new BufferedImage(out, out, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, out, out, x, y, x + side, y + side, null);
        } finally {
            g.dispose();
        }
        Path target = dir.resolve(userId + ".png");
        Path tmp = dir.resolve(userId + ".png.tmp");
        try {
            ImageIO.write(dst, "png", tmp.toFile());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            long v = Math.max(Files.getLastModifiedTime(target).toMillis(), System.currentTimeMillis());
            VERSIONS.put(userId, v);
            return v;
        } catch (IOException e) {
            throw new IllegalStateException("프로필 사진 저장 실패", e);
        }
    }

    public void delete(Long userId) {
        try {
            Files.deleteIfExists(dir.resolve(userId + ".png"));
        } catch (IOException e) {
            log.warn("프로필 사진 삭제 실패: {}", userId, e);
        }
        VERSIONS.remove(userId);
    }
}
