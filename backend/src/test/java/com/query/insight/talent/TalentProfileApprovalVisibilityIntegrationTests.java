package com.query.insight.talent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.query.insight.common.ApiException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class TalentProfileApprovalVisibilityIntegrationTests {
    @Autowired
    private TalentProfileService profiles;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void systemAdminAndAuditorCannotReadAnotherEmployeesTalentProfile() {
        String target = employee("QITEST");
        String admin = employee("QI0001");

        assertThatThrownBy(() -> profiles.findAccessible(target, admin, account("QI0001"),
                Set.of("SYSTEM_ADMIN"))).isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(404));
        assertThatThrownBy(() -> profiles.findAccessible(target, admin, account("QI0001"),
                Set.of("AUDITOR"))).isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(404));
    }

    @Test
    void executiveAllAndCurrentManagerCanReadApprovedProfile() {
        String target = employee("QITEST");
        var executiveProfile = profiles.findAccessible(target, employee("QI0039"), account("QI0039"),
                Set.of("EXECUTIVE"));
        var managerProfile = profiles.findAccessible(target, employee("QI0002"), account("QI0002"),
                Set.of("MANAGER"));

        assertThat(executiveProfile.skills()).isNotEmpty();
        assertThat(managerProfile.skills()).isNotEmpty();
    }

    @Test
    void executiveWithoutAllScopeCannotRead() {
        String target = employee("QITEST");
        String executiveAccount = account("QI0039");
        long grantId = jdbc.sql("""
                SELECT g.id FROM permission_grants g JOIN roles r ON r.id=g.role_id
                JOIN accounts a ON a.id=g.account_id WHERE a.public_id=:account AND r.code='EXECUTIVE'
                """).param("account", executiveAccount).query(Long.class).single();
        try {
            jdbc.sql("UPDATE permission_grants SET scope_type='SELF' WHERE id=:id").param("id", grantId).update();
            assertThatThrownBy(() -> profiles.findAccessible(target, employee("QI0039"), executiveAccount,
                    Set.of("EXECUTIVE"))).isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.status().value()).isEqualTo(404));
        } finally {
            jdbc.sql("UPDATE permission_grants SET scope_type='ALL' WHERE id=:id").param("id", grantId).update();
        }
    }

    private String employee(String employeeNo) {
        return jdbc.sql("SELECT public_id FROM employees WHERE employee_no=:no")
                .param("no", employeeNo).query(String.class).single().trim();
    }

    private String account(String employeeNo) {
        return jdbc.sql("SELECT a.public_id FROM accounts a JOIN employees e ON e.id=a.employee_id WHERE e.employee_no=:no")
                .param("no", employeeNo).query(String.class).single().trim();
    }
}
