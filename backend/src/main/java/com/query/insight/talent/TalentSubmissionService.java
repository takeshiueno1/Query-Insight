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

    @Transactional
    public TalentSubmissionRepository.Row create(String employeePublicId, Type type, Payload payload,
            String actorAccountPublicId, String traceId) {
        validate(payload);
        long employeeId = employeeId(employeePublicId);
        long actorId = accountId(actorAccountPublicId);
        Instant now = Instant.now();
        var draft = repository.createDraft(employeeId, type, payload, null, now);
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
}
