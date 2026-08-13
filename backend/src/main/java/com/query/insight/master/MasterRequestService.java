package com.query.insight.master;

import com.query.insight.audit.AuditService;
import com.query.insight.common.ApiException;
import com.query.insight.common.PublicIdGenerator;
import com.query.insight.notification.NotificationService;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class MasterRequestService {
    private static final Pattern SKILL_CODE = Pattern.compile("[A-Z0-9_]{1,40}");
    private static final Pattern CERTIFICATION_CODE = Pattern.compile("[A-Z0-9_]{1,50}");
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final NotificationService notifications;
    private final AuditService audit;

    public MasterRequestService(JdbcClient jdbc, ObjectMapper objectMapper,
            NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.notifications = notifications;
        this.audit = audit;
    }

    public enum Type { SKILL, CERTIFICATION }
    public enum Status { SUBMITTED, APPROVED, RETURNED }

    @Transactional
    public Row create(String accountPublicId, Type type, JsonNode payload, String traceId) {
        long accountId = accountId(accountPublicId);
        JsonNode normalized = normalizeAndValidate(type, payload);
        String publicId = PublicIdGenerator.next();
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO master_addition_requests(public_id,requested_by_account_id,master_type,
                  proposed_payload_json,status,version,requested_at,created_master_public_id)
                VALUES (:publicId,:accountId,:type,:payload,'SUBMITTED',0,:now,NULL)
                """).param("publicId", publicId).param("accountId", accountId).param("type", type.name())
                .param("payload", jsonParameter(normalized)).param("now", Timestamp.from(now)).update();
        audit.record(accountId, "MASTER_REQUEST_SUBMIT", "MASTER_REQUEST", publicId,
                "SUCCESS", "SELF", traceId);
        return find(publicId);
    }

    public List<Row> mine(String accountPublicId) {
        return rows(" WHERE requested_by_account_id=:accountId ORDER BY requested_at DESC,id DESC")
                .param("accountId", accountId(accountPublicId)).query(this::map).list();
    }

    public List<Row> adminList(String accountPublicId, Set<String> roles, Status status) {
        requireReviewer(accountPublicId, roles);
        return rows(" WHERE status=:status ORDER BY requested_at,id").param("status", status.name())
                .query(this::map).list();
    }

    @Transactional
    public Row approve(String accountPublicId, Set<String> roles, String requestPublicId,
            long version, String traceId) {
        requireReviewer(accountPublicId, roles);
        long actorId = accountId(accountPublicId);
        Row current = find(requestPublicId);
        requireSubmitted(current, version);
        JsonNode payload = normalizeAndValidate(current.type(), current.payload());
        requireUnique(current.type(), text(payload, "code"), text(payload, "name"));
        String masterPublicId = PublicIdGenerator.next();
        if (current.type() == Type.SKILL) {
            jdbc.sql("""
                    INSERT INTO skill_masters(public_id,code,name,category,description,status)
                    VALUES (:publicId,:code,:name,:category,:description,'ACTIVE')
                    """).param("publicId", masterPublicId).param("code", text(payload, "code"))
                    .param("name", text(payload, "name")).param("category", text(payload, "category"))
                    .param("description", text(payload, "description")).update();
        } else {
            jdbc.sql("""
                    INSERT INTO certification_masters(public_id,code,name,issuer,status)
                    VALUES (:publicId,:code,:name,:issuer,'ACTIVE')
                    """).param("publicId", masterPublicId).param("code", text(payload, "code"))
                    .param("name", text(payload, "name")).param("issuer", text(payload, "issuer")).update();
        }
        Instant now = Instant.now();
        int updated = jdbc.sql("""
                UPDATE master_addition_requests SET status='APPROVED',version=version+1,decided_at=:now,
                  decided_by_account_id=:actorId,created_master_public_id=:masterId
                WHERE id=:id AND status='SUBMITTED' AND version=:version
                """).param("now", Timestamp.from(now)).param("actorId", actorId).param("masterId", masterPublicId)
                .param("id", current.id()).param("version", version).update();
        requireUpdated(updated);
        notifyRequester(current, "MASTER_REQUEST_APPROVED", "マスタ追加申請が承認されました",
                "master-request:" + current.publicId() + ":approved");
        audit.record(actorId, "MASTER_REQUEST_APPROVE", "MASTER_REQUEST", current.publicId(),
                "SUCCESS", "ALL", traceId);
        return find(requestPublicId);
    }

    @Transactional
    public Row returnRequest(String accountPublicId, Set<String> roles, String requestPublicId,
            long version, String reason, String traceId) {
        requireReviewer(accountPublicId, roles);
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RETURN_REASON_INVALID",
                    "差戻し理由は1～1000文字で入力してください");
        }
        long actorId = accountId(accountPublicId);
        Row current = find(requestPublicId);
        requireSubmitted(current, version);
        int updated = jdbc.sql("""
                UPDATE master_addition_requests SET status='RETURNED',version=version+1,return_reason=:reason,
                  decided_at=:now,decided_by_account_id=:actorId WHERE id=:id AND status='SUBMITTED' AND version=:version
                """).param("reason", reason).param("now", Timestamp.from(Instant.now()))
                .param("actorId", actorId).param("id", current.id()).param("version", version).update();
        requireUpdated(updated);
        notifyRequester(current, "MASTER_REQUEST_RETURNED", "マスタ追加申請が差し戻されました",
                "master-request:" + current.publicId() + ":returned");
        audit.record(actorId, "MASTER_REQUEST_RETURN", "MASTER_REQUEST", current.publicId(),
                "SUCCESS", "ALL", traceId);
        return find(requestPublicId);
    }

    private void requireReviewer(String accountPublicId, Set<String> roles) {
        if (roles == null || !roles.contains("ADMIN")) throw forbidden();
        int grants = jdbc.sql("""
                SELECT COUNT(*) FROM accounts a JOIN permission_grants g ON g.account_id=a.id
                JOIN roles r ON r.id=g.role_id AND r.code='ADMIN' AND r.status='ACTIVE'
                WHERE a.public_id=:accountId AND a.status='ACTIVE' AND g.scope_type='ALL'
                  AND g.revoked_at IS NULL AND g.valid_from<=CURRENT_TIMESTAMP
                  AND (g.valid_to IS NULL OR g.valid_to>CURRENT_TIMESTAMP)
                """).param("accountId", accountPublicId).query(Integer.class).single();
        if (grants == 0) throw forbidden();
    }

    private ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "この操作を行う権限がありません");
    }

    private JsonNode normalizeAndValidate(Type type, JsonNode payload) {
        if (payload == null || !payload.isObject()) throw invalid("申請内容が不正です");
        ObjectNode normalized = (ObjectNode) payload.deepCopy();
        String code = required(payload, "code", type == Type.SKILL ? 40 : 50).toUpperCase(Locale.ROOT);
        String name = required(payload, "name", type == Type.SKILL ? 100 : 150);
        Pattern pattern = type == Type.SKILL ? SKILL_CODE : CERTIFICATION_CODE;
        if (!pattern.matcher(code).matches()) throw invalid("コードは半角英大文字・数字・アンダースコアで入力してください");
        normalized.put("code", code);
        normalized.put("name", name);
        if (type == Type.SKILL) {
            normalized.put("category", required(payload, "category", 40));
            normalized.put("description", required(payload, "description", 500));
        } else {
            normalized.put("issuer", required(payload, "issuer", 150));
        }
        return normalized;
    }

    private String required(JsonNode payload, String field, int max) {
        if (!payload.path(field).isTextual()) throw invalid(field + "を入力してください");
        String value = payload.path(field).asText().trim();
        if (value.isBlank() || value.length() > max) throw invalid(field + "の文字数が不正です");
        return value;
    }

    private void requireUnique(Type type, String code, String name) {
        String table = type == Type.SKILL ? "skill_masters" : "certification_masters";
        int duplicate = jdbc.sql("SELECT COUNT(*) FROM " + table
                        + " WHERE UPPER(TRIM(code))=:code OR LOWER(TRIM(name))=:name")
                .param("code", code.toUpperCase(Locale.ROOT)).param("name", name.trim().toLowerCase(Locale.ROOT))
                .query(Integer.class).single();
        if (duplicate > 0) throw new ApiException(HttpStatus.CONFLICT, "MASTER_DUPLICATE",
                "同じコードまたは名称のマスタが存在します");
    }

    private void requireSubmitted(Row current, long version) {
        if (current.status() != Status.SUBMITTED) throw conflict();
        if (current.version() != version) throw conflict();
    }

    private void requireUpdated(int updated) {
        if (updated != 1) throw conflict();
    }

    private ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT",
                "他の利用者が更新しました。再読み込みしてください");
    }

    private void notifyRequester(Row current, String type, String title, String dedupeKey) {
        Long employeeId = jdbc.sql("SELECT employee_id FROM accounts WHERE id=:id")
                .param("id", current.requestedByAccountId()).query(Long.class).single();
        notifications.notifyEmployee(employeeId, type, title, "申請結果を確認してください。",
                "/master-requests", dedupeKey);
    }

    private long accountId(String publicId) {
        return jdbc.sql("SELECT id FROM accounts WHERE public_id=:id AND status='ACTIVE'")
                .param("id", publicId).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_INVALID",
                        "有効なアカウントが必要です"));
    }

    private Row find(String publicId) {
        return rows(" WHERE public_id=:publicId").param("publicId", publicId).query(this::map).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MASTER_REQUEST_NOT_FOUND",
                        "対象の申請が見つかりません"));
    }

    private JdbcClient.StatementSpec rows(String suffix) {
        return jdbc.sql("""
                SELECT id,public_id,requested_by_account_id,master_type,CAST(proposed_payload_json AS VARCHAR) payload,
                  status,version,return_reason,requested_at,decided_at,created_master_public_id
                FROM master_addition_requests
                """ + suffix);
    }

    private Row map(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new Row(rs.getLong("id"), rs.getString("public_id").trim(),
                rs.getLong("requested_by_account_id"), Type.valueOf(rs.getString("master_type")),
                parse(rs.getString("payload")), Status.valueOf(rs.getString("status")), rs.getLong("version"),
                rs.getString("return_reason"), rs.getTimestamp("requested_at").toInstant(),
                decided == null ? null : decided.toInstant(), trim(rs.getString("created_master_public_id")));
    }

    private SqlParameterValue jsonParameter(JsonNode payload) {
        try {
            return new SqlParameterValue(Types.OTHER, objectMapper.writeValueAsString(payload));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Master request payload could not be serialized", exception);
        }
    }

    private JsonNode parse(String value) {
        try {
            JsonNode parsed = objectMapper.readTree(value);
            return parsed.isTextual() ? objectMapper.readTree(parsed.asText()) : parsed;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored master request payload is invalid", exception);
        }
    }

    private String text(JsonNode payload, String field) {
        return payload.path(field).asText();
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "MASTER_REQUEST_INVALID", message);
    }

    public record Row(long id, String publicId, long requestedByAccountId, Type type, JsonNode payload,
            Status status, long version, String returnReason, Instant requestedAt, Instant decidedAt,
            String createdMasterPublicId) {
    }
}
