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
        assertThat(refreshed.principal().roles()).containsExactly("GENERAL");
        assertThat(refreshed.principal().scopes()).containsExactly("SELF");
        assertThatThrownBy(() -> authService.refresh(login.refreshToken(), "test-reuse"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("セッション");
        assertThatThrownBy(() -> authService.refresh(refreshed.refreshToken(), "test-family-revoked"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("セッション");
    }

    @Test
    void unknownLoginIdReturnsTheSameAuthenticationFailure() {
        assertThatThrownBy(() -> authService.login(
                "unknown-user@query.local", "QueryInsight#2026", "test-unknown-login"))
                .isInstanceOf(ApiException.class)
                .hasMessage("ログインIDまたはパスワードが正しくありません");
    }

    @Test
    void localTestUserCanLoginWithRequestedCredentials() {
        AuthService.Session login = authService.login("test", "test", "test-local-user");

        assertThat(login.accessToken()).isNotBlank();
        assertThat(login.principal().displayName()).isEqualTo("テスト ユーザー");
        assertThat(login.principal().roles()).containsExactly("GENERAL");
        assertThat(login.principal().scopes()).containsExactly("SELF");
    }

    @Test
    void repeatedInvalidPasswordsNeverLockTheAccount() {
        for (int attempt = 0; attempt < 6; attempt++) {
            assertThatThrownBy(() -> authService.login("test", "wrong-password", "test-invalid-password"))
                    .isInstanceOf(ApiException.class)
                    .hasMessage("ログインIDまたはパスワードが正しくありません");
        }

        var lockState = jdbc.sql("""
                SELECT failed_count, locked_until FROM accounts WHERE login_id_normalized='test'
                """).query((rs, row) -> new Object[] {rs.getInt("failed_count"), rs.getTimestamp("locked_until")})
                .single();
        assertThat(lockState[0]).isEqualTo(0);
        assertThat(lockState[1]).isNull();
        assertThat(authService.login("test", "test", "test-after-invalid-pass").accessToken()).isNotBlank();
    }

    @Test
    void principalsExposeOnlyTheNewRoleAndItsDataScope() {
        assertPrincipal("employee@query.local", "GENERAL", "SELF");
        assertPrincipal("manager@query.local", "OFFICER", "SUBORDINATES");
        assertPrincipal(loginId("QI0039"), "OFFICER", "ALL");
        assertPrincipal("admin@query.local", "ADMIN", "ALL");
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

        assertThat(employeeCount).isEqualTo(51);
        assertThat(submittedAxisCount).isEqualTo(6);
    }

    @Test
    void realisticSeedIsCompleteAndIdempotent() throws Exception {
        realisticDataInitializer.run(null);

        assertThat(count("employees")).isEqualTo(51);
        assertThat(count("accounts")).isEqualTo(51);
        assertThat(count("evaluation_targets")).isEqualTo(51);
        assertThat(count("self_evaluation_details")).isEqualTo(306);
        assertThat(count("employee_skills")).isEqualTo(255);
        assertThat(count("employee_knowledge")).isEqualTo(153);
        assertThat(count("career_histories")).isGreaterThanOrEqualTo(51);
        assertThat(count("employee_certifications")).isEqualTo(51);
    }

    @Test
    void employeeCanReadOwnRealisticTalentProfile() {
        String publicId = jdbc.sql("SELECT public_id FROM employees WHERE employee_no='QI0003'")
                .query(String.class).single();

        TalentProfileService.TalentProfile profile = talentProfileService.findAccessible(
                publicId, publicId, Set.of("GENERAL"));

        assertThat(profile.skills()).hasSize(5);
        assertThat(profile.knowledge()).hasSize(3);
        assertThat(profile.careers()).isNotEmpty();
        assertThat(profile.certifications()).hasSize(1);
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private void assertPrincipal(String loginId, String role, String scope) {
        AuthService.Session session = authService.login(loginId, "QueryInsight#2026", "test-role-scope");
        assertThat(session.principal().roles()).containsExactly(role);
        assertThat(session.principal().scopes()).containsExactly(scope);
    }

    private String loginId(String employeeNo) {
        return jdbc.sql("""
                SELECT a.login_id_normalized FROM accounts a
                JOIN employees e ON e.id=a.employee_id WHERE e.employee_no=:employeeNo
                """).param("employeeNo", employeeNo).query(String.class).single();
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
