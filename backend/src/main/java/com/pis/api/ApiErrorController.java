package com.pis.api;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Container error-dispatch fallback. Never serializes servlet exception/message/path attributes. */
@RestController
public final class ApiErrorController implements ErrorController {
    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        Object rawStatus = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = rawStatus instanceof Integer value ? HttpStatus.resolve(value) : null;
        if (status == null || !status.isError()) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String code = status.is5xxServerError() ? "INTERNAL_ERROR" : switch (status.value()) {
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "INVALID_REQUEST";
        };
        String detail = status.is5xxServerError() ? "An unexpected error occurred."
            : "The request could not be completed.";
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ApiProblems.create(request, status, code, detail));
    }
}
