package com.query.insight.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @Test
    void missingRequiredRequestParameterIsReturnedAsStructuredBadRequest() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new RequiredQueryController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/required-query"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("必須の入力項目を指定してください"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("type"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("required"));
    }

    @RestController
    static class RequiredQueryController {
        @GetMapping("/required-query")
        String requiredQuery(@RequestParam String type) {
            return type;
        }
    }
}
