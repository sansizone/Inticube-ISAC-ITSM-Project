package com.inticube.teamboard.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(f -> f.getField() + ": " + f.getDefaultMessage()).orElse("입력값이 올바르지 않습니다.");
        return ResponseEntity.badRequest().body(Map.of("message", msg));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("message", "파일 크기 제한을 초과했습니다."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> denied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "권한이 없습니다."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> illegal(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", "입력값이 올바르지 않습니다."));
    }

    /** 없는 API 경로 (예: 서버가 이전 버전이라 새 API 가 없을 때) */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "요청한 기능을 찾을 수 없습니다. 서버를 최신 버전으로 재시작해 주세요."));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> methodNotAllowed(Exception e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of("message", "지원하지 않는 요청입니다. 서버를 최신 버전으로 재시작해 주세요."));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> typeMismatch(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("message", "요청 값 형식이 올바르지 않습니다."));
    }

    // ---------- 잘못된 요청은 400 계열로 (서버 오류 500 으로 보이지 않게) ----------

    /** JSON 문법 오류, 타입이 맞지 않는 값(숫자 자리에 문자 등), 빈 본문 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(Exception e) {
        return bad("요청 본문 형식이 올바르지 않습니다.");
    }

    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.bind.MissingRequestCookieException.class})
    public ResponseEntity<Map<String, String>> missing(Exception e) {
        return bad("필수 요청 값이 빠졌습니다.");
    }

    @ExceptionHandler({org.springframework.web.multipart.MultipartException.class})
    public ResponseEntity<Map<String, String>> multipart(Exception e) {
        return bad("파일 업로드 요청 형식이 올바르지 않습니다.");
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> mediaType(Exception e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Map.of("message", "지원하지 않는 요청 형식입니다."));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Map<String, String>> notAcceptable(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body(Map.of("message", "지원하지 않는 응답 형식입니다."));
    }

    /** @Validated 파라미터 검증, 잘못된 바인딩 */
    @ExceptionHandler({jakarta.validation.ConstraintViolationException.class, org.springframework.validation.BindException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class})
    public ResponseEntity<Map<String, String>> constraint(Exception e) {
        return bad("입력값이 올바르지 않습니다.");
    }

    /** 날짜·시간 형식 오류 */
    @ExceptionHandler(java.time.format.DateTimeParseException.class)
    public ResponseEntity<Map<String, String>> dateFormat(Exception e) {
        return bad("날짜 형식이 올바르지 않습니다. (예: 2026-11-04)");
    }

    /** 본문 값의 자료형이 기대와 다름 (예: values 자리에 문자열). 원인 추적용으로 경고 로그만 */
    @ExceptionHandler({ClassCastException.class, ArithmeticException.class})
    public ResponseEntity<Map<String, String>> wrongShape(Exception e) {
        log.warn("잘못된 요청 값: {}", e.toString());
        return bad("요청 값 형식이 올바르지 않습니다.");
    }

    /** 중복 키·외래 키 등 DB 제약 위반: 동시에 같은 작업을 했거나 이미 지워진 대상 */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> integrity(org.springframework.dao.DataIntegrityViolationException e) {
        log.warn("DB 제약 위반: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "다른 데이터와 충돌했습니다. 새로고침 후 다시 시도해 주세요."));
    }

    /** 잠금 대기 초과·교착 */
    @ExceptionHandler({org.springframework.dao.PessimisticLockingFailureException.class, org.springframework.dao.CannotAcquireLockException.class})
    public ResponseEntity<Map<String, String>> lock(Exception e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "다른 사용자가 같은 항목을 수정하는 중입니다. 잠시 후 다시 시도해 주세요."));
    }

    /** 브라우저가 응답을 받기 전에 연결을 끊음 — 오류 아님 */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void aborted(Exception e) { /* 무시 */ }

    private static ResponseEntity<Map<String, String>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("message", msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unknown(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "서버 오류가 발생했습니다."));
    }
}
