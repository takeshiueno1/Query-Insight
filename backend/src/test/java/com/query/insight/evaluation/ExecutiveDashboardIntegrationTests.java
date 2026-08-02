package com.query.insight.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class ExecutiveDashboardIntegrationTests {
    @Autowired
    private EvaluationWorkflowService service;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void localDataContainsDecisionReadyMixedStatusesAndDepartmentDistribution() {
        String executive = jdbc.sql("SELECT a.public_id FROM accounts a JOIN employees e ON e.id=a.employee_id "
                        + "WHERE e.employee_no='QI0039'")
                .query(String.class).single();

        var dashboard = service.executiveDashboard(executive);

        assertThat(dashboard.counts().pending()).isGreaterThanOrEqualTo(1);
        assertThat(dashboard.counts().finalized()).isGreaterThanOrEqualTo(1);
        assertThat(dashboard.distributions()).isNotEmpty();
        assertThat(dashboard.items()).extracting(EvaluationWorkflowService.ExecutiveListItem::status)
                .contains("EXECUTIVE_REVIEW", "FINALIZED", "MANAGER_RETURNED");
    }
}
