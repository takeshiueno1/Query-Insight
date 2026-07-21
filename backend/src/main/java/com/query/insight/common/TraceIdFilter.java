package com.query.insight.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TraceIdFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requested = request.getHeader("X-Correlation-Id");
        String traceId = requested != null && requested.matches("[0-9A-HJKMNP-TV-Z]{26}")
                ? requested
                : PublicIdGenerator.next();
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader("X-Correlation-Id", traceId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable(ATTRIBUTE, traceId)) {
            filterChain.doFilter(request, response);
        }
    }
}
