package com.query.insight.audit;

import com.query.insight.common.Hashing;
import com.query.insight.common.PublicIdGenerator;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private static final String GENESIS_HASH = "0".repeat(64);
    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Long actorAccountId, String action, String targetType, String targetPublicId,
            String result, String dataScope, String traceId) {
        Instant occurredAt = Instant.now();
        String previous = jdbc.sql("SELECT record_hash FROM audit_logs ORDER BY id DESC LIMIT 1")
                .query(String.class).optional().orElse(GENESIS_HASH);
        String canonical = String.join("|", previous, occurredAt.toString(), String.valueOf(actorAccountId), action,
                targetType, String.valueOf(targetPublicId), result, traceId);
        jdbc.sql("""
                INSERT INTO audit_logs(public_id, occurred_at, actor_account_id, action, target_type,
                  target_public_id, result, data_scope, trace_id, prev_hash, record_hash)
                VALUES (:publicId, :occurredAt, :actor, :action, :targetType, :targetId, :result, :scope,
                  :traceId, :previous, :recordHash)
                """)
                .param("publicId", PublicIdGenerator.next())
                .param("occurredAt", Timestamp.from(occurredAt))
                .param("actor", actorAccountId)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetPublicId)
                .param("result", result)
                .param("scope", dataScope)
                .param("traceId", traceId)
                .param("previous", previous)
                .param("recordHash", Hashing.sha256(canonical))
                .update();
    }
}
