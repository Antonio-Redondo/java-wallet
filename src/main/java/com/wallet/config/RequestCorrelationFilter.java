package com.wallet.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns a correlation id to every request and exposes it to the logging layer
 * via the SLF4J {@link MDC}, so every log line emitted while handling a request
 * carries the same {@code requestId} and a single transfer can be traced
 * end-to-end (see {@code logback-spring.xml}, which renders {@code %X{requestId}}).
 *
 * <p>If the caller (or an upstream gateway / load balancer) already supplies an
 * {@code X-Request-Id} header, it is honoured so the trace is continuous across
 * nodes; otherwise a fresh UUID is generated. The id is echoed back on the
 * response so the client can report it. The MDC entry is always cleared in a
 * {@code finally} block — leaving it set would leak the id onto the next request
 * handled by the same (pooled) thread.
 *
 * <p>Runs first in the filter chain ({@link Ordered#HIGHEST_PRECEDENCE}) so the
 * id is in place before security and controller logging happen.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
