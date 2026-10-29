package com.inticube.teamboard.service;

import com.inticube.teamboard.web.ApiException;

import java.security.SecureRandom;

public final class PasswordPolicy {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String LETTERS = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITS = "23456789";
    private static final String SPECIALS = "!@#$%^&*";

    private PasswordPolicy() {}

    public static final int MIN = 8, MAX = 20;

    /** 8~20자, 영문/숫자/특수문자 각 1자 이상, 같은 문자 3번 연속 금지, 아이디 포함 금지 */
    public static void validate(String password, String username) {
        if (password == null || password.length() < MIN || password.length() > MAX)
            throw ApiException.bad("비밀번호는 " + MIN + "~" + MAX + "자여야 합니다.");
        if (password.matches(".*(.)\\1\\1.*"))
            throw ApiException.bad("같은 문자를 3번 이상 연속으로 쓸 수 없습니다.");
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*") || !password.matches(".*[^A-Za-z0-9].*"))
            throw ApiException.bad("비밀번호는 영문, 숫자, 특수문자를 각각 1자 이상 포함해야 합니다.");
        if (username != null && password.toLowerCase().contains(username.toLowerCase()))
            throw ApiException.bad("비밀번호에 아이디를 포함할 수 없습니다.");
    }

    public static String temporary() {
        StringBuilder sb = new StringBuilder();
        String all = LETTERS + DIGITS + SPECIALS;
        sb.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        sb.append(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        sb.append(SPECIALS.charAt(RANDOM.nextInt(SPECIALS.length())));
        for (int i = 0; i < 9; i++) sb.append(all.charAt(RANDOM.nextInt(all.length())));
        char[] c = sb.toString().toCharArray();
        for (int i = c.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char t = c[i]; c[i] = c[j]; c[j] = t;
        }
        return new String(c);
    }
}
