package com.inticube.teamboard.service;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 증빙 파일 검사: 내용 해시(SHA-256), 확장자와 실제 형식 일치 */
public final class Evidence {
    private Evidence() {}

    /** SR 규칙이 켜진 워크스페이스의 최소 증빙 크기 (바이트) */
    public static final long MIN_SIZE = 64;

    public record Inspect(String hash, byte[] head) {}

    public static Inspect inspect(InputStream in) throws IOException {
        try (in) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536], head = new byte[16];
            int n, got = 0;
            while ((n = in.read(buf)) > 0) {
                if (got < 16) { int c = Math.min(16 - got, n); System.arraycopy(buf, 0, head, got, c); got += c; }
                md.update(buf, 0, n);
            }
            byte[] h = got < 16 ? java.util.Arrays.copyOf(head, got) : head;
            return new Inspect(HexFormat.of().formatHex(md.digest()), h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Map<String, String> KIND = Map.ofEntries(
            Map.entry("pdf", "pdf"), Map.entry("png", "png"), Map.entry("jpg", "jpg"), Map.entry("jpeg", "jpg"),
            Map.entry("gif", "gif"), Map.entry("bmp", "bmp"),
            Map.entry("zip", "zip"), Map.entry("docx", "zip"), Map.entry("xlsx", "zip"), Map.entry("pptx", "zip"), Map.entry("hwpx", "zip"),
            Map.entry("doc", "ole"), Map.entry("xls", "ole"), Map.entry("ppt", "ole"), Map.entry("hwp", "ole"), Map.entry("msg", "ole"));
    private static final Set<String> EXEC_OK = Set.of("exe", "dll", "msi", "sys", "com");

    /** 맞지 않으면 사유, 맞으면 null */
    public static String checkType(String name, byte[] head) {
        String ext = FileStorageService.extension(name == null ? "" : name).toLowerCase(Locale.ROOT);
        String actual = detect(head);
        if ("exe".equals(actual) && !EXEC_OK.contains(ext))
            return "확장자(." + ext + ")와 실제 파일 형식(실행 파일)이 다릅니다.";
        String want = KIND.get(ext);
        if (want == null) return null; // 형식을 판별하지 않는 확장자 (txt, csv, log 등)
        if (want.equals(actual)) return null;
        return "확장자(." + ext + ")와 실제 파일 내용이 다릅니다" + (actual == null ? "" : " (실제: " + actual + ")") + ". 원본 파일을 첨부하세요.";
    }

    static String detect(byte[] h) {
        if (starts(h, 0x25, 0x50, 0x44, 0x46)) return "pdf";
        if (starts(h, 0x89, 0x50, 0x4E, 0x47)) return "png";
        if (starts(h, 0xFF, 0xD8, 0xFF)) return "jpg";
        if (starts(h, 0x47, 0x49, 0x46, 0x38)) return "gif";
        if (starts(h, 0x42, 0x4D)) return "bmp";
        if (starts(h, 0x50, 0x4B, 0x03, 0x04) || starts(h, 0x50, 0x4B, 0x05, 0x06)) return "zip";
        if (starts(h, 0xD0, 0xCF, 0x11, 0xE0)) return "ole";
        if (starts(h, 0x4D, 0x5A)) return "exe";
        return null;
    }

    private static boolean starts(byte[] h, int... sig) {
        if (h.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((h[i] & 0xFF) != sig[i]) return false;
        return true;
    }
}
