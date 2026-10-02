package com.pis.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

final class SecurityResponses {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private SecurityResponses() { }
    static void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(JSON.writeValueAsString(Map.of("code", code, "message", message)));
    }
    static void authenticationFailed(HttpServletResponse response) throws IOException {
        error(response, 401, "AUTHENTICATION_FAILED", "用户名或密码不正确，或账号不可用");
    }
}
