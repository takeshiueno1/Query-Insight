package com.query.insight.talent;

import com.query.insight.audit.AuditService;
import com.query.insight.common.ApiException;
import com.query.insight.common.PublicIdGenerator;
import com.query.insight.notification.NotificationService;
import com.query.insight.talent.TalentPayloads.Payload;
import com.query.insight.talent.TalentSubmission.Action;
import com.query.insight.talent.TalentSubmission.Status;
import com.query.insight.talent.TalentSubmission.Type;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import tools.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TalentSubmissionService {
    private final JdbcClient jdbc;
    private final TalentSubmissionRepository repository;
    private final NotificationService notifications;
    private final AuditService audit;

    public TalentSubmissionService(JdbcClient jdbc, TalentSubmissionRepository repository,
            NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.notifications = notifications;
        this.audit = audit;
    }

    public List<TalentSubmissionRepository.Row> mine(String employeePublicId, Type type) {
        return repository.findByEmployee(employeeId(employeePublicId), type);
    }

    public TalentSubmissionRepository.Row own(String employeePublicId, String submissionPublicId) {
        return ownSubmission(employeePublicId, submissionPublicId);
    }

    public List<TalentSubmissionRepository.Row> history(String employeePublicId, String logicalPublicId) {
        return repository.history(employeeId(employeePublicId), logicalPublicId);
    }

    public List<ManagerListItem> managerList(String managerEmployeePublicId, Status status) {
        return jdbc.sql("""
                SELECT s.public_id,s.talent_type,s.status,s.version,s.submitted_at,
                  e.public_id employee_public_id,e.last_name,e.first_name
                FROM talent_submissions s JOIN employees e ON e.id=s.employee_id
                WHERE e.manager_employee_id=(SELECT id FROM employees WHERE public_id=:managerPublicId)
                  AND e.employment_status<>'RETIRED' AND s.status=:status
                ORDER BY s.submitted_at,s.id
                """).param("managerPublicId", managerEmployeePublicId).param("status", status.name())
                .query((rs, row) -> new ManagerListItem(rs.getString("public_id").trim(),
                        Type.valueOf(rs.getString("talent_type")), Status.valueOf(rs.getString("status")),
                        rs.getLong("version"), rs.getTimestamp("submitted_at").toInstant(),
                        rs.getString("employee_public_id").trim(),
                        rs.getString("last_name") + " " + rs.getString("first_name"))).list();
    }

    public ManagerDetail managerDetail(String managerEmployeePublicId, String submissionPublicId) {
        var current = managerSubmission(managerEmployeePublicId, submissionPublicId);
        EmployeeLabel employee = jdbc.sql("SELECT public_id,last_name,first_name FROM employees WHERE id=:id")
                .param("id", current.employeeId()).query((rs, row) -> new EmployeeLabel(
                        rs.getString("public_id").trim(), rs.getString("last_name") + " " + rs.getString("first_name")))
                .single();
        List<AttachmentView> attachments = jdbc.sql("""
                SELECT public_id,file_name,content_type,size_bytes,scan_status FROM talent_attachments
                WHERE submission_id=:id AND scan_status='CLEAN' ORDER BY id
                """).param("id", current.id()).query((rs, row) -> new AttachmentView(
                        rs.getString("public_id").trim(), rs.getString("file_name"), rs.getString("content_type"),
                        rs.getLong("size_bytes"), rs.getString("scan_status"))).list();
        List<EventView> events = jdbc.sql("""
                SELECT action,from_status,to_status,reason,occurred_at FROM talent_submission_events
                WHERE submission_id=:id ORDER BY occurred_at,id
                """).param("id", current.id()).query((rs, row) -> new EventView(rs.getString("action"),
                        rs.getString("from_status"), rs.getString("to_status"), rs.getString("reason"),
                        rs.getTimestamp("occurred_at").toInstant())).list();
        JsonNode predecessor = repository.approvedPredecessors(current).stream()
                .reduce((first, second) -> second).map(TalentSubmissionRepository.Row::payload).orElse(null);
        return new ManagerDetail(SubmissionView.from(current), employee, predecessor, attachments, events);
    }

    @Transactional
    public TalentSubmissionRepository.Row create(String employeePublicId, Type type, Payload payload,
            String actorAccountPublicId, String traceId) {
        validate(payload);
        long employeeId = employeeId(employeePublicId);
        long actorId = accountId(actorAccountPublicId);
        Instant now = Instant.now();
        OfficialBase base = officialBase(employeeId, type, payload);
        var draft = base == null
                ? repository.createDraft(employeeId, type, payload, null, now)
                : repository.createDraftForOfficial(employeeId, type, base.publicId(), base.version(), payload, now);
        recordEvent(draft.id(), actorId, "CREATE", null, Status.DRAFT, null, traceId, now);
        audit.record(actorId, "TALENT_CREATE", "TALENT_SUBMISSION", draft.publicId(),
                "SUCCESS", "SELF", traceId);
        return draft;
    }

    @Transactional
    public TalentSubmissionRepository.Row update(String employeePublicId, String submissionPublicId, long version,
            Payload payload, String actorAccountPublicId, String traceId) {
        validate(payload);
        var current = ownSubmission(employeePublicId, submissionPublicId);
        TalentSubmission.requireTransition(current.status(), Action.SAVE);
        Instant now = Instant.now();
        var saved = current.status() == Status.RETURNED
                ? repository.createRevision(current, payload, version, now)
                : repository.updateDraft(current.id(), current.status(), payload, version, now);
        long actorId = accountId(actorAccountPublicId);
        recordEvent(saved.id(), actorId, "SAVE", current.status(), saved.status(), null, traceId, now);
        audit.record(actorId, "TALENT_SAVE", "TALENT_SUBMISSION", saved.publicId(),
                "SUCCESS", "SELF", traceId);
        return saved;
    }

    @Transactional
    public TalentSubmissionRepository.Row submit(String employeePublicId, String actorAccountPublicId,
            String submissionPublicId, long version, String traceId) {
        var current = ownSubmission(employeePublicId, submissionPublicId);
        TalentSubmission.requireTransition(current.status(), Action.SUBMIT);
        validate(repository.payload(current));
        requireAttachmentsClean(current.id());
        Manager manager = manager(current.employeeId());
        Instant now = Instant.now();
        var submitted = repository.markSubmitted(current.id(), current.status(), version, now);
        long actorId = accountId(actorAccountPublicId);
        recordEvent(current.id(), actorId, "SUBMIT", current.status(), Status.SUBMITTED, null, traceId, now);
        notifications.notifyEmployee(manager.employeeId(), "TALENT_REVIEW_REQUEST", "タレント申請の確認依頼",
                "直属社員からタレント情報が申請されました。", "/approvals/talent/" + current.publicId(),
                "talent:" + current.publicId() + ":submit:" + submitted.version());
        audit.record(actorId, "TALENT_SUBMIT", "TALENT_SUBMISSION", current.publicId(),
                "SUCCESS", "SELF", traceId);
        return submitted;
    }

    @Transactional
    public TalentSubmissionRepository.Row approve(String managerEmployeePublicId, String managerAccountPublicId,
            String submissionPublicId, long version, String traceId) {
        var current = managerSubmission(managerEmployeePublicId, submissionPublicId);
        TalentSubmission.requireTransition(current.status(), Action.APPROVE);
        long actorId = accountId(managerAccountPublicId);
        Instant now = Instant.now();
        applyOfficial(current, repository.payload(current), now);
        for (var predecessor : repository.approvedPredecessors(current)) {
            repository.markSuperseded(predecessor.id(), now);
            recordEvent(predecessor.id(), actorId, "SUPERSEDE", Status.APPROVED, Status.SUPERSEDED,
                    null, traceId, now);
        }
        var approved = repository.markApproved(current.id(), version, actorId, now);
        recordEvent(current.id(), actorId, "APPROVE", Status.SUBMITTED, Status.APPROVED, null, traceId, now);
        notifications.notifyEmployee(current.employeeId(), "TALENT_APPROVED", "タレント申請が承認されました",
                "申請内容が正式なタレント情報へ反映されました。", "/talent/" + current.logicalPublicId() + "/history",
                "talent:" + current.publicId() + ":approved");
        audit.record(actorId, "TALENT_APPROVE", "TALENT_SUBMISSION", current.publicId(),
                "SUCCESS", "DIRECT_REPORT", traceId);
        return approved;
    }

    @Transactional
    public TalentSubmissionRepository.Row returnToEmployee(String managerEmployeePublicId,
            String managerAccountPublicId, String submissionPublicId, long version, String reason, String traceId) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RETURN_REASON_INVALID",
                    "差戻し理由は1～1000文字で入力してください");
        }
        var current = managerSubmission(managerEmployeePublicId, submissionPublicId);
        TalentSubmission.requireTransition(current.status(), Action.RETURN);
        long actorId = accountId(managerAccountPublicId);
        Instant now = Instant.now();
        var returned = repository.markReturned(current.id(), version, actorId, reason, now);
        recordEvent(current.id(), actorId, "RETURN", Status.SUBMITTED, Status.RETURNED, reason, traceId, now);
        notifications.notifyEmployee(current.employeeId(), "TALENT_RETURNED", "タレント申請が差し戻されました",
                "差戻し理由を確認して再申請してください。", "/talent/" + current.logicalPublicId() + "/history",
                "talent:" + current.publicId() + ":returned:" + returned.version());
        audit.record(actorId, "TALENT_RETURN", "TALENT_SUBMISSION", current.publicId(),
                "SUCCESS", "DIRECT_REPORT", traceId);
        return returned;
    }

    private void applyOfficial(TalentSubmissionRepository.Row row, Payload payload, Instant now) {
        switch (payload) {
            case TalentPayloads.SkillPayload skill -> applySkill(row, skill, now);
            case TalentPayloads.KnowledgePayload knowledge -> applyKnowledge(row, knowledge, now);
            case TalentPayloads.CareerPayload career -> applyCareer(row, career, now);
            case TalentPayloads.CertificationPayload certification -> applyCertification(row, certification, now);
        }
    }

    private void applySkill(TalentSubmissionRepository.Row row, TalentPayloads.SkillPayload payload, Instant now) {
        long masterId = activeMasterId("skill_masters", payload.masterPublicId());
        if (row.baseRecordVersion() == null) {
            jdbc.sql("""
                    INSERT INTO employee_skills(public_id,employee_id,skill_id,proficiency_level,years_experience,
                      last_used_on,evidence,version,created_at,updated_at)
                    VALUES (:publicId,:employeeId,:masterId,:level,:years,:lastUsed,:evidence,0,:now,:now)
                    """).param("publicId", row.logicalPublicId()).param("employeeId", row.employeeId())
                    .param("masterId", masterId).param("level", payload.level()).param("years", payload.yearsExperience())
                    .param("lastUsed", payload.lastUsedOn()).param("evidence", payload.evidence())
                    .param("now", Timestamp.from(now)).update();
        } else {
            requireOfficialUpdated(jdbc.sql("""
                    UPDATE employee_skills SET skill_id=:masterId,proficiency_level=:level,years_experience=:years,
                      last_used_on=:lastUsed,evidence=:evidence,version=version+1,updated_at=:now
                    WHERE public_id=:publicId AND employee_id=:employeeId AND version=:version
                    """).param("masterId", masterId).param("level", payload.level())
                    .param("years", payload.yearsExperience()).param("lastUsed", payload.lastUsedOn())
                    .param("evidence", payload.evidence()).param("now", Timestamp.from(now))
                    .param("publicId", row.logicalPublicId()).param("employeeId", row.employeeId())
                    .param("version", row.baseRecordVersion()).update());
        }
    }

    private void applyKnowledge(TalentSubmissionRepository.Row row, TalentPayloads.KnowledgePayload payload, Instant now) {
        long masterId = activeMasterId("knowledge_masters", payload.masterPublicId());
        if (row.baseRecordVersion() == null) {
            jdbc.sql("""
                    INSERT INTO employee_knowledge(public_id,employee_id,knowledge_id,proficiency_level,evidence,
                      version,created_at,updated_at) VALUES (:publicId,:employeeId,:masterId,:level,:evidence,0,:now,:now)
                    """).param("publicId", row.logicalPublicId()).param("employeeId", row.employeeId())
                    .param("masterId", masterId).param("level", payload.level()).param("evidence", payload.evidence())
                    .param("now", Timestamp.from(now)).update();
        } else {
            requireOfficialUpdated(jdbc.sql("""
                    UPDATE employee_knowledge SET knowledge_id=:masterId,proficiency_level=:level,evidence=:evidence,
                      version=version+1,updated_at=:now
                    WHERE public_id=:publicId AND employee_id=:employeeId AND version=:version
                    """).param("masterId", masterId).param("level", payload.level()).param("evidence", payload.evidence())
                    .param("now", Timestamp.from(now)).param("publicId", row.logicalPublicId())
                    .param("employeeId", row.employeeId()).param("version", row.baseRecordVersion()).update());
        }
    }

    private void applyCareer(TalentSubmissionRepository.Row row, TalentPayloads.CareerPayload payload, Instant now) {
        if (row.baseRecordVersion() == null) {
            jdbc.sql("""
                    INSERT INTO career_histories(public_id,employee_id,project_name,industry,role_name,start_date,
                      end_date,summary,achievements,technologies,version,created_at,updated_at)
                    VALUES (:publicId,:employeeId,:project,:industry,:role,:startDate,:endDate,:summary,
                      :achievements,:technologies,0,:now,:now)
                    """).param("publicId", row.logicalPublicId()).param("employeeId", row.employeeId())
                    .param("project", payload.projectName()).param("industry", payload.industry())
                    .param("role", payload.roleName()).param("startDate", payload.startDate())
                    .param("endDate", payload.endDate()).param("summary", payload.summary())
                    .param("achievements", payload.achievements()).param("technologies", payload.technologies())
                    .param("now", Timestamp.from(now)).update();
        } else {
            requireOfficialUpdated(jdbc.sql("""
                    UPDATE career_histories SET project_name=:project,industry=:industry,role_name=:role,
                      start_date=:startDate,end_date=:endDate,summary=:summary,achievements=:achievements,
                      technologies=:technologies,version=version+1,updated_at=:now
                    WHERE public_id=:publicId AND employee_id=:employeeId AND version=:version
                    """).param("project", payload.projectName()).param("industry", payload.industry())
                    .param("role", payload.roleName()).param("startDate", payload.startDate())
                    .param("endDate", payload.endDate()).param("summary", payload.summary())
                    .param("achievements", payload.achievements()).param("technologies", payload.technologies())
                    .param("now", Timestamp.from(now)).param("publicId", row.logicalPublicId())
                    .param("employeeId", row.employeeId()).param("version", row.baseRecordVersion()).update());
        }
    }

    private void applyCertification(TalentSubmissionRepository.Row row,
            TalentPayloads.CertificationPayload payload, Instant now) {
        long masterId = activeMasterId("certification_masters", payload.masterPublicId());
        if (row.baseRecordVersion() == null) {
            jdbc.sql("""
                    INSERT INTO employee_certifications(public_id,employee_id,certification_id,acquired_on,
                      expires_on,credential_reference,verification_status,version,created_at,updated_at)
                    VALUES (:publicId,:employeeId,:masterId,:acquired,:expires,:credential,'VERIFIED',0,:now,:now)
                    """).param("publicId", row.logicalPublicId()).param("employeeId", row.employeeId())
                    .param("masterId", masterId).param("acquired", payload.acquiredOn())
                    .param("expires", payload.expiresOn()).param("credential", payload.credentialReference())
                    .param("now", Timestamp.from(now)).update();
        } else {
            requireOfficialUpdated(jdbc.sql("""
                    UPDATE employee_certifications SET certification_id=:masterId,acquired_on=:acquired,
                      expires_on=:expires,credential_reference=:credential,verification_status='VERIFIED',
                      version=version+1,updated_at=:now
                    WHERE public_id=:publicId AND employee_id=:employeeId AND version=:version
                    """).param("masterId", masterId).param("acquired", payload.acquiredOn())
                    .param("expires", payload.expiresOn()).param("credential", payload.credentialReference())
                    .param("now", Timestamp.from(now)).param("publicId", row.logicalPublicId())
                    .param("employeeId", row.employeeId()).param("version", row.baseRecordVersion()).update());
        }
    }

    private long activeMasterId(String table, String publicId) {
        if (!List.of("skill_masters", "knowledge_masters", "certification_masters").contains(table)) {
            throw new IllegalArgumentException("Unsupported master table");
        }
        return jdbc.sql("SELECT id FROM " + table + " WHERE public_id=:publicId AND status='ACTIVE'")
                .param("publicId", publicId).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "MASTER_UNAVAILABLE",
                        "選択したマスタは現在利用できません"));
    }

    private OfficialBase officialBase(long employeeId, Type type, Payload payload) {
        String sql = switch (type) {
            case SKILL -> """
                    SELECT es.public_id,es.version FROM employee_skills es JOIN skill_masters sm ON sm.id=es.skill_id
                    WHERE es.employee_id=:employeeId AND sm.public_id=:masterPublicId
                    """;
            case KNOWLEDGE -> """
                    SELECT ek.public_id,ek.version FROM employee_knowledge ek
                    JOIN knowledge_masters km ON km.id=ek.knowledge_id
                    WHERE ek.employee_id=:employeeId AND km.public_id=:masterPublicId
                    """;
            case CERTIFICATION -> """
                    SELECT ec.public_id,ec.version FROM employee_certifications ec
                    JOIN certification_masters cm ON cm.id=ec.certification_id
                    WHERE ec.employee_id=:employeeId AND cm.public_id=:masterPublicId
                    """;
            case CAREER -> """
                    SELECT ch.public_id,ch.version FROM career_histories ch
                    WHERE ch.employee_id=:employeeId AND ch.project_name=:projectName AND ch.start_date=:startDate
                    """;
        };
        var query = jdbc.sql(sql).param("employeeId", employeeId);
        query = switch (payload) {
            case TalentPayloads.SkillPayload value -> query.param("masterPublicId", value.masterPublicId());
            case TalentPayloads.KnowledgePayload value -> query.param("masterPublicId", value.masterPublicId());
            case TalentPayloads.CertificationPayload value -> query.param("masterPublicId", value.masterPublicId());
            case TalentPayloads.CareerPayload value -> query.param("projectName", value.projectName())
                    .param("startDate", value.startDate());
        };
        return query.query((rs, row) -> new OfficialBase(rs.getString("public_id").trim(), rs.getLong("version")))
                .optional().orElse(null);
    }

    private void requireOfficialUpdated(int updated) {
        if (updated != 1) throw new ApiException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT",
                "正式データが更新されています。再読み込みしてください");
    }

    private TalentSubmissionRepository.Row managerSubmission(String managerEmployeePublicId,
            String submissionPublicId) {
        return repository.findForManager(submissionPublicId, managerEmployeePublicId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TALENT_SUBMISSION_NOT_FOUND",
                        "対象の申請が見つかりません"));
    }

    private TalentSubmissionRepository.Row ownSubmission(String employeePublicId, String submissionPublicId) {
        long employeeId = employeeId(employeePublicId);
        return repository.findByPublicId(submissionPublicId)
                .filter(row -> row.employeeId() == employeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TALENT_SUBMISSION_NOT_FOUND",
                        "対象の申請が見つかりません"));
    }

    private void validate(Payload payload) {
        if (payload == null) throw new ApiException(HttpStatus.BAD_REQUEST, "TALENT_PAYLOAD_INVALID",
                "申請内容を入力してください");
        payload.validate(LocalDate.now(ZoneOffset.UTC));
    }

    private void requireAttachmentsClean(long submissionId) {
        int unsafe = jdbc.sql("""
                SELECT COUNT(*) FROM talent_attachments
                WHERE submission_id=:submissionId AND scan_status<>'CLEAN'
                """).param("submissionId", submissionId).query(Integer.class).single();
        if (unsafe > 0) throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_SCAN_INCOMPLETE",
                "検査が完了していない添付ファイルがあります");
    }

    private Manager manager(long employeeId) {
        return jdbc.sql("""
                SELECT manager.id employee_id
                FROM employees e JOIN employees manager ON manager.id=e.manager_employee_id
                JOIN accounts a ON a.employee_id=manager.id AND a.status='ACTIVE'
                WHERE e.id=:employeeId AND manager.employment_status<>'RETIRED'
                """).param("employeeId", employeeId)
                .query((rs, row) -> new Manager(rs.getLong("employee_id")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "MANAGER_UNAVAILABLE",
                        "現在の直属上長へ申請できません"));
    }

    private long employeeId(String publicId) {
        return jdbc.sql("SELECT id FROM employees WHERE public_id=:publicId AND employment_status<>'RETIRED'")
                .param("publicId", publicId).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND",
                        "対象の社員が見つかりません"));
    }

    private long accountId(String publicId) {
        return jdbc.sql("SELECT id FROM accounts WHERE public_id=:publicId AND status='ACTIVE'")
                .param("publicId", publicId).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_INVALID",
                        "有効なアカウントが必要です"));
    }

    private void recordEvent(long submissionId, long actorId, String action, Status from, Status to,
            String reason, String traceId, Instant now) {
        jdbc.sql("""
                INSERT INTO talent_submission_events(public_id,submission_id,actor_account_id,action,
                  from_status,to_status,reason,occurred_at,trace_id)
                VALUES (:publicId,:submissionId,:actorId,:action,:fromStatus,:toStatus,:reason,:now,:traceId)
                """).param("publicId", PublicIdGenerator.next()).param("submissionId", submissionId)
                .param("actorId", actorId).param("action", action)
                .param("fromStatus", from == null ? null : from.name()).param("toStatus", to.name())
                .param("reason", reason).param("now", Timestamp.from(now)).param("traceId", traceId).update();
    }

    private record Manager(long employeeId) {
    }

    private record OfficialBase(String publicId, long version) {
    }

    public record ManagerListItem(String publicId, Type type, Status status, long version, Instant submittedAt,
            String employeePublicId, String employeeName) {
    }

    public record EmployeeLabel(String publicId, String displayName) {
    }

    public record AttachmentView(String publicId, String fileName, String contentType, long sizeBytes,
            String scanStatus) {
    }

    public record EventView(String action, String fromStatus, String toStatus, String reason, Instant occurredAt) {
    }

    public record SubmissionView(String publicId, Type type, String logicalPublicId, int revisionNo, Status status,
            JsonNode payload, Long baseRecordVersion, long version, Instant submittedAt, Instant decidedAt,
            String returnReason, Instant createdAt, Instant updatedAt) {
        static SubmissionView from(TalentSubmissionRepository.Row row) {
            return new SubmissionView(row.publicId(), row.type(), row.logicalPublicId(), row.revisionNo(),
                    row.status(), row.payload(), row.baseRecordVersion(), row.version(), row.submittedAt(),
                    row.decidedAt(), row.returnReason(), row.createdAt(), row.updatedAt());
        }
    }

    public record ManagerDetail(SubmissionView submission, EmployeeLabel employee,
            JsonNode approvedPredecessorPayload, List<AttachmentView> attachments, List<EventView> events) {
    }
}
