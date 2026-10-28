package com.inticube.teamboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class TeamboardApplication {
    public static void main(String[] args) {
        // 서버 OS 시간대와 무관하게 업무 시간대 고정 (환경변수 TEAMBOARD_TZ 로 변경 가능)
        TimeZone.setDefault(TimeZone.getTimeZone(System.getenv().getOrDefault("TEAMBOARD_TZ", "Asia/Seoul")));
        SpringApplication.run(TeamboardApplication.class, args);
    }
}
