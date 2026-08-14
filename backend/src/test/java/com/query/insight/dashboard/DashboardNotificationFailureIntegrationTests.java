package com.query.insight.dashboard;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.query.insight.notification.NotificationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class DashboardNotificationFailureIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private NotificationService notifications;
    private Account owner;

    @BeforeEach
    void setUp() {
        owner = jdbc.sql("""
                SELECT a.public_id account_public_id,e.public_id employee_public_id
                FROM accounts a JOIN employees e ON e.id=a.employee_id WHERE e.employee_no='QI0005'
                """).query((rs, row) -> new Account(rs.getString("account_public_id").trim(),
                        rs.getString("employee_public_id").trim())).single();
        when(notifications.unreadCount(anyString()))
                .thenThrow(new IllegalStateException("notification store unavailable"));
    }

    @Test
    void returnsCoreDashboardWithZeroUnreadWhenNotificationCountFails() throws Exception {
        mvc.perform(get("/api/v1/dashboard/me").with(general(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.employeeNo").value("QI0005"))
                .andExpect(jsonPath("$.profileStatus.grade").value("C"))
                .andExpect(jsonPath("$.finalManagerEvaluation.finalRank").value("A"))
                .andExpect(jsonPath("$.unreadNotifications").value(0));
    }

    private RequestPostProcessor general(Account account) {
        return jwt().jwt(token -> token.claim("accountPublicId", account.accountPublicId())
                        .claim("employeePublicId", account.employeePublicId())
                        .claim("scopes", List.of("SELF")))
                .authorities(new SimpleGrantedAuthority("ROLE_GENERAL"));
    }

    private record Account(String accountPublicId, String employeePublicId) {
    }
}
