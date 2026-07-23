package com.query.insight.security;

import com.query.insight.common.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestRateLimitFilter extends OncePerRequestFilter {
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";
    private static final String RESET_PATH = "/api/v1/auth/password-reset-requests";
    private static final String AI_PATH = "/api/v1/ai-analyses";

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong requestCounter = new AtomicLong();
    private final Policy loginPolicy;
    private final Policy refreshPolicy;
    private final Policy resetPolicy;
    private final Policy aiPolicy;
    private final int maxEntries;
    private final Semaphore aiPermits;

    @Autowired
    public RequestRateLimitFilter(
            @Value("${app.security.rate-limit.login.max-requests:10}") int loginMaxRequests,
            @Value("${app.security.rate-limit.login.window:PT1M}") Duration loginWindow,
            @Value("${app.security.rate-limit.refresh.max-requests:30}") int refreshMaxRequests,
            @Value("${app.security.rate-limit.refresh.window:PT1M}") Duration refreshWindow,
            @Value("${app.security.rate-limit.password-reset.max-requests:3}") int resetMaxRequests,
            @Value("${app.security.rate-limit.password-reset.window:PT10M}") Duration resetWindow,
            @Value("${app.security.rate-limit.ai.max-requests:3}") int aiMaxRequests,
            @Value("${app.security.rate-limit.ai.window:PT10M}") Duration aiWindow,
            @Value("${app.security.rate-limit.max-entries:10000}") int maxEntries,
            @Value("${app.security.rate-limit.ai.max-concurrent:1}") int aiMaxConcurrent) {
        this(Clock.systemUTC(), new Policy(loginMaxRequests, loginWindow),
                new Policy(refreshMaxRequests, refreshWindow), new Policy(resetMaxRequests, resetWindow),
                new Policy(aiMaxRequests, aiWindow), maxEntries, aiMaxConcurrent);
    }

    RequestRateLimitFilter(Clock clock, Policy loginPolicy, Policy refreshPolicy, Policy resetPolicy,
            Policy aiPolicy, int maxEntries, int aiMaxConcurrent) {
        if (maxEntries < 1 || aiMaxConcurrent < 1) {
            throw new IllegalArgumentException("Rate limit capacity must be positive");
        }
        this.clock = clock;
        this.loginPolicy = loginPolicy;
        this.refreshPolicy = refreshPolicy;
        this.resetPolicy = resetPolicy;
        this.aiPolicy = aiPolicy;
        this.maxEntries = maxEntries;
        this.aiPermits = new Semaphore(aiMaxConcurrent, true);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        Policy policy = policy(path);
        if (policy == null) {
            chain.doFilter(request, response);
            return;
        }

        Decision decision = acquire(key(path, request), policy);
        if (!decision.allowed()) {
            writeRateLimit(response, request, decision.retryAfterSeconds(), "リクエスト回数が上限を超えました");
            return;
        }

        boolean aiPermit = false;
        if (AI_PATH.equals(path)) {
            aiPermit = aiPermits.tryAcquire();
            if (!aiPermit) {
                writeRateLimit(response, request, 30, "AI分析を実行中です。完了後に再試行してください");
                return;
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (aiPermit) {
                aiPermits.release();
            }
        }
    }

    private Policy policy(String path) {
        return switch (path) {
            case LOGIN_PATH -> loginPolicy;
            case REFRESH_PATH -> refreshPolicy;
            case RESET_PATH -> resetPolicy;
            case AI_PATH -> aiPolicy;
            default -> null;
        };
    }

    private String key(String path, HttpServletRequest request) {
        if (AI_PATH.equals(path)) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()) {
                return path + ":account:" + authentication.getName();
            }
        }
        return path + ":address:" + request.getRemoteAddr();
    }

    private Decision acquire(String key, Policy policy) {
        long now = clock.instant().getEpochSecond();
        if (requestCounter.incrementAndGet() % 256 == 0 || windows.size() >= maxEntries) {
            windows.entrySet().removeIf(entry -> entry.getValue().resetAtEpochSecond() <= now);
        }
        if (!windows.containsKey(key) && windows.size() >= maxEntries) {
            return new Decision(false, 60);
        }
        Window window = windows.compute(key, (ignored, current) -> {
            if (current == null || current.resetAtEpochSecond() <= now) {
                return new Window(1, now + policy.window().toSeconds());
            }
            return new Window(Math.min(current.count() + 1, policy.maxRequests() + 1),
                    current.resetAtEpochSecond());
        });
        return new Decision(window.count() <= policy.maxRequests(),
                Math.max(1, window.resetAtEpochSecond() - now));
    }

    private void writeRateLimit(HttpServletResponse response, HttpServletRequest request, long retryAfterSeconds,
            String detail) throws IOException {
        response.setStatus(429);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setHeader("Cache-Control", "no-store");
        Object traceId = request.getAttribute(TraceIdFilter.ATTRIBUTE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\","
                + "\"status\":429,\"code\":\"RATE_LIMIT_EXCEEDED\",\"detail\":\"" + detail
                + "\",\"traceId\":\"" + (traceId == null ? "" : traceId) + "\"}");
    }

    record Policy(int maxRequests, Duration window) {
        Policy {
            if (maxRequests < 1 || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("Rate limit policy must be positive");
            }
        }
    }

    private record Window(int count, long resetAtEpochSecond) {
    }

    private record Decision(boolean allowed, long retryAfterSeconds) {
    }
}
