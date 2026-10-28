package com.inticube.teamboard.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "teamboard")
@Getter @Setter
public class AppProperties {
    /** 사용자가 없을 때 생성되는 관리자 계정 초기 비밀번호 (최초 로그인 시 변경 강제) */
    private String initAdminPassword = "admin1234!";
    /** 첨부파일 저장 경로 */
    private String uploadDir = "./data/uploads";
    /** 업로드 금지 확장자 */
    private List<String> deniedExtensions = List.of("jsp", "jspx", "php", "asp", "aspx", "exe", "bat", "cmd", "sh", "ps1", "msi", "dll", "js", "html", "htm");
    /** 로그인 실패 허용 횟수 및 잠금 시간(분) */
    private int maxLoginFailures = 5;
    private int lockMinutes = 10;
    /** true 면 모든 사용자가 2단계 인증(OTP 앱)을 설정해야 사용 가능 */
    private boolean mfaRequired = false;
    /** true 면 관리자 계정은 2단계 인증 필수 */
    private boolean mfaRequiredForAdmin = false;
}
