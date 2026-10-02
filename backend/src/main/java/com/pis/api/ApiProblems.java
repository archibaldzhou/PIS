package com.pis.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

/** Shared RFC 9457 factory. Callers supply constants, never exception text or request data. */
public final class ApiProblems {
    private static final JsonMapper WRITER = JsonMapper.builder().build();

    private ApiProblems() { }

    public static ProblemDetail create(HttpServletRequest request, HttpStatus status,
                                       String code, String safeDetail) {
        return create(TraceIdFilter.traceId(request), status, code, safeDetail);
    }

    private static ProblemDetail create(UUID traceId, HttpStatus status,
                                        String code, String safeDetail) {
        ApiException.requireErrorStatus(status);
        ApiException.requireCode(code);
        Objects.requireNonNull(safeDetail, "safeDetail");
        var problem = ProblemDetail.forStatusAndDetail(status, safeDetail);
        problem.setType(URI.create("urn:pis:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(status.getReasonPhrase());
        // Never echo a path or query that might contain a patient identifier or credential.
        problem.setInstance(URI.create("urn:uuid:" + traceId));
        problem.setProperty("code", code);
        problem.setProperty("traceId", traceId.toString());
        return problem;
    }

    public static void write(HttpServletRequest request, HttpServletResponse response,
                             HttpStatus status, String code, String safeDetail) throws IOException {
        write(response, create(request, status, code, safeDetail));
    }

    /** Adapter for the existing SecurityResponses signature. */
    public static void write(HttpServletResponse response, int status,
                             String code, String safeDetail) throws IOException {
        write(response, create(TraceIdFilter.currentTraceId(), HttpStatus.valueOf(status), code, safeDetail));
    }

    private static void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        // A plain, explicit map is intentional: a raw Jackson mapper does not install MVC's
        // ProblemDetail mixin, and serializing ProblemDetail directly would nest extensions.
        var body = new LinkedHashMap<String, Object>();
        body.put("type", problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("detail", problem.getDetail());
        body.put("instance", problem.getInstance().toString());
        body.put("code", problem.getProperties().get("code"));
        body.put("traceId", problem.getProperties().get("traceId"));
        body.put("message", problem.getDetail()); // Compatibility with existing auth/CSRF clients.
        response.setStatus(problem.getStatus());
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(TraceIdFilter.HEADER, (String) body.get("traceId"));
        response.getOutputStream().write(WRITER.writeValueAsBytes(body));
    }
}
