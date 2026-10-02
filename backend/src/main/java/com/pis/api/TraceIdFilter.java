package com.pis.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Generates a server-owned correlation ID before the Spring Security filter chain. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class TraceIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    private static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    public static UUID currentTraceId() {
        var traceId = CURRENT.get();
        if (traceId == null) {
            throw new IllegalStateException("No server request trace is bound to this thread");
        }
        return traceId;
    }

    public static UUID traceId(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof UUID traceId) {
            return traceId;
        }
        throw new IllegalStateException("TraceIdFilter has not initialized this request");
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return false; }

    @Override
    protected boolean shouldNotFilterErrorDispatch() { return false; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Keep one ID across server redispatches, but never read a client header or parameter.
        var traceId = request.getAttribute(ATTRIBUTE) instanceof UUID existing
            ? existing : UUID.randomUUID();
        request.setAttribute(ATTRIBUTE, traceId);
        CURRENT.set(traceId);
        try {
            MDC.put(MDC_KEY, traceId.toString());
            response.setHeader(HEADER, traceId.toString());
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            CURRENT.remove();
        }
    }
}
