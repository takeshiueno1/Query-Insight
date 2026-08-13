package com.query.insight.auth;

import com.query.insight.common.PublicIdGenerator;
import com.query.insight.security.AccountPrincipal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuthRepository {
    private final JdbcClient jdbc;

    public AuthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AccountRecord> findAccount(String normalizedLoginId) {
        return jdbc.sql("""
                SELECT a.id, a.public_id, a.employee_id, a.password_hash, a.status,
                       e.public_id employee_public_id,
                       CONCAT(e.last_name, ' ', e.first_name) display_name
                FROM accounts a JOIN employees e ON e.id = a.employee_id
                WHERE a.login_id_normalized = :loginId
                """)
                .param("loginId", normalizedLoginId)
                .query((rs, row) -> new AccountRecord(rs.getLong("id"), rs.getString("public_id"),
                        rs.getLong("employee_id"), rs.getString("employee_public_id"), rs.getString("display_name"),
                        rs.getString("password_hash"), rs.getString("status")))
                .optional();
    }

    Optional<AccountRecord> findAccountById(long accountId) {
        return jdbc.sql("""
                SELECT a.id, a.public_id, a.employee_id, a.password_hash, a.status,
                       e.public_id employee_public_id,
                       CONCAT(e.last_name, ' ', e.first_name) display_name
                FROM accounts a JOIN employees e ON e.id = a.employee_id WHERE a.id = :id
                """)
                .param("id", accountId)
                .query((rs, row) -> new AccountRecord(rs.getLong("id"), rs.getString("public_id"),
                        rs.getLong("employee_id"), rs.getString("employee_public_id"), rs.getString("display_name"),
                        rs.getString("password_hash"), rs.getString("status")))
                .optional();
    }

    Set<String> activeRoles(long accountId, Instant now) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT r.code FROM permission_grants g JOIN roles r ON r.id = g.role_id
                WHERE g.account_id = :accountId AND g.revoked_at IS NULL
                  AND g.valid_from <= :now AND (g.valid_to IS NULL OR g.valid_to > :now)
                  AND r.status = 'ACTIVE' ORDER BY r.code
                """).param("accountId", accountId).param("now", Timestamp.from(now)).query(String.class).list());
    }

    Set<String> activeScopes(long accountId, Instant now) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT DISTINCT g.scope_type FROM permission_grants g JOIN roles r ON r.id = g.role_id
                WHERE g.account_id = :accountId AND g.revoked_at IS NULL
                  AND g.valid_from <= :now AND (g.valid_to IS NULL OR g.valid_to > :now)
                  AND r.status = 'ACTIVE' ORDER BY g.scope_type
                """).param("accountId", accountId).param("now", Timestamp.from(now)).query(String.class).list());
    }

    void loginSucceeded(long accountId, Instant now) {
        jdbc.sql("UPDATE accounts SET last_login_at = :now WHERE id = :id")
                .param("now", Timestamp.from(now)).param("id", accountId).update();
    }

    long insertRefreshToken(long accountId, String tokenHash, String familyId, Instant issuedAt, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO refresh_tokens(account_id, token_hash, family_id, issued_at, expires_at)
                VALUES (:accountId, :tokenHash, :familyId, :issuedAt, :expiresAt)
                """).param("accountId", accountId).param("tokenHash", tokenHash).param("familyId", familyId)
                .param("issuedAt", Timestamp.from(issuedAt)).param("expiresAt", Timestamp.from(expiresAt)).update();
        return jdbc.sql("SELECT id FROM refresh_tokens WHERE token_hash=:tokenHash")
                .param("tokenHash", tokenHash).query(Long.class).single();
    }

    Optional<RefreshRecord> findRefreshToken(String tokenHash) {
        return jdbc.sql("""
                SELECT id, account_id, family_id, expires_at, used_at, revoked_at
                FROM refresh_tokens WHERE token_hash = :tokenHash FOR UPDATE
                """).param("tokenHash", tokenHash)
                .query((rs, row) -> new RefreshRecord(rs.getLong("id"), rs.getLong("account_id"),
                        rs.getString("family_id"), rs.getTimestamp("expires_at").toInstant(),
                        instant(rs.getTimestamp("used_at")), instant(rs.getTimestamp("revoked_at"))))
                .optional();
    }

    boolean rotateRefreshToken(long oldId, long replacementId, Instant usedAt) {
        return jdbc.sql("UPDATE refresh_tokens SET used_at = :usedAt, replaced_by_id = :replacement WHERE id = :id AND used_at IS NULL")
                .param("usedAt", Timestamp.from(usedAt)).param("replacement", replacementId).param("id", oldId)
                .update() == 1;
    }

    void revokeFamily(String familyId, Instant revokedAt) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = :revokedAt WHERE family_id = :familyId AND revoked_at IS NULL")
                .param("revokedAt", Timestamp.from(revokedAt)).param("familyId", familyId).update();
    }

    AccountPrincipal principal(AccountRecord account) {
        Instant now = Instant.now();
        return new AccountPrincipal(account.id(), account.employeeId(), account.publicId(), account.employeePublicId(),
                account.displayName(), activeRoles(account.id(), now), activeScopes(account.id(), now));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    record AccountRecord(long id, String publicId, long employeeId, String employeePublicId, String displayName,
            String passwordHash, String status) {
    }

    record RefreshRecord(long id, long accountId, String familyId, Instant expiresAt, Instant usedAt,
            Instant revokedAt) {
    }
}
