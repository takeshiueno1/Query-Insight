package com.query.insight.notification;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.query.insight.common.ApiException;
import com.query.insight.common.PublicIdGenerator;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class NotificationCountIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private NotificationService notifications;
    private Account owner;
    private Account other;

    @BeforeEach
    void setUp() {
        owner = account("QITEST");
        other = account("QI0003");
        jdbc.sql("DELETE FROM notifications WHERE recipient_account_id IN (:owner,:other)")
                .param("owner", owner.id()).param("other", other.id()).update();
    }

    @Test
    void countFallsFromTwoToOneToZeroAsNotificationsAreRead() throws Exception {
        String first = insert(owner.id(), "count-owner-1");
        insert(owner.id(), "count-owner-2");

        mvc.perform(get("/api/v1/notifications/unread-count").with(ownerJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(2));
        mvc.perform(patch("/api/v1/notifications/{publicId}/read", first).with(ownerJwt()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications/unread-count").with(ownerJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1));
        mvc.perform(patch("/api/v1/notifications/read-all").with(ownerJwt()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications/unread-count").with(ownerJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void countUsesAuthenticatedActiveAccountOnlyAndCannotBeSubstituted() throws Exception {
        insert(owner.id(), "count-scope-owner");
        insert(other.id(), "count-scope-other-1");
        insert(other.id(), "count-scope-other-2");

        mvc.perform(get("/api/v1/notifications/unread-count")
                        .param("accountPublicId", other.publicId())
                        .param("employeePublicId", other.employeePublicId()).with(ownerJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1));
        mvc.perform(get("/api/v1/notifications/unread-count"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidOrInactiveAuthenticatedAccountUsesExistingSessionError() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> notifications.unreadCount("01UNKNOWNACCOUNT00000000000"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.status().value()).isEqualTo(401);
                    org.assertj.core.api.Assertions.assertThat(exception.code()).isEqualTo("SESSION_EXPIRED");
                });
    }

    @Test
    void transitionDedupeKeyRemainsIdempotent() {
        notifications.notifyEmployee(owner.employeeId(), "TALENT_APPROVED", "承認", "承認されました", "/talent",
                "talent:logical-public-id:approved:7");
        notifications.notifyEmployee(owner.employeeId(), "TALENT_APPROVED", "承認", "承認されました", "/talent",
                "talent:logical-public-id:approved:7");

        assertThatCount("talent:logical-public-id:approved:7", 1);
    }

    private void assertThatCount(String dedupeKey, int expected) {
        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                        "SELECT COUNT(*) FROM notifications WHERE dedupe_key=:dedupeKey")
                .param("dedupeKey", dedupeKey).query(Integer.class).single()).isEqualTo(expected);
    }

    private String insert(long accountId, String dedupeKey) {
        String publicId = PublicIdGenerator.next();
        jdbc.sql("""
                INSERT INTO notifications(public_id,recipient_account_id,type,title,body,created_at,dedupe_key)
                VALUES (:publicId,:accountId,'TEST','テスト','本文',:createdAt,:dedupeKey)
                """).param("publicId", publicId).param("accountId", accountId)
                .param("createdAt", Timestamp.from(Instant.parse("2026-08-14T03:00:00Z")))
                .param("dedupeKey", dedupeKey).update();
        return publicId;
    }

    private RequestPostProcessor ownerJwt() {
        return jwt().jwt(token -> token.claim("accountPublicId", owner.publicId())
                        .claim("employeePublicId", owner.employeePublicId()))
                .authorities(new SimpleGrantedAuthority("ROLE_GENERAL"));
    }

    private Account account(String employeeNo) {
        return jdbc.sql("""
                SELECT a.id,a.public_id,e.id employee_id,e.public_id employee_public_id
                FROM accounts a JOIN employees e ON e.id=a.employee_id WHERE e.employee_no=:employeeNo
                """).param("employeeNo", employeeNo).query((rs, row) -> new Account(rs.getLong("id"),
                        rs.getString("public_id").trim(), rs.getLong("employee_id"),
                        rs.getString("employee_public_id").trim())).single();
    }

    private record Account(long id, String publicId, long employeeId, String employeePublicId) {
    }
}
