package com.query.insight.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OriginValidationFilterTests {
    private final OriginValidationFilter filter = new OriginValidationFilter("http://localhost:8088");

    @Test
    void rejectsCrossSiteLoginThatCreatesARefreshCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.addHeader("Origin", "https://attacker.example");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ORIGIN_NOT_ALLOWED");
    }

    @Test
    void allowsConfiguredBrowserOriginAndNonBrowserClient() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = (ignoredRequest, ignoredResponse) -> calls.incrementAndGet();
        MockHttpServletRequest browser = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        browser.addHeader("Origin", "http://localhost:8088");
        browser.addHeader("Sec-Fetch-Site", "same-origin");

        filter.doFilter(browser, new MockHttpServletResponse(), chain);
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/auth/logout"),
                new MockHttpServletResponse(), chain);

        assertThat(calls).hasValue(2);
    }
}
