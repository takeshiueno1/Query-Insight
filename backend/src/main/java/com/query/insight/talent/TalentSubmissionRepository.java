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

    public Row createDraftForOfficial(long employeeId, Type type, String logicalPublicId,
            long baseRecordVersion, Payload payload, Instant now) {
        Optional<RevisionHead> latest = jdbc.sql("""
                SELECT id,revision_no,status,version FROM talent_submissions
                WHERE employee_id=:employeeId AND talent_type=:type AND logical_public_id=:logicalPublicId
                ORDER BY revision_no DESC LIMIT 1 FOR UPDATE
                """).param("employeeId", employeeId).param("type", type.name())
                .param("logicalPublicId", logicalPublicId)
                .query((rs, row) -> new RevisionHead(rs.getLong("id"), rs.getInt("revision_no"),
                        Status.valueOf(rs.getString("status")), rs.getLong("version"))).optional();
        if (latest.isPresent() && latest.get().status() != Status.APPROVED
                && latest.get().status() != Status.SUPERSEDED) {
            throw new ApiException(HttpStatus.CONFLICT, "TALENT_ACTIVE_REVISION_EXISTS",
                    "このタレント情報には処理中の申請があります");
        }
        String publicId = PublicIdGenerator.next();
        int revisionNo = latest.map(head -> head.revisionNo() + 1).orElse(1);
        jdbc.sql("""
                INSERT INTO talent_submissions(public_id,employee_id,talent_type,logical_public_id,revision_no,
                  status,payload_json,base_record_version,version,created_at,updated_at)
                VALUES (:publicId,:employeeId,:type,:logicalPublicId,:revisionNo,'DRAFT',:payload,
                  :baseRecordVersion,0,:now,:now)
                """).param("publicId", publicId).param("employeeId", employeeId).param("type", type.name())
                .param("logicalPublicId", logicalPublicId).param("revisionNo", revisionNo)
                .param("payload", jsonParameter(payload)).param("baseRecordVersion", baseRecordVersion)
                .param("now", Timestamp.from(now)).update();
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

    public Optional<Row> findForManager(String submissionPublicId, String managerEmployeePublicId) {
        return jdbc.sql(selectSql() + """
                 JOIN employees e ON e.id=talent_submissions.employee_id
                 WHERE talent_submissions.public_id=:submissionPublicId
                   AND e.manager_employee_id=(SELECT id FROM employees WHERE public_id=:managerPublicId)
                   AND e.employment_status<>'RETIRED'
                """)
                .param("submissionPublicId", submissionPublicId)
                .param("managerPublicId", managerEmployeePublicId)
                .query(this::mapRow).optional();
    }

    public List<Row> findForManager(String managerEmployeePublicId, Status status) {
        return jdbc.sql(selectSql() + """
                 JOIN employees e ON e.id=talent_submissions.employee_id
                 WHERE e.manager_employee_id=(SELECT id FROM employees WHERE public_id=:managerPublicId)
                   AND e.employment_status<>'RETIRED' AND talent_submissions.status=:status
                 ORDER BY talent_submissions.submitted_at,talent_submissions.id
                """)
                .param("managerPublicId", managerEmployeePublicId).param("status", status.name())
                .query(this::mapRow).list();
    }

    public List<Row> approvedPredecessors(Row target) {
        return jdbc.sql(selectSql() + """
                 WHERE employee_id=:employeeId AND talent_type=:type AND logical_public_id=:logicalPublicId
                   AND status='APPROVED' AND id<>:id ORDER BY revision_no
                """)
                .param("employeeId", target.employeeId()).param("type", target.type().name())
                .param("logicalPublicId", target.logicalPublicId()).param("id", target.id())
                .query(this::mapRow).list();
    }

    public List<Row> history(long employeeId, String logicalPublicId) {
        return jdbc.sql(selectSql() + """
                 WHERE employee_id=:employeeId AND logical_public_id=:logicalPublicId
                 ORDER BY revision_no DESC
                """).param("employeeId", employeeId).param("logicalPublicId", logicalPublicId)
                .query(this::mapRow).list();
    }

    public Row markReturned(long id, long version, long reviewerAccountId, String reason, Instant now) {
        int updated = jdbc.sql("""
                UPDATE talent_submissions SET status='RETURNED',return_reason=:reason,decided_at=:now,
                  reviewer_account_id=:reviewer,version=version+1,updated_at=:now
                WHERE id=:id AND status='SUBMITTED' AND version=:version
                """).param("reason", reason).param("now", Timestamp.from(now))
                .param("reviewer", reviewerAccountId).param("id", id).param("version", version).update();
        requireUpdated(updated);
        return findById(id);
    }

    public Row markApproved(long id, long version, long reviewerAccountId, Instant now) {
        int updated = jdbc.sql("""
                UPDATE talent_submissions SET status='APPROVED',decided_at=:now,
                  reviewer_account_id=:reviewer,version=version+1,updated_at=:now
                WHERE id=:id AND status='SUBMITTED' AND version=:version
                """).param("now", Timestamp.from(now)).param("reviewer", reviewerAccountId)
                .param("id", id).param("version", version).update();
        requireUpdated(updated);
        return findById(id);
    }

    public Row markSuperseded(long id, Instant now) {
        int updated = jdbc.sql("""
                UPDATE talent_submissions SET status='SUPERSEDED',version=version+1,updated_at=:now
                WHERE id=:id AND status='APPROVED'
                """).param("now", Timestamp.from(now)).param("id", id).update();
        requireUpdated(updated);
        return findById(id);
    }

    private Row findById(long id) {
        return jdbc.sql(selectSql() + " WHERE id=:id")
                .param("id", id)
                .query(this::mapRow)
                .single();
    }

    private String selectSql() {
        return """
                SELECT talent_submissions.id,talent_submissions.public_id,talent_submissions.employee_id,
                  talent_submissions.talent_type,talent_submissions.logical_public_id,
                  talent_submissions.revision_no,talent_submissions.status,
                  CAST(talent_submissions.payload_json AS VARCHAR) payload_json,
                  talent_submissions.base_record_version,talent_submissions.version,
                  talent_submissions.submitted_at,talent_submissions.decided_at,
                  talent_submissions.reviewer_account_id,talent_submissions.return_reason,
                  talent_submissions.created_at,talent_submissions.updated_at
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
