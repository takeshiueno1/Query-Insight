package com.query.insight.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SimplifiedRoleMigrationIntegrationTests {

    @Test
    void migratesLegacyGrantsToOneOfThreeActiveRolesWithoutDeletingHistory() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:simplified_roles;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("5"))
                .load()
                .migrate();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        insertLegacyAccountsAndGrants(jdbc);

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("9"));
        assertThat(jdbc.sql("SELECT code FROM roles WHERE status='ACTIVE' ORDER BY code")
                .query(String.class).list()).containsExactly("ADMIN", "GENERAL", "OFFICER");
        assertThat(jdbc.sql("""
                SELECT e.employee_no || ':' || r.code || ':' || g.scope_type
                FROM permission_grants g
                JOIN accounts a ON a.id=g.account_id
                JOIN employees e ON e.id=a.employee_id
                JOIN roles r ON r.id=g.role_id
                WHERE g.revoked_at IS NULL
                ORDER BY e.employee_no
                """).query(String.class).list()).containsExactly(
                        "QI0001:ADMIN:ALL",
                        "QI0002:OFFICER:SUBORDINATES",
                        "QI0003:GENERAL:SELF",
                        "QI0039:OFFICER:ALL");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM permission_grants g
                JOIN roles r ON r.id=g.role_id
                WHERE g.revoked_at IS NULL AND r.status<>'ACTIVE'
                """).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM accounts a
                WHERE (SELECT COUNT(*) FROM permission_grants g
                       WHERE g.account_id=a.id AND g.revoked_at IS NULL) <> 1
                """).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM permission_grants WHERE revoked_at IS NOT NULL")
                .query(Integer.class).single()).isEqualTo(8);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM accounts WHERE failed_count<>0 OR locked_until IS NOT NULL")
                .query(Integer.class).single()).isZero();
    }

    private void insertLegacyAccountsAndGrants(JdbcClient jdbc) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 0, 0);
        jdbc.sql("""
                INSERT INTO departments(public_id,code,name,status,version,created_at,updated_at)
                VALUES ('01000000000000000000000000','TEST','テスト部','ACTIVE',0,:now,:now)
                """).param("now", now).update();
        String[] employeeNos = {"QI0001", "QI0002", "QI0003", "QI0039"};
        for (int index = 0; index < employeeNos.length; index++) {
            int number = index + 1;
            jdbc.sql("""
                    INSERT INTO employees(public_id,employee_no,last_name,first_name,email,department_id,
                      employment_status,version,created_at,updated_at)
                    VALUES (:publicId,:employeeNo,'試験',:firstName,:email,
                      (SELECT id FROM departments WHERE code='TEST'),'ACTIVE',0,:now,:now)
                    """).param("publicId", "01" + String.format("%024d", number))
                    .param("employeeNo", employeeNos[index]).param("firstName", Integer.toString(number))
                    .param("email", "role-" + number + "@example.invalid").param("now", now).update();
            jdbc.sql("""
                    INSERT INTO accounts(public_id,employee_id,login_id_normalized,password_hash,status,
                      failed_count,locked_until,password_changed_at,version)
                    VALUES (:publicId,(SELECT id FROM employees WHERE employee_no=:employeeNo),:loginId,
                      'unused','ACTIVE',7,:lockedUntil,:now,0)
                    """).param("publicId", "02" + String.format("%024d", number))
                    .param("employeeNo", employeeNos[index]).param("loginId", "role-" + number)
                    .param("lockedUntil", now.plusDays(1)).param("now", now).update();
        }
        for (String role : new String[] {"EMPLOYEE", "MANAGER", "HR", "SYSTEM_ADMIN", "AUDITOR"}) {
            jdbc.sql("""
                    INSERT INTO roles(code,name,status)
                    SELECT :role,:role,'ACTIVE' WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code=:role)
                    """).param("role", role).update();
        }
        grant(jdbc, 1, "QI0001", "EMPLOYEE", "SELF", now);
        grant(jdbc, 2, "QI0001", "HR", "ALL", now);
        grant(jdbc, 3, "QI0001", "SYSTEM_ADMIN", "ALL", now);
        grant(jdbc, 4, "QI0001", "AUDITOR", "ALL", now);
        grant(jdbc, 5, "QI0002", "EMPLOYEE", "SELF", now);
        grant(jdbc, 6, "QI0002", "MANAGER", "SUBORDINATES", now);
        grant(jdbc, 7, "QI0003", "EMPLOYEE", "SELF", now);
        grant(jdbc, 8, "QI0039", "EXECUTIVE", "ALL", now);
    }

    private void grant(JdbcClient jdbc, int number, String employeeNo, String role, String scope, LocalDateTime now) {
        jdbc.sql("""
                INSERT INTO permission_grants(public_id,account_id,role_id,scope_type,valid_from,reason)
                VALUES (:publicId,
                  (SELECT a.id FROM accounts a JOIN employees e ON e.id=a.employee_id WHERE e.employee_no=:employeeNo),
                  (SELECT id FROM roles WHERE code=:role),:scope,:now,'移行テスト')
                """).param("publicId", "03" + String.format("%024d", number)).param("employeeNo", employeeNo)
                .param("role", role).param("scope", scope).param("now", now).update();
    }
}
