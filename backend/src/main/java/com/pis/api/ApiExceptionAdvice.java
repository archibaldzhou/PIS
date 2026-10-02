package com.pis.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Safe MVC boundary: framework exception messages, SQL and submitted values are never returned. */
@RestControllerAdvice
public final class ApiExceptionAdvice extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionAdvice.class);
    private static final String INVALID_DETAIL = "The request parameters are invalid.";
    private static final String INTERNAL_DETAIL = "An unexpected error occurred.";
    private static final Set<String> CONSTRAINT_CODES = Set.of(
        "AssertFalse", "AssertTrue", "DecimalMax", "DecimalMin", "Digits", "Email", "Future",
        "FutureOrPresent", "Max", "Min", "Negative", "NegativeOrZero", "NotBlank", "NotEmpty",
        "NotNull", "Null", "Past", "PastOrPresent", "Pattern", "Positive", "PositiveOrZero", "Size",
        "typeMismatch", "required");

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> domain(ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ApiProblems.create(request, exception.status(), exception.code(), exception.safeDetail()));
    }

    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    public void security(RuntimeException exception) {
        // Let the surrounding Security chain choose its established 401/403 code and message.
        throw exception;
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Object> binding(BindException exception, HttpServletRequest request) {
        return validation(request, bindingViolations(exception.getBindingResult()), HttpHeaders.EMPTY);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unknown(Exception exception, HttpServletRequest request) {
        // A controller may wrap a Security failure; preserve the Security boundary in that case too.
        int depth = 0;
        for (Throwable cause = exception; cause != null && depth++ < 32; cause = cause.getCause()) {
            if (cause instanceof AuthenticationException authentication) { throw authentication; }
            if (cause instanceof AccessDeniedException denied) { throw denied; }
            if (cause.getCause() == cause) { break; }
        }
        LOG.error("api_error code=INTERNAL_ERROR traceId={} exceptionType={}",
            TraceIdFilter.traceId(request), exception.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ApiProblems.create(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", INTERNAL_DETAIL));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object ignoredBody,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        var servletRequest = ((ServletWebRequest) request).getRequest();
        if (exception instanceof MethodArgumentNotValidException invalid) {
            return validation(servletRequest, bindingViolations(invalid.getBindingResult()), headers);
        }
        if (exception instanceof HandlerMethodValidationException invalid && !invalid.isForReturnValue()) {
            var violations = new ArrayList<Violation>();
            for (var result : invalid.getParameterValidationResults()) {
                if (result instanceof Errors errors) {
                    violations.addAll(bindingViolations(errors));
                } else {
                    String field = parameterName(result.getMethodParameter());
                    result.getResolvableErrors().forEach(error -> violations.add(violation(field, error)));
                }
            }
            invalid.getCrossParameterValidationResults()
                .forEach(error -> violations.add(violation("$", error)));
            return validation(servletRequest, violations, headers);
        }
        // Return-value validation and other server failures always remain 500-class failures.
        var status = HttpStatus.resolve(statusCode.value());
        if (status == null || !status.isError()) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String code;
        String detail;
        if (status.is5xxServerError()) {
            code = "INTERNAL_ERROR";
            detail = INTERNAL_DETAIL;
        } else if (exception instanceof HttpMessageNotReadableException) {
            code = "INVALID_JSON";
            detail = "The request body is missing or is not valid JSON for this operation.";
        } else if (exception instanceof MissingServletRequestParameterException) {
            code = "MISSING_PARAMETER";
            detail = "A required request parameter is missing.";
        } else if (exception instanceof TypeMismatchException) {
            code = "TYPE_MISMATCH";
            detail = "A request parameter has an invalid type.";
        } else {
            code = switch (status.value()) {
                case 404 -> "NOT_FOUND";
                case 405 -> "METHOD_NOT_ALLOWED";
                case 406 -> "NOT_ACCEPTABLE";
                case 413 -> "PAYLOAD_TOO_LARGE";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                default -> "INVALID_REQUEST";
            };
            detail = switch (status.value()) {
                case 404 -> "The requested resource was not found.";
                case 405 -> "The request method is not supported.";
                case 406 -> "The requested response media type is not supported.";
                case 413 -> "The request exceeds the permitted size.";
                case 415 -> "The request media type is not supported.";
                default -> INVALID_DETAIL;
            };
        }
        return response(ApiProblems.create(servletRequest, status, code, detail), headers);
    }

    private static ResponseEntity<Object> validation(HttpServletRequest request,
                                                     List<Violation> violations, HttpHeaders headers) {
        var problem = ApiProblems.create(request, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", INVALID_DETAIL);
        problem.setProperty("violations", violations.stream().distinct()
            .sorted(Comparator.comparing(Violation::field).thenComparing(Violation::code)).toList());
        return response(problem, headers);
    }

    private static ResponseEntity<Object> response(ProblemDetail problem, HttpHeaders headers) {
        var safeHeaders = new HttpHeaders();
        safeHeaders.putAll(headers);
        safeHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        safeHeaders.setCacheControl("no-store");
        return new ResponseEntity<>(problem, safeHeaders, HttpStatusCode.valueOf(problem.getStatus()));
    }

    private static List<Violation> bindingViolations(Errors errors) {
        return errors.getAllErrors().stream()
            .map(error -> violation(error instanceof FieldError field ? field.getField() : "$", error))
            .toList();
    }

    private static Violation violation(String field, MessageSourceResolvable error) {
        String code = "Invalid";
        if (error.getCodes() != null) {
            for (String candidate : error.getCodes()) {
                if (CONSTRAINT_CODES.contains(candidate)) {
                    code = candidate;
                    break;
                }
            }
        }
        // A Map binding path may contain an attacker-controlled key. Do not echo bracket values.
        String safeField = field.replaceAll("\\[[^\\]]*]", "[]");
        if (safeField.length() > 128 || !safeField.matches("[A-Za-z_$][A-Za-z0-9_$.\\[\\]]*")) {
            safeField = "$";
        }
        return new Violation(safeField, code);
    }

    private static String parameterName(MethodParameter parameter) {
        var query = parameter.getParameterAnnotation(RequestParam.class);
        if (query != null) {
            return declaredName(query.name(), query.value(), parameter);
        }
        var path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null) {
            return declaredName(path.name(), path.value(), parameter);
        }
        var header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null) {
            return declaredName(header.name(), header.value(), parameter);
        }
        return declaredName("", "", parameter);
    }

    private static String declaredName(String name, String alias, MethodParameter parameter) {
        if (!name.isBlank()) { return name; }
        if (!alias.isBlank()) { return alias; }
        String reflected = parameter.getParameterName();
        return reflected != null ? reflected : "argument" + parameter.getParameterIndex();
    }

    /** Deliberately contains no rejectedValue, message, arguments, object, or constraint payload. */
    public record Violation(String field, String code) { }
}
