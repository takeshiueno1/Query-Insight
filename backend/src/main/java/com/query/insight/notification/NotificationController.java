package com.query.insight.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final JdbcClient jdbc;

    public NotificationController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<NotificationResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.sql("""
                SELECT n.public_id,n.type,n.title,n.body,n.link_path,n.read_at,n.created_at
                FROM notifications n JOIN accounts a ON a.id=n.recipient_account_id
                WHERE a.public_id=:accountPublicId ORDER BY n.created_at DESC LIMIT 100
                """).param("accountPublicId", jwt.getClaimAsString("accountPublicId"))
                .query((rs, row) -> new NotificationResponse(rs.getString("public_id"), rs.getString("type"),
                        rs.getString("title"), rs.getString("body"), rs.getString("link_path"),
                        rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant())).list();
    }

    @PatchMapping("/{publicId}/read")
    @Transactional
    void read(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        jdbc.sql("""
                UPDATE notifications SET read_at=COALESCE(read_at,:now)
                WHERE public_id=:publicId AND recipient_account_id=(SELECT id FROM accounts WHERE public_id=:accountPublicId)
                """).param("now", Timestamp.from(Instant.now())).param("publicId", publicId)
                .param("accountPublicId", jwt.getClaimAsString("accountPublicId")).update();
    }

    @PatchMapping("/read-all")
    @Transactional
    void readAll(@AuthenticationPrincipal Jwt jwt) {
        jdbc.sql("""
                UPDATE notifications SET read_at=:now WHERE read_at IS NULL
                  AND recipient_account_id=(SELECT id FROM accounts WHERE public_id=:accountPublicId)
                """).param("now", Timestamp.from(Instant.now()))
                .param("accountPublicId", jwt.getClaimAsString("accountPublicId")).update();
    }

    record NotificationResponse(String publicId, String type, String title, String body, String linkPath,
            Instant readAt, Instant createdAt) {
    }
}
