package com.inticube.teamboard.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 로그인한 사용자 한 명의 브라우저 역할: 세션·CSRF 쿠키를 들고 API 를 호출 (화면과 같은 경로) */
final class Api {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    final String base, username;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http;

    record Res(int status, JsonNode body, String raw) {
        JsonNode get(String f) { return body.path(f); }
        String message() { return body.path("message").asText(""); }
        boolean ok() { return status >= 200 && status < 300; }
        @Override public String toString() { return status + " " + (raw.length() > 300 ? raw.substring(0, 300) : raw); }
    }

    Api(String base, String username, String password) {
        this.base = base; this.username = username;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
        call("GET", "/api/auth/csrf", null);
        Res r = call("POST", "/api/auth/login", java.util.Map.of("username", username, "password", password));
        if (!r.ok()) throw new IllegalStateException("로그인 실패 " + username + ": " + r);
    }

    private String csrf() {
        for (HttpCookie c : cookies.getCookieStore().getCookies()) if (c.getName().equals("XSRF-TOKEN")) return c.getValue();
        return "";
    }

    Res get(String path) { return call("GET", path, null); }
    Res post(String path, Object body) { return call("POST", path, body); }
    Res put(String path, Object body) { return call("PUT", path, body); }
    Res patch(String path, Object body) { return call("PATCH", path, body); }
    Res delete(String path) { return call("DELETE", path, null); }

    Res call(String method, String path, Object body) {
        try {
            String text = body == null ? null : body instanceof String s ? s : JSON.writeValueAsString(body);
            return raw(method, path, text, "application/json");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** 본문을 그대로 보냄 (깨진 JSON 등 잘못된 요청 재현용) */
    Res raw(String method, String path, String text, String contentType) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("X-XSRF-TOKEN", csrf());
            if (text != null) b.header("Content-Type", contentType);
            b.method(method, text == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8));
            return wrap(http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    Res upload(String path, String filename, byte[] content) {
        try {
            String boundary = "----tb" + UUID.randomUUID();
            var out = new java.io.ByteArrayOutputStream();
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(content);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).header("X-XSRF-TOKEN", csrf())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
            return wrap(http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static Res wrap(HttpResponse<String> r) {
        JsonNode n;
        try { n = r.body() == null || r.body().isBlank() ? MissingNode.getInstance() : JSON.readTree(r.body()); }
        catch (Exception e) { n = MissingNode.getInstance(); }
        return new Res(r.statusCode(), n, r.body() == null ? "" : r.body());
    }
}
