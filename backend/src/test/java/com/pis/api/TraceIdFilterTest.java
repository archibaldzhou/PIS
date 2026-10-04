package com.pis.api;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceIdFilterTest {
    private final TraceIdFilter filter = new TraceIdFilter();

    @Test void operationalLogsContainOnlyFixedFieldsAndServerTrace() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(TraceIdFilter.class);
        var captured=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();captured.start();logger.addAppender(captured);
        try {
            var request=new MockHttpServletRequest("GET","/synthetic-private-path");request.setQueryString("secret=synthetic-secret");request.addHeader("Authorization","synthetic-secret");request.addParameter("patient","synthetic-private-name");
            var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->response.setStatus(503));
            assertThat(captured.list).hasSize(1);
            assertThat(captured.list.getFirst().getFormattedMessage()).isEqualTo("event=HTTP_COMPLETE traceId="+response.getHeader(TraceIdFilter.HEADER)+" status=503").doesNotContain("synthetic-secret","synthetic-private");
        } finally { logger.detachAppender(captured);captured.stop(); }
    }

    @Test
    void replacesClientTraceAndSharesOneServerTraceWithRequestResponseAndMdc() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var clientTrace = UUID.randomUUID().toString();
        request.addHeader(TraceIdFilter.HEADER, clientTrace);
        request.addParameter("traceId", clientTrace);
        var seen = new AtomicReference<UUID>();
        filter.doFilter(request, response, (req, res) -> {
            var trace = TraceIdFilter.currentTraceId();
            seen.set(trace);
            assertThat(trace.version()).isEqualTo(4);
            assertThat(trace.toString()).isNotEqualTo(clientTrace);
            assertThat(TraceIdFilter.traceId(request)).isEqualTo(trace);
            assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isEqualTo(trace.toString());
            assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(trace.toString());
        });
        assertThat(seen.get()).isNotNull();
        assertUnbound();
    }

    @Test
    void clearsThreadContextEvenWhenTheChainThrowsAndDoesNotReuseIt() throws Exception {
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), response,
            (req, res) -> { throw new ServletException("synthetic exception only"); }))
            .isInstanceOf(ServletException.class);
        assertUnbound();
        var next = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), next, (req, res) -> { });
        assertThat(next.getHeader(TraceIdFilter.HEADER)).isNotEqualTo(response.getHeader(TraceIdFilter.HEADER));
        assertUnbound();
    }

    @Test
    void preservesTraceAcrossServerAsyncAndErrorRedispatches() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });
        String trace = response.getHeader(TraceIdFilter.HEADER);
        for (var dispatcher : new DispatcherType[] {DispatcherType.ASYNC, DispatcherType.ERROR}) {
            request.setDispatcherType(dispatcher);
            filter.doFilter(request, response, (req, res) ->
                assertThat(TraceIdFilter.currentTraceId().toString()).isEqualTo(trace));
            assertUnbound();
        }
    }

    @Test
    void requiresABoundServerTraceAndRunsBeforeSecurityDefaultOrder() {
        assertUnbound();
        assertThatThrownBy(() -> TraceIdFilter.traceId(new MockHttpServletRequest()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(TraceIdFilter.class.getAnnotation(Order.class).value())
            .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10).isLessThan(-100);
    }

    private static void assertUnbound() {
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
        assertThatThrownBy(TraceIdFilter::currentTraceId).isInstanceOf(IllegalStateException.class);
    }
}
