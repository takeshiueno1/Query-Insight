package com.query.insight.evaluation;

import com.query.insight.audit.AuditService;
import com.query.insight.common.ApiException;
import com.query.insight.common.PublicIdGenerator;
import com.query.insight.evaluation.EvaluationWorkflow.Action;
import com.query.insight.evaluation.EvaluationWorkflow.Status;
import com.query.insight.notification.NotificationService;
import com.query.insight.status.ProfileStatusService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationWorkflowService {
    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ProfileStatusService profileStatusService;

    public EvaluationWorkflowService(JdbcClient jdbc, NotificationService notifications, AuditService audit,
            ProfileStatusService profileStatusService) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.audit = audit;
        this.profileStatusService = profileStatusService;
    }

    public List<ManagerListItem> managerList(String accountPublicId, String evaluatorEmployeePublicId) {
        requireManager(accountPublicId, evaluatorEmployeePublicId);
        return jdbc.sql("""
                SELECT t.public_id,CONCAT(e.last_name,' ',e.first_name) employee_name,d.name department_name,
                  p.name period_name,t.status,t.version,p.manager_deadline
                FROM evaluation_targets t JOIN employees e ON e.id=t.employee_id
                LEFT JOIN departments d ON d.id=e.department_id JOIN evaluation_periods p ON p.id=t.period_id
                WHERE t.evaluator_employee_id=(SELECT id FROM employees WHERE public_id=:employeePublicId)
                ORDER BY p.manager_deadline,t.public_id
                """).param("employeePublicId", evaluatorEmployeePublicId)
                .query((rs, row) -> new ManagerListItem(rs.getString("public_id"), rs.getString("employee_name"),
                        rs.getString("department_name"), rs.getString("period_name"), rs.getString("status"),
                        rs.getLong("version"), rs.getTimestamp("manager_deadline").toInstant().isBefore(Instant.now())))
                .list();
    }

    public ManagerEvaluationResponse managerDetail(String accountPublicId, String evaluatorEmployeePublicId,
            String targetPublicId) {
        requireManager(accountPublicId, evaluatorEmployeePublicId);
        Target target = managerTarget(evaluatorEmployeePublicId, targetPublicId);
        return managerResponse(target);
    }

    @Transactional
    public ManagerEvaluationResponse saveManager(String evaluatorEmployeePublicId, String actorAccountPublicId,
            String targetPublicId, ManagerSaveRequest request, String traceId) {
        long actorId = requireManager(actorAccountPublicId, evaluatorEmployeePublicId);
        Target target = managerTarget(evaluatorEmployeePublicId, targetPublicId);
        requireVersion(target, request.version());
        Status next = EvaluationWorkflow.requireTransition(Status.parse(target.status()), Action.MANAGER_SAVE);
        validateDetails(target.id(), request.details());
        validateSummaryLength(request.summary());
        long managerEvaluationId = target.managerEvaluationId() == null
                ? createManagerEvaluation(target.id(), target.employeeId(), request.summary())
                : updateManagerEvaluation(target.managerEvaluationId(), request.summary());
        replaceManagerDetails(managerEvaluationId, request.details());
        long nextVersion = updateTarget(target, next, managerEvaluationId, request.version(), false, null, null);
        recordEvent(target, managerEvaluationId, actorId, "MANAGER_SAVE", next, null, null, traceId);
        audit.record(actorId, "EVALUATION_MANAGER_SAVE", "EVALUATION_TARGET", target.publicId(), "SUCCESS",
                "SUBORDINATES", traceId);
        return managerResponse(managerTarget(evaluatorEmployeePublicId, targetPublicId), nextVersion);
    }

    @Transactional
    public ManagerEvaluationResponse returnToEmployee(String evaluatorEmployeePublicId, String actorAccountPublicId,
            String targetPublicId, long version, String reason, String traceId) {
        requireReason(reason);
        requireManager(actorAccountPublicId, evaluatorEmployeePublicId);
        Target target = managerTarget(evaluatorEmployeePublicId, targetPublicId);
        requireVersion(target, version);
        throw new ApiException(HttpStatus.GONE, "SELF_EVALUATION_RETURN_RETIRED",
                "本人への評価差戻しは廃止されました。上長評価を編集して最終承認者へ提出してください");
    }

    @Transactional
    public ManagerEvaluationResponse submitManager(String evaluatorEmployeePublicId, String actorAccountPublicId,
            String targetPublicId, long version, String traceId) {
        long actorId = requireManager(actorAccountPublicId, evaluatorEmployeePublicId);
        Target target = managerTarget(evaluatorEmployeePublicId, targetPublicId);
        requireVersion(target, version);
        Status next = EvaluationWorkflow.requireTransition(Status.parse(target.status()), Action.MANAGER_SUBMIT);
        if (target.managerEvaluationId() == null) throw invalidState();
        ManagerEvaluation manager = managerEvaluation(target.managerEvaluationId());
        if (manager.summary() == null || manager.summary().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MANAGER_SUMMARY_REQUIRED", "上長総評を入力してください");
        }
        validateSummaryLength(manager.summary());
        List<ScoringRow> scoring = scoringRows(target.id(), target.managerEvaluationId());
        if (scoring.size() != 6) throw invalidState();
        for (ScoringRow row : scoring) {
            if (row.comment() == null || row.comment().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MANAGER_COMMENT_REQUIRED",
                        "提出には各評価軸のコメントが必要です");
            }
        }
        List<EvaluationRank> ranks = scoring.stream().map(ScoringRow::rank).toList();
        EvaluationRank overall = EvaluationRank.overall(ranks);
        BigDecimal score = ranks.stream().map(rank -> new BigDecimal(rank.score())).reduce(BigDecimal.ZERO,
                BigDecimal::add).divide(BigDecimal.valueOf(ranks.size()), 2, RoundingMode.HALF_UP);
        Instant now = Instant.now();
        jdbc.sql("""
                UPDATE manager_evaluations SET status='SUBMITTED',weighted_score=:score,grade=:grade,
                  submitted_at=:now,version=version+1 WHERE id=:id AND status='DRAFT'
                """).param("score", score).param("grade", overall.name()).param("now", Timestamp.from(now))
                .param("id", target.managerEvaluationId()).update();
        long nextVersion = updateTarget(target, next, target.managerEvaluationId(), version, false, null, null);
        recordEvent(target, target.managerEvaluationId(), actorId, "MANAGER_SUBMIT", next, null, null, traceId);
        notifications.notifyExecutives("EXECUTIVE_REVIEW", "上長評価の最終承認をお願いします",
                target.employeeName() + "さんの上長評価が提出されました。", "/executive/evaluations/" + target.publicId(),
                dedupe(target, "MANAGER_SUBMIT", nextVersion));
        audit.record(actorId, "EVALUATION_MANAGER_SUBMIT", "EVALUATION_TARGET", target.publicId(), "SUCCESS",
                "SUBORDINATES", traceId);
        return managerResponse(managerTarget(evaluatorEmployeePublicId, targetPublicId), nextVersion);
    }

    public ExecutiveDashboard executiveDashboard(String accountPublicId) {
        requireExecutive(accountPublicId);
        DashboardCounts counts = jdbc.sql("""
                SELECT COUNT(*) total,
                  SUM(CASE WHEN t.status='EXECUTIVE_REVIEW' THEN 1 ELSE 0 END) pending,
                  SUM(CASE WHEN t.status='FINALIZED' THEN 1 ELSE 0 END) finalized,
                  SUM(CASE
                    WHEN t.status='DRAFT' AND p.self_deadline<CURRENT_TIMESTAMP THEN 1
                    WHEN t.status IN ('SELF_RETURNED','SELF_SUBMITTED','MANAGER_IN_PROGRESS','MANAGER_RETURNED')
                      AND p.manager_deadline<CURRENT_TIMESTAMP THEN 1 ELSE 0 END) overdue
                FROM evaluation_targets t JOIN evaluation_periods p ON p.id=t.period_id
                """).query((rs, row) -> new DashboardCounts(rs.getInt("total"), rs.getInt("pending"),
                        rs.getInt("finalized"), rs.getInt("overdue"))).single();
        List<ExecutiveListItem> items = jdbc.sql("""
                SELECT t.public_id,CONCAT(e.last_name,' ',e.first_name) employee_name,d.name department_name,
                  t.status,t.version,t.final_score,t.final_grade,p.name period_name,
                  CASE WHEN t.status='DRAFT' THEN p.self_deadline
                    WHEN t.status IN ('SELF_RETURNED','SELF_SUBMITTED','MANAGER_IN_PROGRESS','MANAGER_RETURNED') THEN p.manager_deadline
                    ELSE NULL END active_deadline
                FROM evaluation_targets t JOIN employees e ON e.id=t.employee_id
                LEFT JOIN departments d ON d.id=e.department_id JOIN evaluation_periods p ON p.id=t.period_id
                ORDER BY CASE WHEN t.status='EXECUTIVE_REVIEW' THEN 0 ELSE 1 END,p.manager_deadline,t.public_id
                """).query((rs, row) -> new ExecutiveListItem(rs.getString("public_id"), rs.getString("employee_name"),
                        rs.getString("department_name"), rs.getString("status"), rs.getLong("version"),
                        rs.getBigDecimal("final_score"), rs.getString("final_grade"),
                        rs.getString("period_name"), isPast(rs.getTimestamp("active_deadline")))).list();
        List<Distribution> distributions = jdbc.sql("""
                SELECT '全社' department_name,t.final_grade,COUNT(*) employee_count
                FROM evaluation_targets t WHERE t.status='FINALIZED'
                GROUP BY t.final_grade
                UNION ALL
                SELECT COALESCE(d.name,'所属未設定') department_name,t.final_grade,COUNT(*) employee_count
                FROM evaluation_targets t JOIN employees e ON e.id=t.employee_id
                LEFT JOIN departments d ON d.id=e.department_id WHERE t.status='FINALIZED'
                GROUP BY d.name,t.final_grade ORDER BY department_name,final_grade
                """).query((rs, row) -> new Distribution(rs.getString("department_name"),
                        rs.getString("final_grade"), rs.getInt("employee_count"))).list();
        return new ExecutiveDashboard(counts, items, distributions);
    }

    public ExecutiveEvaluationResponse executiveDetail(String accountPublicId, String targetPublicId) {
        requireExecutive(accountPublicId);
        return executiveResponse(target(targetPublicId));
    }

    @Transactional
    public ExecutiveEvaluationResponse approve(String actorAccountPublicId, String targetPublicId, long version,
            String comment, String traceId) {
        long actorId = requireExecutive(actorAccountPublicId);
        Target target = target(targetPublicId);
        requireVersion(target, version);
        Status next = EvaluationWorkflow.requireTransition(Status.parse(target.status()), Action.EXECUTIVE_APPROVE);
        ManagerEvaluation manager = managerEvaluationRequired(target);
        ensureProfileStatusSnapshot(manager, target.employeeId());
        Instant now = Instant.now();
        jdbc.sql("UPDATE manager_evaluations SET status='FINALIZED',finalized_at=:now WHERE id=:id AND status='SUBMITTED'")
                .param("now", Timestamp.from(now)).param("id", manager.id()).update();
        long nextVersion = updateTarget(target, next, manager.id(), version, false, manager.score(), manager.grade());
        recordEvent(target, manager.id(), actorId, "EXECUTIVE_APPROVE", next, null, stripOrNull(comment), traceId);
        notifications.notifyEmployee(target.employeeId(), "EVALUATION_FINALIZED", "評価が確定しました",
                "確定した評価結果を確認できます。", "/evaluations/self",
                dedupe(target, "EXECUTIVE_APPROVE_EMPLOYEE", nextVersion));
        notifications.notifyEmployee(target.evaluatorId(), "EVALUATION_FINALIZED", "担当評価が確定しました",
                target.employeeName() + "さんの評価が最終承認されました。", "/evaluations/manager/" + target.publicId(),
                dedupe(target, "EXECUTIVE_APPROVE_MANAGER", nextVersion));
        audit.record(actorId, "EVALUATION_EXECUTIVE_APPROVE", "EVALUATION_TARGET", target.publicId(), "SUCCESS",
                "ALL", traceId);
        return executiveResponse(target(targetPublicId));
    }

    @Transactional
    public ExecutiveEvaluationResponse returnToManager(String actorAccountPublicId, String targetPublicId, long version,
            String reason, String traceId) {
        requireReason(reason);
        long actorId = requireExecutive(actorAccountPublicId);
        Target target = target(targetPublicId);
        requireVersion(target, version);
        Status next = EvaluationWorkflow.requireTransition(Status.parse(target.status()), Action.EXECUTIVE_RETURN);
        long submittedId = managerEvaluationRequired(target).id();
        long draftId = copyCurrentToDraft(target, "SUBMITTED");
        long nextVersion = updateTarget(target, next, draftId, version, false, null, null);
        recordEvent(target, submittedId, actorId, "EXECUTIVE_RETURN", next, reason, null, traceId);
        notifyManager(target, "上長評価が差し戻されました", reason, nextVersion, "EXECUTIVE_RETURN");
        audit.record(actorId, "EVALUATION_EXECUTIVE_RETURN", "EVALUATION_TARGET", target.publicId(), "SUCCESS",
                "ALL", traceId);
        return executiveResponse(target(targetPublicId));
    }

    @Transactional
    public ExecutiveEvaluationResponse reopen(String actorAccountPublicId, String targetPublicId, long version,
            String reason, String traceId) {
        requireReason(reason);
        long actorId = requireExecutive(actorAccountPublicId);
        Target target = target(targetPublicId);
        requireVersion(target, version);
        Status next = EvaluationWorkflow.requireTransition(Status.parse(target.status()), Action.EXECUTIVE_REOPEN);
        long finalizedId = managerEvaluationRequired(target).id();
        long draftId = copyCurrentToDraft(target, "FINALIZED");
        long nextVersion = updateTarget(target, next, draftId, version, false, null, null);
        recordEvent(target, finalizedId, actorId, "EXECUTIVE_REOPEN", next, reason, null, traceId);
        notifyManager(target, "確定評価が再オープンされました", reason, nextVersion, "EXECUTIVE_REOPEN");
        audit.record(actorId, "EVALUATION_EXECUTIVE_REOPEN", "EVALUATION_TARGET", target.publicId(), "SUCCESS",
                "ALL", traceId);
        return executiveResponse(target(targetPublicId));
    }

    public FinalResult finalResult(String employeePublicId) {
        Target target = latestOpenTarget(employeePublicId)
                .orElseThrow(() -> notFound("評価対象が見つかりません"));
        return publishedFinalResult(target)
                .orElseThrow(() -> notFound("確定した評価結果が見つかりません"));
    }

    public Optional<FinalResult> publishedFinalResult(String employeePublicId) {
        return latestOpenTarget(employeePublicId).flatMap(this::publishedFinalResult);
    }

    private Optional<Target> latestOpenTarget(String employeePublicId) {
        return jdbc.sql(targetSelect()
                        + " WHERE e.public_id=:employeePublicId AND p.status='OPEN'"
                        + " ORDER BY p.start_date DESC,t.id DESC LIMIT 1")
                .param("employeePublicId", employeePublicId).query(this::mapTarget).optional();
    }

    private Optional<FinalResult> publishedFinalResult(Target target) {
        if (!"FINALIZED".equals(target.status()) || target.managerEvaluationId() == null) return Optional.empty();
        Optional<ManagerEvaluation> managerResult = publishedManagerEvaluation(
                        target.managerEvaluationId(), target.id())
                .filter(manager -> "FINALIZED".equals(manager.status()));
        if (managerResult.isEmpty()) return Optional.empty();
        ManagerEvaluation manager = managerResult.get();
        List<FinalDetail> details = jdbc.sql("""
                SELECT c.axis_code,c.display_name,m.level manager_level,m.comment
                FROM evaluation_criteria c JOIN evaluation_periods p ON p.criteria_version_id=c.criteria_version_id
                JOIN evaluation_targets t ON t.period_id=p.id
                JOIN manager_evaluation_details m ON m.manager_evaluation_id=:managerId AND m.axis_code=c.axis_code
                WHERE t.id=:targetId ORDER BY c.sort_order
                """).param("managerId", manager.id()).param("targetId", target.id())
                .query((rs, row) -> new FinalDetail(rs.getString("axis_code"), rs.getString("display_name"),
                        EvaluationRank.fromLevel(rs.getInt("manager_level")).name(), rs.getString("comment"))).list();
        return Optional.of(new FinalResult(target.status(), target.finalGrade(), manager.summary(), details,
                target.finalizedAt()));
    }

    private ManagerEvaluationResponse managerResponse(Target target) {
        return managerResponse(target, target.version());
    }

    private ManagerEvaluationResponse managerResponse(Target target, long version) {
        ManagerEvaluation manager = target.managerEvaluationId() == null ? null : managerEvaluation(target.managerEvaluationId());
        Map<String, ManagerValue> managerValues = manager == null ? Map.of() : managerValues(manager.id());
        List<ComparisonDetail> details = comparisonDetails(target, managerValues);
        return new ManagerEvaluationResponse(target.publicId(), target.employeePublicId(), target.employeeName(),
                target.departmentName(), target.periodName(), target.status(), version, target.late(),
                manager == null ? null : manager.summary(), manager == null ? null : manager.score(),
                manager == null ? null : manager.grade(), details);
    }

    private ExecutiveEvaluationResponse executiveResponse(Target target) {
        ManagerEvaluation manager = target.managerEvaluationId() == null ? null : managerEvaluation(target.managerEvaluationId());
        List<ComparisonDetail> details = comparisonDetails(target,
                manager == null ? Map.of() : managerValues(manager.id()));
        List<WorkflowEvent> events = jdbc.sql("""
                SELECT action,from_status,to_status,reason,comment,late,deadline_type,occurred_at
                FROM evaluation_workflow_events WHERE target_id=:targetId ORDER BY occurred_at,id
                """).param("targetId", target.id()).query((rs, row) -> new WorkflowEvent(rs.getString("action"),
                        rs.getString("from_status"), rs.getString("to_status"), rs.getString("reason"),
                        rs.getString("comment"), rs.getBoolean("late"), rs.getString("deadline_type"),
                        rs.getTimestamp("occurred_at").toInstant())).list();
        return new ExecutiveEvaluationResponse(target.publicId(), target.employeeName(), target.departmentName(),
                target.periodName(), target.status(), target.version(), target.late(),
                manager == null ? null : manager.summary(), manager == null ? null : manager.score(),
                manager == null ? null : manager.grade(), target.finalScore(), target.finalGrade(), details, events);
    }

    private List<ComparisonDetail> comparisonDetails(Target target, Map<String, ManagerValue> managerValues) {
        return jdbc.sql("""
                SELECT c.axis_code,c.display_name,c.description,s.level self_level,s.evidence
                FROM evaluation_criteria c JOIN evaluation_periods p ON p.criteria_version_id=c.criteria_version_id
                JOIN evaluation_targets t ON t.period_id=p.id
                LEFT JOIN self_evaluation_details s ON s.target_id=t.id AND s.axis_code=c.axis_code
                WHERE t.id=:targetId ORDER BY c.sort_order
                """).param("targetId", target.id()).query((rs, row) -> {
                    ManagerValue value = managerValues.get(rs.getString("axis_code"));
                    return new ComparisonDetail(rs.getString("axis_code"), rs.getString("display_name"),
                            rs.getString("description"), (Integer) rs.getObject("self_level"), rs.getString("evidence"),
                            value == null ? null : value.rank(), value == null ? null : value.comment());
                }).list();
    }

    private Map<String, ManagerValue> managerValues(long managerEvaluationId) {
        Map<String, ManagerValue> values = new LinkedHashMap<>();
        jdbc.sql("SELECT axis_code,level,comment FROM manager_evaluation_details WHERE manager_evaluation_id=:id")
                .param("id", managerEvaluationId).query((rs, row) -> {
                    values.put(rs.getString("axis_code"), new ManagerValue(
                            EvaluationRank.fromLevel(rs.getInt("level")).name(), rs.getString("comment")));
                    return 1;
                }).list();
        return values;
    }

    private long createManagerEvaluation(long targetId, long employeeId, String summary) {
        long profileStatusSnapshotId = profileStatusService.recalculate(employeeId).id();
        int revision = jdbc.sql("SELECT COALESCE(MAX(revision_no),0)+1 FROM manager_evaluations WHERE target_id=:targetId")
                .param("targetId", targetId).query(Integer.class).single();
        String publicId = PublicIdGenerator.next();
        try {
            jdbc.sql("""
                    INSERT INTO manager_evaluations(public_id,target_id,revision_no,status,summary,
                      profile_status_snapshot_id,version)
                    VALUES (:publicId,:targetId,:revision,'DRAFT',:summary,:snapshotId,0)
                    """).param("publicId", publicId).param("targetId", targetId).param("revision", revision)
                    .param("summary", stripOrNull(summary)).param("snapshotId", profileStatusSnapshotId).update();
        } catch (DuplicateKeyException exception) {
            throw conflict();
        }
        return jdbc.sql("SELECT id FROM manager_evaluations WHERE public_id=:publicId").param("publicId", publicId)
                .query(Long.class).single();
    }

    private long updateManagerEvaluation(long id, String summary) {
        int updated = jdbc.sql("UPDATE manager_evaluations SET summary=:summary,version=version+1 WHERE id=:id AND status='DRAFT'")
                .param("summary", stripOrNull(summary)).param("id", id).update();
        if (updated == 0) throw invalidState();
        return id;
    }

    private void replaceManagerDetails(long managerEvaluationId, List<ManagerDetailInput> details) {
        jdbc.sql("DELETE FROM manager_evaluation_details WHERE manager_evaluation_id=:id")
                .param("id", managerEvaluationId).update();
        details.forEach(detail -> jdbc.sql("""
                INSERT INTO manager_evaluation_details(manager_evaluation_id,axis_code,level,comment)
                VALUES (:id,:axisCode,:level,:comment)
                """).param("id", managerEvaluationId).param("axisCode", detail.axisCode())
                .param("level", EvaluationRank.fromCode(detail.rank()).level())
                .param("comment", stripOrNull(detail.comment())).update());
    }

    private void validateDetails(long targetId, List<ManagerDetailInput> details) {
        if (details == null) throw new ApiException(HttpStatus.BAD_REQUEST, "MANAGER_DETAILS_REQUIRED", "評価項目を入力してください");
        Set<String> expected = jdbc.sql("""
                SELECT c.axis_code FROM evaluation_criteria c JOIN evaluation_periods p
                  ON p.criteria_version_id=c.criteria_version_id JOIN evaluation_targets t ON t.period_id=p.id
                WHERE t.id=:targetId
                """).param("targetId", targetId).query(String.class).list().stream().collect(Collectors.toSet());
        Set<String> actual = details.stream().map(ManagerDetailInput::axisCode).collect(Collectors.toSet());
        if (details.size() != expected.size() || !actual.equals(expected)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MANAGER_AXES_INVALID", "すべての評価項目を重複なく指定してください");
        }
        for (ManagerDetailInput detail : details) {
            EvaluationRank.fromCode(detail.rank());
            if (detail.comment() != null && detail.comment().length() > 1500) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MANAGER_COMMENT_TOO_LONG", "項目コメントは1500文字以内で入力してください");
            }
        }
    }

    private List<ScoringRow> scoringRows(long targetId, long managerEvaluationId) {
        return jdbc.sql("""
                SELECT m.level manager_level,m.comment
                FROM manager_evaluation_details m JOIN evaluation_targets t ON t.id=:targetId
                JOIN evaluation_periods p ON p.id=t.period_id
                JOIN evaluation_criteria c ON c.criteria_version_id=p.criteria_version_id AND c.axis_code=m.axis_code
                WHERE m.manager_evaluation_id=:managerId ORDER BY c.sort_order
                """).param("targetId", targetId).param("managerId", managerEvaluationId)
                .query((rs, row) -> new ScoringRow(EvaluationRank.fromLevel(rs.getInt("manager_level")),
                        rs.getString("comment"))).list();
    }

    private long copyCurrentToDraft(Target target, String expectedStatus) {
        ManagerEvaluation current = managerEvaluationRequired(target);
        if (!expectedStatus.equals(current.status())) throw invalidState();
        long draftId = createManagerEvaluation(target.id(), target.employeeId(), current.summary());
        jdbc.sql("""
                INSERT INTO manager_evaluation_details(manager_evaluation_id,axis_code,level,comment)
                SELECT :draftId,axis_code,level,comment FROM manager_evaluation_details WHERE manager_evaluation_id=:currentId
                """).param("draftId", draftId).param("currentId", current.id()).update();
        return draftId;
    }

    private long updateTarget(Target target, Status next, Long managerEvaluationId, long version,
            boolean clearCurrent, BigDecimal finalScore, String finalGrade) {
        Instant now = Instant.now();
        int updated = jdbc.sql("""
                UPDATE evaluation_targets SET status=:status,
                  current_manager_evaluation_id=:managerId,final_score=:finalScore,final_grade=:finalGrade,
                  finalized_at=:finalizedAt,version=version+1
                WHERE id=:id AND version=:version
                """).param("status", next.name()).param("managerId", clearCurrent ? null : managerEvaluationId)
                .param("finalScore", finalScore).param("finalGrade", finalGrade)
                .param("finalizedAt", next == Status.FINALIZED ? Timestamp.from(now) : null)
                .param("id", target.id()).param("version", version).update();
        if (updated == 0) throw conflict();
        return version + 1;
    }

    private void recordEvent(Target target, Long managerEvaluationId, long actorId, String action, Status next,
            String reason, String comment, String traceId) {
        jdbc.sql("""
                INSERT INTO evaluation_workflow_events(public_id,target_id,manager_evaluation_id,actor_account_id,
                  action,from_status,to_status,reason,comment,late,deadline_type,occurred_at,trace_id)
                VALUES (:publicId,:targetId,:managerId,:actorId,:action,:fromStatus,:toStatus,:reason,:comment,
                  :late,'MANAGER',:occurredAt,:traceId)
                """).param("publicId", PublicIdGenerator.next()).param("targetId", target.id())
                .param("managerId", managerEvaluationId).param("actorId", actorId).param("action", action)
                .param("fromStatus", target.status()).param("toStatus", next.name()).param("reason", stripOrNull(reason))
                .param("comment", stripOrNull(comment)).param("late", target.late())
                .param("occurredAt", Timestamp.from(Instant.now())).param("traceId", traceId).update();
    }

    private void notifyManager(Target target, String title, String body, long version, String action) {
        notifications.notifyEmployee(target.evaluatorId(), "MANAGER_EVALUATION_RETURNED", title, body.strip(),
                "/evaluations/manager/" + target.publicId(), dedupe(target, action, version));
    }

    private Target managerTarget(String evaluatorEmployeePublicId, String targetPublicId) {
        Target target = target(targetPublicId);
        long evaluatorId = jdbc.sql("SELECT id FROM employees WHERE public_id=:employeePublicId")
                .param("employeePublicId", evaluatorEmployeePublicId).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "MANAGER_ASSIGNMENT_REQUIRED",
                        "直属部下の評価だけを操作できます"));
        if (target.evaluatorId() != evaluatorId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MANAGER_ASSIGNMENT_REQUIRED",
                    "直属部下の評価だけを操作できます");
        }
        return target;
    }

    private Target target(String targetPublicId) {
        return jdbc.sql(targetSelect() + " WHERE t.public_id=:targetPublicId")
                .param("targetPublicId", targetPublicId).query(this::mapTarget).optional()
                .orElseThrow(() -> notFound("評価対象が見つかりません"));
    }

    private String targetSelect() {
        return """
                SELECT t.id,t.public_id,t.status,t.version,t.employee_id,t.evaluator_employee_id,
                  t.current_manager_evaluation_id,t.final_score,t.final_grade,t.finalized_at,
                  e.public_id employee_public_id,
                  CONCAT(e.last_name,' ',e.first_name) employee_name,d.name department_name,p.name period_name,
                  p.manager_deadline
                FROM evaluation_targets t JOIN employees e ON e.id=t.employee_id
                LEFT JOIN departments d ON d.id=e.department_id JOIN evaluation_periods p ON p.id=t.period_id
                """;
    }

    private Target mapTarget(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp deadline = rs.getTimestamp("manager_deadline");
        return new Target(rs.getLong("id"), rs.getString("public_id"), rs.getString("status"), rs.getLong("version"),
                rs.getLong("employee_id"), rs.getLong("evaluator_employee_id"),
                (Long) rs.getObject("current_manager_evaluation_id"), rs.getBigDecimal("final_score"),
                rs.getString("final_grade"), rs.getString("employee_public_id"), rs.getString("employee_name"),
                rs.getString("department_name"), rs.getString("period_name"),
                deadline != null && deadline.toInstant().isBefore(Instant.now()),
                rs.getTimestamp("finalized_at") == null ? null : rs.getTimestamp("finalized_at").toInstant());
    }

    private ManagerEvaluation managerEvaluationRequired(Target target) {
        if (target.managerEvaluationId() == null) throw invalidState();
        return managerEvaluation(target.managerEvaluationId());
    }

    private ManagerEvaluation managerEvaluation(long id) {
        return managerEvaluationOptional(id).orElseThrow(EvaluationWorkflowService::invalidState);
    }

    private Optional<ManagerEvaluation> managerEvaluationOptional(long id) {
        return jdbc.sql("""
                SELECT id,status,summary,weighted_score,grade,profile_status_snapshot_id
                FROM manager_evaluations WHERE id=:id
                """)
                .param("id", id).query((rs, row) -> new ManagerEvaluation(rs.getLong("id"), rs.getString("status"),
                        rs.getString("summary"), rs.getBigDecimal("weighted_score"), rs.getString("grade"),
                        (Long) rs.getObject("profile_status_snapshot_id")))
                .optional();
    }

    private Optional<ManagerEvaluation> publishedManagerEvaluation(long id, long targetId) {
        return jdbc.sql("""
                SELECT id,status,summary,weighted_score,grade,profile_status_snapshot_id
                FROM manager_evaluations WHERE id=:id AND target_id=:targetId
                """)
                .param("id", id).param("targetId", targetId)
                .query((rs, row) -> new ManagerEvaluation(rs.getLong("id"), rs.getString("status"),
                        rs.getString("summary"), rs.getBigDecimal("weighted_score"), rs.getString("grade"),
                        (Long) rs.getObject("profile_status_snapshot_id")))
                .optional();
    }

    private void ensureProfileStatusSnapshot(ManagerEvaluation manager, long employeeId) {
        if (manager.profileStatusSnapshotId() != null) return;
        long snapshotId = profileStatusService.recalculate(employeeId).id();
        jdbc.sql("""
                UPDATE manager_evaluations SET profile_status_snapshot_id=:snapshotId
                WHERE id=:id AND profile_status_snapshot_id IS NULL
                """).param("snapshotId", snapshotId).param("id", manager.id()).update();
    }

    private long requireExecutive(String accountPublicId) {
        return jdbc.sql("""
                SELECT a.id FROM accounts a JOIN permission_grants g ON g.account_id=a.id AND g.revoked_at IS NULL
                JOIN roles r ON r.id=g.role_id WHERE a.public_id=:publicId AND a.status='ACTIVE'
                  AND r.code='OFFICER' AND r.status='ACTIVE' AND g.scope_type='ALL'
                  AND g.valid_from<=CURRENT_TIMESTAMP
                  AND (g.valid_to IS NULL OR g.valid_to>CURRENT_TIMESTAMP)
                """).param("publicId", accountPublicId).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "EXECUTIVE_REQUIRED", "経営者権限が必要です"));
    }

    private long requireManager(String accountPublicId, String employeePublicId) {
        return jdbc.sql("""
                SELECT a.id FROM accounts a JOIN employees e ON e.id=a.employee_id
                JOIN permission_grants g ON g.account_id=a.id AND g.revoked_at IS NULL
                JOIN roles r ON r.id=g.role_id WHERE a.public_id=:accountPublicId AND a.status='ACTIVE'
                  AND e.public_id=:employeePublicId AND r.code='OFFICER' AND r.status='ACTIVE'
                  AND g.scope_type='SUBORDINATES' AND g.valid_from<=CURRENT_TIMESTAMP
                  AND (g.valid_to IS NULL OR g.valid_to>CURRENT_TIMESTAMP)
                """).param("accountPublicId", accountPublicId).param("employeePublicId", employeePublicId)
                .query(Long.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "MANAGER_SCOPE_REQUIRED",
                        "上長評価権限が必要です"));
    }

    private static boolean isPast(Timestamp deadline) {
        return deadline != null && deadline.toInstant().isBefore(Instant.now());
    }

    private void requireVersion(Target target, long version) {
        if (target.version() != version) throw conflict();
    }

    private static void validateSummaryLength(String summary) {
        if (summary != null && summary.length() > 3000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MANAGER_SUMMARY_TOO_LONG",
                    "上長総評は3000文字以内で入力してください");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RETURN_REASON_REQUIRED", "差戻し・再オープン理由を入力してください");
        }
        if (reason.length() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RETURN_REASON_TOO_LONG", "理由は1000文字以内で入力してください");
        }
    }

    private static String stripOrNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String dedupe(Target target, String action, long version) {
        return "EVAL:" + target.publicId() + ":" + action + ":" + version;
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT", "他の利用者が更新しました。再読み込みしてください");
    }

    private static ApiException invalidState() {
        return new ApiException(HttpStatus.CONFLICT, "EVALUATION_STATE_INVALID", "現在の状態ではこの評価操作を実行できません");
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "EVALUATION_NOT_FOUND", message);
    }

    private record Target(long id, String publicId, String status, long version, long employeeId, long evaluatorId,
            Long managerEvaluationId, BigDecimal finalScore, String finalGrade, String employeePublicId,
            String employeeName, String departmentName, String periodName, boolean late, Instant finalizedAt) {
    }
    private record ManagerEvaluation(long id, String status, String summary, BigDecimal score, String grade,
            Long profileStatusSnapshotId) {
    }
    private record ManagerValue(String rank, String comment) {
    }
    private record ScoringRow(EvaluationRank rank, String comment) {
    }

    public record ManagerSaveRequest(long version, List<ManagerDetailInput> details, String summary) {
    }
    public record ManagerDetailInput(String axisCode, String rank, String comment) {
    }
    public record ManagerListItem(String publicId, String employeeName, String departmentName, String periodName,
            String status, long version, boolean late) {
    }
    public record ManagerEvaluationResponse(String publicId, String employeePublicId, String employeeName,
            String departmentName, String periodName, String status, long targetVersion, boolean late, String summary,
            BigDecimal score, String grade, List<ComparisonDetail> details) {
    }
    public record ComparisonDetail(String axisCode, String displayName, String description, Integer selfLevel,
            String selfEvidence, String managerRank, String managerComment) {
    }
    public record DashboardCounts(int total, int pending, int finalized, int overdue) {
    }
    public record ExecutiveListItem(String publicId, String employeeName, String departmentName, String status,
            long version, BigDecimal finalScore, String finalGrade, String periodName, boolean late) {
    }
    public record Distribution(String departmentName, String grade, int employeeCount) {
    }
    public record ExecutiveDashboard(DashboardCounts counts, List<ExecutiveListItem> items,
            List<Distribution> distributions) {
    }
    public record ExecutiveEvaluationResponse(String publicId, String employeeName, String departmentName,
            String periodName, String status, long targetVersion, boolean late, String summary, BigDecimal score,
            String grade, BigDecimal finalScore, String finalGrade, List<ComparisonDetail> details,
            List<WorkflowEvent> events) {
    }
    public record WorkflowEvent(String action, String fromStatus, String toStatus, String reason, String comment,
            boolean late, String deadlineType, Instant occurredAt) {
    }
    public record FinalResult(String status, String finalRank, String summary,
            List<FinalDetail> details, Instant finalizedAt) {
    }
    public record FinalDetail(String axisCode, String displayName, String managerRank, String comment) {
    }
}
