package com.pis.api;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiProblemsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> STANDARD = Set.of("type", "title", "status", "detail", "instance", "code", "traceId");

    @Test
    void mvcSerializesARealProblemDetailWithTopLevelExtensions() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/synthetic-secret-id");
        request.setQueryString("token=synthetic-secret-token");
        var response = new MockHttpServletResponse();
        new TraceIdFilter().doFilter(request, response, (req, res) -> {
            ProblemDetail problem = ApiProblems.create(request, HttpStatus.CONFLICT,
                "VERSION_CONFLICT", "The resource version has changed.");
            var output = new MockHttpOutputMessage();
            new JacksonJsonHttpMessageConverter().write(problem, null, output);
            var json = JSON.readTree(output.getBodyAsBytes());
            assertThat(json.propertyNames()).containsExactlyInAnyOrderElementsOf(STANDARD);
            assertThat(json.path("status").intValue()).isEqualTo(409);
            assertThat(json.path("type").stringValue()).isEqualTo("urn:pis:problem:version-conflict");
            assertTrace(json, response);
            assertThat(output.getBodyAsString()).doesNotContain("synthetic-secret", "properties", "stackTrace");
        });
    }

    @Test
    void securityWriterUsesAWhiteListAndPreservesEveryExistingCodeAndMessage() throws Exception {
        for (var entry : new Object[][] {
            {401, "AUTHENTICATION_REQUIRED", "请先登录"},
            {403, "CSRF_INVALID", "请求校验失败"},
            {503, "AUTHENTICATION_UNAVAILABLE", "认证服务暂时不可用"}
        }) {
            var response = new MockHttpServletResponse();
            new TraceIdFilter().doFilter(new MockHttpServletRequest(), response, (req, res) ->
                ApiProblems.write(response, (Integer) entry[0], (String) entry[1], (String) entry[2]));
            JsonNode json = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            assertThat(json.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "detail",
                "instance", "code", "traceId", "message");
            assertThat(response.getStatus()).isEqualTo(entry[0]);
            assertThat(response.getContentType()).startsWith("application/problem+json");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(json.path("code").stringValue()).isEqualTo(entry[1]);
            assertThat(json.path("detail").stringValue()).isEqualTo(entry[2]);
            assertThat(json.path("message").stringValue()).isEqualTo(entry[2]);
            assertTrace(json, response);
        }
    }

    @Test
    void domainFailureRequiresErrorStatusAndStaticCodeSyntax() {
        var error = new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "The version changed.");
        assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error.code()).isEqualTo("VERSION_CONFLICT");
        assertThat(error.safeDetail()).isEqualTo("The version changed.");
        assertThatThrownBy(() -> new ApiException(HttpStatus.OK, "INVALID", "Safe detail"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiException(HttpStatus.BAD_REQUEST, "request supplied text", "Safe detail"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertTrace(JsonNode json, MockHttpServletResponse response) {
        String trace = response.getHeader(TraceIdFilter.HEADER);
        assertThat(json.path("traceId").stringValue()).isEqualTo(trace);
        assertThat(json.path("instance").stringValue()).isEqualTo("urn:uuid:" + trace);
    }
}
