package com.query.insight.talent;

import com.query.insight.common.ApiException;
import com.query.insight.common.PublicIdGenerator;
import com.query.insight.talent.TalentPayloads.Payload;
import com.query.insight.talent.TalentSubmission.Status;
import com.query.insight.talent.TalentSubmission.Type;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class TalentSubmissionRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public TalentSubmissionRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Row createDraft(long employeeId, Type type, Payload payload, Long baseRecordVersion, Instant now) {
        String publicId = PublicIdGenerator.next();
        jdbc.sql("""
                INSERT INTO talent_submissions(public_id,employee_id,talent_type,logical_public_id,revision_no,
                  status,payload_json,base_record_version,version,created_at,updated_at)
                VALUES (:publicId,:employeeId,:type,:publicId,1,'DRAFT',:payload,
                  :baseRecordVersion,0,:now,:now)
                """)
                .param("publicId", publicId)
                .param("employeeId", employeeId)
                .param("type", type.name())
                .param("payload", jsonParameter(payload))
                .param("baseRecordVersion", baseRecordVersion)
                .param("now", Timestamp.from(now))
                .update();
        return findByPublicId(publicId).orElseThrow();
    }

    public Row updateDraft(long id, Status expectedStatus, Payload payload, long version, Instant now) {
        int updated = jdbc.sql("""
                UPDATE talent_submissions
                SET payload_json=:payload,version=version+1,updated_at=:now
                WHERE id=:id AND status=:status AND version=:version
                """)
                .param("payload", jsonParameter(payload))
                .param("now", Timestamp.from(now))
                .param("id", id)
                .param("status", expectedStatus.name())
                .param("version", version)
                .update();
        requireUpdated(updated);
        return findById(id);
    }

    public Row createRevision(Row returned, Payload payload, long version, Instant now) {
        RevisionHead head = jdbc.sql("""
                SELECT id,revision_no,status,version FROM talent_submissions
                WHERE employee_id=:employeeId AND talent_type=:type AND logical_public_id=:logicalPublicId
                ORDER BY revision_no DESC LIMIT 1 FOR UPDATE
                """)
                .param("employeeId", returned.employeeId())
                .param("type", returned.type().name())
                .param("logicalPublicId", returned.logicalPublicId())
                .query((rs, row) -> new RevisionHead(rs.getLong("id"), rs.getInt("revision_no"),
                        Status.valueOf(rs.getString("status")), rs.getLong("version")))
                .single();
        if (head.id() != returned.id() || head.status() != Status.RETURNED || head.version() != version) {
            throw optimisticConflict();
        }
        String publicId = PublicIdGenerator.next();
        jdbc.sql("""
                INSERT INTO talent_submissions(public_id,employee_id,talent_type,logical_public_id,revision_no,
                  status,payload_json,base_record_version,version,created_at,updated_at)
                VALUES (:publicId,:employeeId,:type,:logicalPublicId,:revisionNo,'DRAFT',:payload,
                  :baseRecordVersion,0,:now,:now)
                """)
                .param("publicId", publicId)
                .param("employeeId", returned.employeeId())
                .param("type", returned.type().name())
                .param("logicalPublicId", returned.logicalPublicId())
                .param("revisionNo", head.revisionNo() + 1)
                .param("payload", jsonParameter(payload))
                .param("baseRecordVersion", returned.baseRecordVersion())
                .param("now", Timestamp.from(now))
                .update();
        return findByPublicId(publicId).orElseThrow();
    }

    public Row markSubmitted(long id, Status expectedStatus, long version, Instant now) {
        int updated = jdbc.sql("""
                UPDATE talent_submissions
                SET status='SUBMITTED',submitted_at=:now,return_reason=NULL,version=version+1,updated_at=:now
                WHERE id=:id AND status=:status AND version=:version
                """)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .param("status", expectedStatus.name())
                .param("version", version)
                .update();
        requireUpdated(updated);
        return findById(id);
    }

    public Optional<Row> findByPublicId(String publicId) {
        return jdbc.sql(selectSql() + " WHERE public_id=:publicId")
                .param("publicId", publicId)
                .query(this::mapRow)
                .optional();
    }

    public Payload payload(Row row) {
        try {
            return switch (row.type()) {
                case SKILL -> objectMapper.treeToValue(row.payload(), TalentPayloads.SkillPayload.class);
                case KNOWLEDGE -> objectMapper.treeToValue(row.payload(), TalentPayloads.KnowledgePayload.class);
                case CAREER -> objectMapper.treeToValue(row.payload(), TalentPayloads.CareerPayload.class);
                case CERTIFICATION -> objectMapper.treeToValue(row.payload(), TalentPayloads.CertificationPayload.class);
            };
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored talent payload is invalid", exception);
        }
    }

    public List<Row> findByEmployee(long employeeId, Type type) {
        return jdbc.sql(selectSql() + " WHERE employee_id=:employeeId AND talent_type=:type "
                        + "ORDER BY updated_at DESC,id DESC")
                .param("employeeId", employeeId).param("type", type.name())
                .query(this::mapRow).list();
    }

    private Row findById(long id) {
        return jdbc.sql(selectSql() + " WHERE id=:id")
                .param("id", id)
                .query(this::mapRow)
                .single();
    }

    private String selectSql() {
        return """
                SELECT id,public_id,employee_id,talent_type,logical_public_id,revision_no,status,
                  CAST(payload_json AS VARCHAR) payload_json,base_record_version,version,submitted_at,
                  decided_at,reviewer_account_id,return_reason,created_at,updated_at
                FROM talent_submissions
                """;
    }

    private Row mapRow(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
        Number baseRecordVersion = (Number) rs.getObject("base_record_version");
        Number reviewerAccountId = (Number) rs.getObject("reviewer_account_id");
        Timestamp submittedAt = rs.getTimestamp("submitted_at");
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new Row(
                rs.getLong("id"),
                rs.getString("public_id").trim(),
                rs.getLong("employee_id"),
                Type.valueOf(rs.getString("talent_type")),
                rs.getString("logical_public_id").trim(),
                rs.getInt("revision_no"),
                Status.valueOf(rs.getString("status")),
                parse(rs.getString("payload_json")),
                baseRecordVersion == null ? null : baseRecordVersion.longValue(),
                rs.getLong("version"),
                submittedAt == null ? null : submittedAt.toInstant(),
                decidedAt == null ? null : decidedAt.toInstant(),
                reviewerAccountId == null ? null : reviewerAccountId.longValue(),
                rs.getString("return_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private String serialize(Payload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Talent payload could not be serialized", exception);
        }
    }

    private SqlParameterValue jsonParameter(Payload payload) {
        return new SqlParameterValue(Types.OTHER, serialize(payload));
    }

    private JsonNode parse(String payload) {
        try {
            JsonNode parsed = objectMapper.readTree(payload);
            return parsed.isTextual() ? objectMapper.readTree(parsed.asText()) : parsed;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored talent payload is invalid", exception);
        }
    }

    private void requireUpdated(int updated) {
        if (updated != 1) {
            throw optimisticConflict();
        }
    }

    private ApiException optimisticConflict() {
        return new ApiException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT",
                "他の利用者が更新しました。再読み込みしてください");
    }

    private record RevisionHead(long id, int revisionNo, Status status, long version) {
    }

    public record Row(long id, String publicId, long employeeId, Type type, String logicalPublicId,
            int revisionNo, Status status, JsonNode payload, Long baseRecordVersion, long version,
            Instant submittedAt, Instant decidedAt, Long reviewerAccountId, String returnReason,
            Instant createdAt, Instant updatedAt) {
    }
}
