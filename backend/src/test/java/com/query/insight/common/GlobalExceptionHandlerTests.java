package com.query.insight.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

class GlobalExceptionHandlerTests {
    @Test
    void accessDeniedIsReturnedAsForbidden() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/audit-logs");
        when(request.getAttribute(TraceIdFilter.ATTRIBUTE)).thenReturn("test-trace");

        var response = new GlobalExceptionHandler()
                .handleAccessDenied(new AccessDeniedException("denied"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", "ACCESS_DENIED");
    }
}
