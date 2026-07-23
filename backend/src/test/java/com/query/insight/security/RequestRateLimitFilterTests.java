package com.query.insight.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestRateLimitFilterTests {
    private final RequestRateLimitFilter filter = new RequestRateLimitFilter(
            Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC),
            policy(2), policy(2), policy(1), policy(10), 100, 1);

    @Test
    void rejectsLoginRequestsBeyondTheConfiguredLimit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = (ignoredRequest, ignoredResponse) -> calls.incrementAndGet();

        assertThat(invoke("/api/v1/auth/login", chain).getStatus()).isEqualTo(200);
        assertThat(invoke("/api/v1/auth/login", chain).getStatus()).isEqualTo(200);
        MockHttpServletResponse rejected = invoke("/api/v1/auth/login", chain);

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("60");
        assertThat(rejected.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
        assertThat(calls).hasValue(2);
    }

    @Test
    void allowsOnlyOneConcurrentAiAnalysis() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                invoke("/api/v1/ai-analyses", (ignoredRequest, ignoredResponse) -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting for test release");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                });
            } catch (Throwable throwable) {
                backgroundFailure.set(throwable);
            }
        });
        first.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        MockHttpServletResponse rejected = invoke("/api/v1/ai-analyses",
                (ignoredRequest, ignoredResponse) -> { });
        release.countDown();
        first.join(5000);

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getContentAsString()).contains("AI分析を実行中");
        assertThat(backgroundFailure.get()).isNull();
    }

    private MockHttpServletResponse invoke(String path, FilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("192.0.2.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static RequestRateLimitFilter.Policy policy(int maxRequests) {
        return new RequestRateLimitFilter.Policy(maxRequests, Duration.ofMinutes(1));
    }
}
