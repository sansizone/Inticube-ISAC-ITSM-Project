package com.inticube.teamboard.service;

import com.inticube.teamboard.config.AppProperties;
import com.inticube.teamboard.domain.AppUser;
import com.inticube.teamboard.domain.Role;
import com.inticube.teamboard.dao.AppUserDao;
import com.inticube.teamboard.web.ApiException;
import com.inticube.teamboard.web.dto.Dtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/**
 * 2단계 인증 — RFC 6238 TOTP (Google Authenticator, Microsoft Authenticator, Authy 등 호환).
 * 30초 주기, 6자리, SHA1. 외부 라이브러리 없이 구현.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TotpService {
    private static final String ISSUER = "ISAC-PROJECT";
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RND = new SecureRandom();
    private final AppUserDao userDao;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    // ---------- 정책 ----------
    public boolean getSetupRequired(AppUser u) {
        if (u.isTotpEnabled()) return false;
        return props.isMfaRequired() || (props.isMfaRequiredForAdmin() && u.getRole() == Role.ADMIN);
    }

    // ---------- 설정 ----------
    /** 새 비밀키 발급 (아직 활성화 안 됨). 앱에 등록할 otpauth URL 반환 */
    public MfaSetupResponse insertSetup(Long userId) {
        AppUser u = load(userId);
        if (u.isTotpEnabled()) throw ApiException.bad("이미 2단계 인증이 켜져 있습니다. 바꾸려면 먼저 끄세요.");
        byte[] raw = new byte[20];
        RND.nextBytes(raw);
        String secret = base32(raw);
        u.setTotpSecret(secret);
        userDao.update(u);
        String account = u.getUsername();
        String url = "otpauth://totp/" + enc(ISSUER + ":" + account) + "?secret=" + secret + "&issuer=" + enc(ISSUER) + "&algorithm=SHA1&digits=6&period=30";
        return new MfaSetupResponse(secret, url, ISSUER, account);
    }

    /** 앱이 보여 주는 코드로 확인 → 활성화 + 복구 코드 10개 발급 (한 번만 보여 줌) */
    public MfaEnableResponse updateEnable(Long userId, String code) {
        AppUser u = load(userId);
        if (u.isTotpEnabled()) throw ApiException.bad("이미 켜져 있습니다.");
        if (u.getTotpSecret() == null) throw ApiException.bad("먼저 QR 코드를 발급받으세요.");
        if (!verifyTotp(u.getTotpSecret(), code)) throw ApiException.bad("인증 코드가 맞지 않습니다. 앱의 시간이 정확한지 확인하세요.");
        List<String> codes = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < 10; i++) { String c = recoveryCode(); codes.add(c); hashes.add(encoder.encode(c.replace("-", ""))); }
        u.setRecoveryCodes(String.join(",", hashes));
        u.setTotpEnabled(true);
        userDao.update(u);
        return new MfaEnableResponse(codes);
    }

    public void updateDisable(Long userId, String password, String code) {
        AppUser u = load(userId);
        if (!u.isTotpEnabled()) throw ApiException.bad("2단계 인증이 꺼져 있습니다.");
        if (!encoder.matches(password, u.getPasswordHash())) throw new ApiException(HttpStatus.FORBIDDEN, "비밀번호가 맞지 않습니다.");
        if (!getVerified(u, code)) throw new ApiException(HttpStatus.FORBIDDEN, "인증 코드가 맞지 않습니다.");
        if (getSetupRequired(u) || props.isMfaRequired() || (props.isMfaRequiredForAdmin() && u.getRole() == Role.ADMIN))
            throw ApiException.bad("정책상 2단계 인증을 끌 수 없습니다. 기기를 바꾸려면 관리자에게 초기화를 요청하세요.");
        clear(u);
    }

    /** 관리자: 기기 분실 등으로 초기화 (다음 로그인부터 OTP 없이, 정책상 필수면 다시 설정하게 됨) */
    public void updateReset(Long userId) {
        clear(load(userId));
    }

    private void clear(AppUser u) {
        u.setTotpEnabled(false);
        u.setTotpSecret(null);
        u.setRecoveryCodes(null);
        userDao.update(u);
    }

    private AppUser load(Long userId) {
        AppUser u = userDao.selectById(userId);
        if (u == null) throw ApiException.notFound("사용자");
        return u;
    }

    // ---------- 검증 ----------
    /** OTP 코드 또는 복구 코드. 복구 코드는 한 번 쓰면 지움 */
    public boolean getVerified(AppUser user, String code) {
        AppUser u = userDao.selectById(user.getId()); // 영속 상태로 다시 읽어야 복구 코드 소모가 저장됨
        if (u == null || u.getTotpSecret() == null || code == null) return false;
        String c = code.trim().replace(" ", "").replace("-", "");
        if (c.matches("\\d{6}") && verifyTotp(u.getTotpSecret(), c)) return true;
        if (c.length() >= 8 && u.getRecoveryCodes() != null) {
            List<String> hashes = new ArrayList<>(Arrays.asList(u.getRecoveryCodes().split(",")));
            for (Iterator<String> it = hashes.iterator(); it.hasNext();) {
                String h = it.next();
                if (!h.isBlank() && encoder.matches(c.toUpperCase(Locale.ROOT), h)) {
                    it.remove();
                    u.setRecoveryCodes(String.join(",", hashes));
                    userDao.update(u);
                    return true;
                }
            }
        }
        return false;
    }

    public int getRemainingRecoveryCodes(AppUser u) {
        if (u.getRecoveryCodes() == null || u.getRecoveryCodes().isBlank()) return 0;
        return (int) Arrays.stream(u.getRecoveryCodes().split(",")).filter(x -> !x.isBlank()).count();
    }

    /** 현재 시각 ±1 구간(총 90초) 허용 */
    static boolean verifyTotp(String secret, String code) {
        if (code == null || !code.matches("\\d{6}")) return false;
        long t = System.currentTimeMillis() / 1000 / 30;
        byte[] key = base32Decode(secret);
        for (long w = -1; w <= 1; w++) if (totp(key, t + w).equals(code)) return true;
        return false;
    }

    static String totp(byte[] key, long counter) {
        try {
            byte[] msg = new byte[8];
            for (int i = 7; i >= 0; i--) { msg[i] = (byte) (counter & 0xff); counter >>= 8; }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(msg);
            int off = h[h.length - 1] & 0xf;
            int bin = ((h[off] & 0x7f) << 24) | ((h[off + 1] & 0xff) << 16) | ((h[off + 2] & 0xff) << 8) | (h[off + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte b : data) {
            buf = (buf << 8) | (b & 0xff); bits += 8;
            while (bits >= 5) { sb.append(B32.charAt((buf >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) sb.append(B32.charAt((buf << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] base32Decode(String s) {
        s = s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z2-7]", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (char c : s.toCharArray()) {
            buf = (buf << 5) | B32.indexOf(c); bits += 5;
            if (bits >= 8) { out.write((buf >> (bits - 8)) & 0xff); bits -= 8; }
        }
        return out.toByteArray();
    }

    private static String recoveryCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) { if (i == 5) sb.append('-'); sb.append(chars.charAt(RND.nextInt(chars.length()))); }
        return sb.toString();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
}
