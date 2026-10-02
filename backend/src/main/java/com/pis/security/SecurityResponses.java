package com.pis.security;

import com.pis.api.ApiProblems;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

final class SecurityResponses {
    private SecurityResponses() { }
    static void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        ApiProblems.write(response, status, code, message);
    }
    static void authenticationFailed(HttpServletResponse response) throws IOException {
        error(response, 401, "AUTHENTICATION_FAILED", "用户名或密码不正确，或账号不可用");
    }
}
