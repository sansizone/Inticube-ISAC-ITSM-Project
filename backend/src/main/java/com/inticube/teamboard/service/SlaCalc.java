package com.inticube.teamboard.service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** SLA 마감 시각 계산. 영업시간 = 평일 09:00~18:00 (프론트 util.js 의 addBusinessHours 와 같은 규칙) */
public final class SlaCalc {
    private SlaCalc() {}
    public static final LocalTime OPEN = LocalTime.of(9, 0);
    public static final LocalTime CLOSE = LocalTime.of(18, 0);

    public static LocalDateTime deadline(LocalDateTime from, int hours, boolean business) {
        if (!business) return from.plusHours(hours);
        LocalDateTime t = from;
        double remain = hours * 60.0;
        while (true) {
            if (t.getDayOfWeek() == DayOfWeek.SATURDAY || t.getDayOfWeek() == DayOfWeek.SUNDAY || !t.toLocalTime().isBefore(CLOSE)) {
                t = t.toLocalDate().plusDays(1).atTime(OPEN);
                continue;
            }
            if (t.toLocalTime().isBefore(OPEN)) t = t.toLocalDate().atTime(OPEN);
            double untilClose = java.time.Duration.between(t, t.toLocalDate().atTime(CLOSE)).toMinutes();
            if (remain <= untilClose) return t.plusMinutes((long) remain);
            remain -= untilClose;
            t = t.toLocalDate().plusDays(1).atTime(OPEN);
        }
    }
}
