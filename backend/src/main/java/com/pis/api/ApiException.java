package com.pis.api;

import java.util.Objects;
import org.springframework.http.HttpStatus;

/** An intentional API failure. All arguments must be static, safe application text. */
public final class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String safeDetail;

    public ApiException(HttpStatus status, String code, String safeDetail) {
        super(Objects.requireNonNull(safeDetail, "safeDetail"));
        this.status = requireErrorStatus(status);
        this.code = requireCode(code);
        this.safeDetail = safeDetail;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String safeDetail() { return safeDetail; }

    static HttpStatus requireErrorStatus(HttpStatus status) {
        Objects.requireNonNull(status, "status");
        if (!status.isError()) {
            throw new IllegalArgumentException("API problems require a 4xx or 5xx status");
        }
        return status;
    }

    static String requireCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,79}")) {
            throw new IllegalArgumentException("API problem code must be a static uppercase identifier");
        }
        return code;
    }
}
