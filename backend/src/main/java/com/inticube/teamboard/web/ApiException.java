package com.inticube.teamboard.web;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiException notFound(String what) { return new ApiException(HttpStatus.NOT_FOUND, what + "을(를) 찾을 수 없습니다."); }
    public static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN, "권한이 없습니다."); }
    public static ApiException bad(String msg) { return new ApiException(HttpStatus.BAD_REQUEST, msg); }
}
