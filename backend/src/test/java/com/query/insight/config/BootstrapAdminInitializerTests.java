package com.query.insight.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:bootstrap_admin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "app.bootstrap.admin.enabled=true",
        "app.bootstrap.admin.login-id=owner@example.invalid",
        "app.bootstrap.admin.password=BootstrapPassword2026",
        "app.bootstrap.admin.employee-no=QI-OWNER",
        "app.bootstrap.admin.last-name=初期",
        "app.bootstrap.admin.first-name=管理者"
})
@ActiveProfiles("free")
@Import(BootstrapAdminInitializerTests.TestPasswordConfiguration.class)
class BootstrapAdminInitializerTests {
    @Autowired
    private JdbcClient jdbc;

    @Test
    void createsOneAdministratorOnlyInAnEmptyDatabase() {
        Integer accounts = jdbc.sql("SELECT COUNT(*) FROM accounts WHERE login_id_normalized='owner@example.invalid'")
                .query(Integer.class).single();
        Integer grants = jdbc.sql("""
                SELECT COUNT(*) FROM permission_grants g
                JOIN accounts a ON a.id=g.account_id
                WHERE a.login_id_normalized='owner@example.invalid'
                """).query(Integer.class).single();

        assertThat(accounts).isEqualTo(1);
        assertThat(grants).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT r.code || ':' || g.scope_type FROM permission_grants g
                JOIN roles r ON r.id=g.role_id JOIN accounts a ON a.id=g.account_id
                WHERE a.login_id_normalized='owner@example.invalid' AND g.revoked_at IS NULL
                """).query(String.class).single()).isEqualTo("ADMIN:ALL");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestPasswordConfiguration {
        @Bean
        @Primary
        PasswordEncoder testPasswordEncoder() {
            return new PasswordEncoder() {
                @Override
                public String encode(CharSequence rawPassword) {
                    return "test:" + rawPassword;
                }

                @Override
                public boolean matches(CharSequence rawPassword, String encodedPassword) {
                    return encodedPassword.equals(encode(rawPassword));
                }
            };
        }
    }
}
