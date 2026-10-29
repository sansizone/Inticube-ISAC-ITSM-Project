package com.inticube.teamboard.service;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 전화번호 컬럼 값 정리.
 * 엑셀에서 숫자로 저장된 번호는 앞자리 0 이 빠지므로(01012345678 → 1012345678) 되살리고,
 * 국내 번호 규칙대로 하이픈을 넣는다. 번호로 보이지 않는 값(내선, 메모 등)은 원래 글자 그대로 둔다.
 * 여러 번호는 쉼표·줄바꿈으로 구분해 각각 정리한다.
 */
public final class PhoneFormat {
    private PhoneFormat() {}

    public static final String HEADER_PATTERN = ".*(전화|휴대|핸드폰|연락처|phone|mobile|\\btel\\b|cell).*";

    public static String format(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        if (v.isEmpty()) return "";
        if (v.matches(".*[,\\n;].*")) {
            return Arrays.stream(v.split("\\s*[,\\n;]\\s*")).map(PhoneFormat::one).filter(s -> !s.isEmpty())
                    .collect(Collectors.joining(", "));
        }
        return one(v);
    }

    private static String one(String v) {
        v = v.trim();
        // 엑셀 숫자 셀이 1.012345678E9 처럼 지수로 온 경우
        if (v.matches("\\d(\\.\\d+)?[eE]\\+?\\d+")) {
            try { v = new java.math.BigDecimal(v).toPlainString(); } catch (NumberFormatException ignored) { /* 그대로 */ }
        }
        if (v.endsWith(".0")) v = v.substring(0, v.length() - 2);
        // 숫자·하이픈·공백·괄호·점·+ 외의 글자가 있으면 번호가 아닌 것으로 보고 그대로 둔다
        if (!v.matches("[\\d\\-\\s().+]+")) return v;
        if (v.startsWith("+")) return v; // 국제번호는 손대지 않음
        String d = v.replaceAll("\\D", "");
        if (d.length() < 7 || d.length() > 11) return v;
        // 앞자리 0 이 빠진 번호 복원: 휴대폰(10…, 11…), 지역번호(2…, 31… 등) 9~10자리
        if (!d.startsWith("0") && (d.length() == 9 || d.length() == 10) && !d.matches("1[5-9]\\d+")) d = "0" + d;
        if (d.startsWith("02")) {
            if (d.length() == 9) return d.substring(0, 2) + "-" + d.substring(2, 5) + "-" + d.substring(5);
            if (d.length() == 10) return d.substring(0, 2) + "-" + d.substring(2, 6) + "-" + d.substring(6);
        }
        if (d.length() == 8 && d.matches("1[5-9]\\d+")) return d.substring(0, 4) + "-" + d.substring(4); // 대표번호 1588-0000
        if (d.length() == 11) return d.substring(0, 3) + "-" + d.substring(3, 7) + "-" + d.substring(7);
        if (d.length() == 10) return d.substring(0, 3) + "-" + d.substring(3, 6) + "-" + d.substring(6);
        return v;
    }
}
