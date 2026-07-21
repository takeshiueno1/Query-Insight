package com.query.insight.audit;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditController {
    private final JdbcClient jdbc;

    public AuditController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AUDITOR','SYSTEM_ADMIN','HR')")
    List<AuditResponse> list(@RequestParam(required = false) String action) {
        return jdbc.sql("""
                SELECT l.public_id,l.occurred_at,l.action,l.target_type,l.target_public_id,l.result,
                  l.data_scope,l.trace_id,a.public_id actor_public_id
                FROM audit_logs l LEFT JOIN accounts a ON a.id=l.actor_account_id
                WHERE (:action IS NULL OR l.action=:action) ORDER BY l.occurred_at DESC LIMIT 200
                """).param("action", action)
                .query((rs, row) -> new AuditResponse(rs.getString("public_id"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("actor_public_id"),
                        rs.getString("action"), rs.getString("target_type"), rs.getString("target_public_id"),
                        rs.getString("result"), rs.getString("data_scope"), rs.getString("trace_id"))).list();
    }

    record AuditResponse(String publicId, Instant occurredAt, String actorPublicId, String action,
            String targetType, String targetPublicId, String result, String dataScope, String traceId) {
    }
}
