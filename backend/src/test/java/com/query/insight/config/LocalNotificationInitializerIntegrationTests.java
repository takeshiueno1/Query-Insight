package com.query.insight.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:local_notification_initializer;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("local")
class LocalNotificationInitializerIntegrationTests {
    @Autowired
    private JdbcClient jdbc;

    @Test
    void seedsCurrentActionNotificationsForTheExactRecipientRolesAndRoutes() {
        List<SeedNotification> notifications = jdbc.sql("""
                SELECT e.employee_no,r.code role_code,g.scope_type,n.type,n.title,n.body,n.link_path
                FROM notifications n JOIN accounts a ON a.id=n.recipient_account_id
                JOIN employees e ON e.id=a.employee_id
                JOIN permission_grants g ON g.account_id=a.id AND g.revoked_at IS NULL
                JOIN roles r ON r.id=g.role_id
                WHERE n.dedupe_key LIKE 'local-current-evaluation-%'
                ORDER BY e.employee_no
                """).query((rs, row) -> new SeedNotification(
                        rs.getString("employee_no"), rs.getString("role_code"), rs.getString("scope_type"),
                        rs.getString("type"), rs.getString("title"), rs.getString("body"),
                        rs.getString("link_path"))).list();

        assertThat(notifications).hasSize(3);
        assertThat(notifications).anySatisfy(item -> {
            assertThat(item.employeeNo()).isEqualTo("QI0008");
            assertThat(item.roleCode()).isEqualTo("GENERAL");
            assertThat(item.scopeType()).isEqualTo("SELF");
            assertThat(item.type()).isEqualTo("EVALUATION_FINALIZED");
            assertThat(item.title()).isEqualTo("上長評価が確定しました");
            assertThat(item.linkPath()).isEqualTo("/evaluations/manager-result");
        });
        assertThat(notifications).anySatisfy(item -> {
            assertThat(item.employeeNo()).isEqualTo("QI0002");
            assertThat(item.roleCode()).isEqualTo("OFFICER");
            assertThat(item.scopeType()).isEqualTo("SUBORDINATES");
            assertThat(item.type()).isEqualTo("MANAGER_EVALUATION_RETURNED");
            assertThat(item.title()).isEqualTo("最終承認者から評価が差し戻されました");
            assertThat(item.linkPath()).matches("/evaluations/manager/[0-7][0-9A-HJKMNP-TV-Z]{25}");
        });
        assertThat(notifications).anySatisfy(item -> {
            assertThat(item.employeeNo()).isEqualTo("QI0039");
            assertThat(item.roleCode()).isEqualTo("OFFICER");
            assertThat(item.scopeType()).isEqualTo("ALL");
            assertThat(item.type()).isEqualTo("EXECUTIVE_REVIEW");
            assertThat(item.title()).isEqualTo("上長評価の最終承認をお願いします");
            assertThat(item.linkPath()).matches("/executive/evaluations/[0-7][0-9A-HJKMNP-TV-Z]{25}");
        });
        assertThat(notifications).noneSatisfy(item ->
                assertThat(item.title() + item.body()).contains("自己評価"));
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM notifications
                WHERE title LIKE '%自己評価%' OR body LIKE '%自己評価%'
                  OR dedupe_key IN ('local-evaluation-submitted-employee','local-manager-review')
                """).query(Long.class).single()).isZero();
    }

    @Test
    void doesNotSeedTheObsoleteSelfReturnedWorkflow() {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM evaluation_targets WHERE status='SELF_RETURNED'")
                .query(Long.class).single()).isZero();
    }

    private record SeedNotification(String employeeNo, String roleCode, String scopeType, String type,
            String title, String body, String linkPath) {
    }
}
