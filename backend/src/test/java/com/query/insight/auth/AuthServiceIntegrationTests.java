package com.query.insight.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.query.insight.common.ApiException;
import com.query.insight.config.LocalRealisticDataInitializer;
import com.query.insight.talent.TalentProfileService;
import java.util.Set;
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

@SpringBootTest
@ActiveProfiles("local")
@Import(AuthServiceIntegrationTests.TestPasswordConfiguration.class)
class AuthServiceIntegrationTests {
    @Autowired
    private AuthService authService;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private LocalRealisticDataInitializer realisticDataInitializer;
    @Autowired
    private TalentProfileService talentProfileService;

    @Test
    void loginAndRefreshRotateTheRefreshToken() {
        AuthService.Session login = authService.login(
                "employee@query.local", "QueryInsight#2026", "test-login");
        AuthService.Session refreshed = authService.refresh(login.refreshToken(), "test-refresh");

        assertThat(login.accessToken()).isNotBlank();
        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(refreshed.principal().roles()).contains("EMPLOYEE");
        assertThatThrownBy(() -> authService.refresh(login.refreshToken(), "test-reuse"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("セッション");
    }

    @Test
    void localSeedContainsProductionLikeEmployeesAndSubmittedCapabilities() {
        Integer employeeCount = jdbc.sql("SELECT COUNT(*) FROM employees").query(Integer.class).single();
        Integer submittedAxisCount = jdbc.sql("""
                SELECT COUNT(*)
                FROM self_evaluation_details ed
                JOIN evaluation_targets et ON et.id = ed.target_id
                JOIN employees e ON e.id = et.employee_id
                JOIN accounts a ON a.employee_id = e.id
                WHERE a.login_id_normalized = 'employee@query.local'
                  AND et.status = 'SELF_SUBMITTED'
                  AND ed.level > 0
                """).query(Integer.class).single();

        assertThat(employeeCount).isEqualTo(50);
        assertThat(submittedAxisCount).isEqualTo(6);
    }

    @Test
    void realisticSeedIsCompleteAndIdempotent() throws Exception {
        realisticDataInitializer.run(null);

        assertThat(count("employees")).isEqualTo(50);
        assertThat(count("accounts")).isEqualTo(50);
        assertThat(count("evaluation_targets")).isEqualTo(50);
        assertThat(count("self_evaluation_details")).isEqualTo(300);
        assertThat(count("employee_skills")).isEqualTo(250);
        assertThat(count("employee_knowledge")).isEqualTo(150);
        assertThat(count("career_histories")).isGreaterThanOrEqualTo(50);
        assertThat(count("employee_certifications")).isEqualTo(50);
    }

    @Test
    void employeeCanReadOwnRealisticTalentProfile() {
        String publicId = jdbc.sql("SELECT public_id FROM employees WHERE employee_no='QI0003'")
                .query(String.class).single();

        TalentProfileService.TalentProfile profile = talentProfileService.findAccessible(
                publicId, publicId, Set.of("EMPLOYEE"));

        assertThat(profile.skills()).hasSize(5);
        assertThat(profile.knowledge()).hasSize(3);
        assertThat(profile.careers()).isNotEmpty();
        assertThat(profile.certifications()).hasSize(1);
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
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
