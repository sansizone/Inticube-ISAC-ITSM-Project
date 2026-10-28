package com.inticube.teamboard.dao;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/** resultType="map" 결과를 안전하게 꺼내는 도우미 (PostgreSQL 은 컬럼 라벨이 소문자) */
public final class Rows {
    private Rows() {}
    public static Long lng(Map<String, Object> r, String k) { Object v = r.get(k); return v == null ? null : ((Number) v).longValue(); }
    public static long lngOr0(Map<String, Object> r, String k) { Long v = lng(r, k); return v == null ? 0 : v; }
    public static Integer integer(Map<String, Object> r, String k) { Object v = r.get(k); return v == null ? null : ((Number) v).intValue(); }
    public static String str(Map<String, Object> r, String k) { Object v = r.get(k); return v == null ? null : v.toString(); }
    public static BigDecimal dec(Map<String, Object> r, String k) {
        Object v = r.get(k);
        if (v == null) return null;
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }
    public static LocalDate date(Map<String, Object> r, String k) {
        Object v = r.get(k);
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(v.toString().substring(0, 10));
    }
    public static LocalDateTime dateTime(Map<String, Object> r, String k) {
        Object v = r.get(k);
        if (v == null) return null;
        if (v instanceof LocalDateTime d) return d;
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        return LocalDateTime.parse(v.toString().replace(' ', 'T'));
    }
}
