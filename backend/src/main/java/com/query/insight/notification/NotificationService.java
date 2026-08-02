package com.query.insight.notification;

import com.query.insight.common.PublicIdGenerator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
    private final JdbcClient jdbc;

    public NotificationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void notifyEmployee(long employeeId, String type, String title, String body, String path, String dedupeKey) {
        jdbc.sql("SELECT id FROM accounts WHERE employee_id=:employeeId AND status='ACTIVE'")
                .param("employeeId", employeeId).query(Long.class).optional()
                .ifPresent(accountId -> notifyAccount(accountId, type, title, body, path, dedupeKey));
    }

    public void notifyExecutives(String type, String title, String body, String path, String dedupePrefix) {
        List<Long> accounts = jdbc.sql("""
                SELECT DISTINCT a.id FROM accounts a
                JOIN permission_grants g ON g.account_id=a.id AND g.revoked_at IS NULL
                JOIN roles r ON r.id=g.role_id AND r.code='EXECUTIVE' AND r.status='ACTIVE'
                WHERE a.status='ACTIVE' AND g.scope_type='ALL' AND g.valid_from<=CURRENT_TIMESTAMP
                  AND (g.valid_to IS NULL OR g.valid_to>CURRENT_TIMESTAMP)
                """).query(Long.class).list();
        accounts.forEach(accountId -> notifyAccount(accountId, type, title, body, path,
                dedupePrefix + ":" + accountId));
    }

    private void notifyAccount(long accountId, String type, String title, String body, String path, String dedupeKey) {
        if (jdbc.sql("SELECT COUNT(*) FROM notifications WHERE dedupe_key=:dedupeKey")
                .param("dedupeKey", dedupeKey).query(Integer.class).single() > 0) return;
        try {
            jdbc.sql("""
                    INSERT INTO notifications(public_id,recipient_account_id,type,title,body,link_path,created_at,dedupe_key)
                    VALUES (:publicId,:accountId,:type,:title,:body,:path,:createdAt,:dedupeKey)
                    """).param("publicId", PublicIdGenerator.next()).param("accountId", accountId)
                    .param("type", type).param("title", title).param("body", body).param("path", path)
                    .param("createdAt", Timestamp.from(Instant.now())).param("dedupeKey", dedupeKey).update();
        } catch (DuplicateKeyException ignored) {
            // 同一イベントの並行処理は一意制約で片方だけを残す。
        }
    }
}
