package com.query.insight.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.query.insight.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
class EvaluationWorkflowServiceIntegrationTests {
    @Autowired
    private EvaluationWorkflowService service;
    @Autowired
    private EvaluationService selfService;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void assignedManagerCanEvaluateDraftWithoutSelfInputAndFinalizeWithSnapshotHistoryAndNotifications() {
        Actor manager = actor("manager@query.local");
        Actor executive = actor("qi0039@query.local");
        Target target = target("QI0003");
        jdbc.sql("DELETE FROM self_evaluation_details WHERE target_id="
                        + "(SELECT id FROM evaluation_targets WHERE public_id=:publicId)")
                .param("publicId", target.publicId()).update();
        jdbc.sql("UPDATE evaluation_targets SET status='DRAFT' WHERE public_id=:publicId")
                .param("publicId", target.publicId()).update();
        List<EvaluationWorkflowService.ManagerDetailInput> details = rankDetails(true);

        var saved = service.saveManager(manager.employeePublicId(), manager.accountPublicId(), target.publicId(),
                new EvaluationWorkflowService.ManagerSaveRequest(target.version(), details, "期待役割を安定して果たしています"),
                "TRACE-MANAGER-SAVE");
        assertThat(saved.status()).isEqualTo("MANAGER_IN_PROGRESS");
        assertThat(saved.details()).extracting(EvaluationWorkflowService.ComparisonDetail::managerRank)
                .containsExactly("S", "A", "B", "C", "D", "F");
        var submitted = service.submitManager(manager.employeePublicId(), manager.accountPublicId(), target.publicId(),
                saved.targetVersion(), "TRACE-MANAGER-SUBMIT");
        var finalized = service.approve(executive.accountPublicId(), target.publicId(), submitted.targetVersion(),
                "経営判断に利用できる内容です", "TRACE-EXEC-APPROVE");

        assertThat(finalized.status()).isEqualTo("FINALIZED");
        assertThat(finalized.finalScore()).isEqualByComparingTo("63.33");
        assertThat(finalized.finalGrade()).isEqualTo("C");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM manager_evaluations m JOIN profile_status_snapshots s
                  ON s.id=m.profile_status_snapshot_id
                JOIN evaluation_targets t ON t.id=m.target_id AND t.employee_id=s.employee_id
                WHERE t.public_id=:publicId
                """).param("publicId", target.publicId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(count("evaluation_workflow_events", target.publicId())).isEqualTo(3);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notifications WHERE dedupe_key LIKE :prefix")
                .param("prefix", "EVAL:" + target.publicId() + ":%")
                .query(Integer.class).single()).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE target_public_id=:publicId")
                .param("publicId", target.publicId()).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void nonAssignedManagerCannotOpenTarget() {
        Actor otherManager = actor("qi0006@query.local");
        Target target = target("QI0003");

        assertThatThrownBy(() -> service.managerDetail(otherManager.accountPublicId(),
                otherManager.employeePublicId(), target.publicId()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void managerSaveRejectsMissingAxesUnknownRanksAndOversizedTextAsBadRequests() {
        Actor manager = actor("manager@query.local");
        Target target = target("QI0003");
        jdbc.sql("UPDATE evaluation_targets SET status='DRAFT',current_manager_evaluation_id=NULL "
                        + "WHERE public_id=:publicId")
                .param("publicId", target.publicId()).update();
        List<EvaluationWorkflowService.ManagerDetailInput> valid = rankDetails(false);

        assertBadRequest(() -> service.saveManager(manager.employeePublicId(), manager.accountPublicId(),
                target.publicId(), new EvaluationWorkflowService.ManagerSaveRequest(target.version(),
                        valid.subList(0, 5), "総評"), "TRACE-MISSING-AXIS"));
        List<EvaluationWorkflowService.ManagerDetailInput> unknown = new ArrayList<>(valid);
        unknown.set(0, new EvaluationWorkflowService.ManagerDetailInput("TECHNICAL", "E", "コメント"));
        assertBadRequest(() -> service.saveManager(manager.employeePublicId(), manager.accountPublicId(),
                target.publicId(), new EvaluationWorkflowService.ManagerSaveRequest(target.version(),
                        unknown, "総評"), "TRACE-UNKNOWN-RANK"));
        assertBadRequest(() -> service.saveManager(manager.employeePublicId(), manager.accountPublicId(),
                target.publicId(), new EvaluationWorkflowService.ManagerSaveRequest(target.version(),
                        valid, "x".repeat(3001)), "TRACE-LONG-SUMMARY"));
        List<EvaluationWorkflowService.ManagerDetailInput> longComment = new ArrayList<>(valid);
        longComment.set(0, new EvaluationWorkflowService.ManagerDetailInput("TECHNICAL", "S", "x".repeat(1501)));
        assertBadRequest(() -> service.saveManager(manager.employeePublicId(), manager.accountPublicId(),
                target.publicId(), new EvaluationWorkflowService.ManagerSaveRequest(target.version(),
                        longComment, "総評"), "TRACE-LONG-COMMENT"));
    }

    @Test
    void managerSubmitRequiresEveryCommentAndSummary() {
        Target target = target("QI0003");
        Actor manager = managerActor(target.publicId());
        jdbc.sql("UPDATE evaluation_targets SET status='DRAFT',current_manager_evaluation_id=NULL "
                        + "WHERE public_id=:publicId")
                .param("publicId", target.publicId()).update();
        var saved = service.saveManager(manager.employeePublicId(), manager.accountPublicId(), target.publicId(),
                new EvaluationWorkflowService.ManagerSaveRequest(target.version(), rankDetails(false), null),
                "TRACE-INCOMPLETE-DRAFT");

        assertBadRequest(() -> service.submitManager(manager.employeePublicId(), manager.accountPublicId(),
                target.publicId(), saved.targetVersion(), "TRACE-INCOMPLETE-SUBMIT"));
    }

    @Test
    void returnedEmployeeSubmissionCreatesWorkflowEventAuditAndManagerNotification() {
        Actor employee = actor("ueno");
        Target target = target("QITEST");
        jdbc.sql("UPDATE evaluation_targets SET status='SELF_RETURNED' WHERE public_id=:publicId")
                .param("publicId", target.publicId()).update();
        EvaluationService.SelfEvaluation current = selfService.get(employee.employeePublicId());
        List<EvaluationService.SaveDetail> details = current.details().stream()
                .map(detail -> new EvaluationService.SaveDetail(detail.axisCode(), detail.level(), detail.evidence()))
                .toList();

        selfService.submit(employee.employeePublicId(), employee.accountPublicId(),
                new EvaluationService.SaveRequest(current.version(), details), "TRACE-SELF-SUBMIT");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM evaluation_workflow_events WHERE target_id="
                        + "(SELECT id FROM evaluation_targets WHERE public_id=:publicId) AND action='SELF_SUBMIT'")
                .param("publicId", target.publicId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE target_public_id=:publicId "
                        + "AND action='EVALUATION_SELF_SUBMIT'")
                .param("publicId", target.publicId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notifications WHERE dedupe_key LIKE :prefix")
                .param("prefix", "EVAL:" + target.publicId() + ":SELF_SUBMIT:%")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void returnAndReopenPreserveSubmittedAndFinalizedRevisions() {
        Actor executive = actor("qi0039@query.local");
        Target target = target("QI0003");
        Actor manager = managerActor(target.publicId());
        var initialSaved = service.saveManager(manager.employeePublicId(), manager.accountPublicId(), target.publicId(),
                new EvaluationWorkflowService.ManagerSaveRequest(target.version(), rankDetails(true),
                        "初回の上長評価です"), "TRACE-MANAGER-INITIAL-SAVE");
        var initialSubmitted = service.submitManager(manager.employeePublicId(), manager.accountPublicId(),
                target.publicId(), initialSaved.targetVersion(), "TRACE-MGR-INITIAL-SUBMIT");
        long submittedRevisionId = currentManagerEvaluationId(target.publicId());

        var returned = service.returnToManager(executive.accountPublicId(), target.publicId(),
                initialSubmitted.targetVersion(),
                "経営判断に必要な補足が不足しています", "TRACE-EXEC-RETURN");
        assertThat(revisionStatuses(target.publicId())).containsExactly("SUBMITTED", "DRAFT");
        assertThat(eventManagerEvaluationId(target.publicId(), "EXECUTIVE_RETURN"))
                .isEqualTo(submittedRevisionId);

        var saved = service.saveManager(manager.employeePublicId(), manager.accountPublicId(), target.publicId(),
                new EvaluationWorkflowService.ManagerSaveRequest(returned.targetVersion(), rankDetails(true),
                        "判断根拠を補足しました"), "TRACE-MANAGER-RESAVE");
        var submitted = service.submitManager(manager.employeePublicId(), manager.accountPublicId(), target.publicId(),
                saved.targetVersion(), "TRACE-MANAGER-RESUBMIT");
        var finalized = service.approve(executive.accountPublicId(), target.publicId(), submitted.targetVersion(),
                null, "TRACE-EXEC-REAPPROVE");
        long finalizedRevisionId = currentManagerEvaluationId(target.publicId());
        Long finalizedSnapshotId = profileSnapshotId(finalizedRevisionId);
        var reopened = service.reopen(executive.accountPublicId(), target.publicId(), finalized.targetVersion(),
                "配置判断の前提が変更されたため", "TRACE-EXEC-REOPEN");

        assertThat(reopened.status()).isEqualTo("MANAGER_RETURNED");
        assertThat(revisionStatuses(target.publicId())).containsExactly("SUBMITTED", "FINALIZED", "DRAFT");
        assertThat(eventManagerEvaluationId(target.publicId(), "EXECUTIVE_REOPEN"))
                .isEqualTo(finalizedRevisionId);
        assertThat(profileSnapshotId(finalizedRevisionId)).isEqualTo(finalizedSnapshotId).isNotNull();
        assertThat(profileSnapshotId(currentManagerEvaluationId(target.publicId()))).isNotNull();
    }

    @Test
    void executiveApproveBackfillsMissingSnapshotAndPreservesAnExistingSnapshot() {
        Actor executive = actor("qi0039@query.local");
        Target missingTarget = prepareSubmittedTarget("QI0013");
        long missingManagerEvaluationId = currentManagerEvaluationId(missingTarget.publicId());
        Long originalSnapshotId = profileSnapshotId(missingManagerEvaluationId);
        jdbc.sql("UPDATE manager_evaluations SET profile_status_snapshot_id=NULL WHERE id=:id")
                .param("id", missingManagerEvaluationId).update();

        service.approve(executive.accountPublicId(), missingTarget.publicId(), missingTarget.version(),
                null, "TRACE-APPROVE-SNAPSHOT-1");

        assertThat(profileSnapshotId(missingManagerEvaluationId)).isEqualTo(originalSnapshotId).isNotNull();

        Target existingTarget = prepareSubmittedTarget("QI0014");
        long existingManagerEvaluationId = currentManagerEvaluationId(existingTarget.publicId());
        Long existingSnapshotId = profileSnapshotId(existingManagerEvaluationId);

        service.approve(executive.accountPublicId(), existingTarget.publicId(), existingTarget.version(),
                null, "TRACE-APPROVE-SNAPSHOT-2");

        assertThat(profileSnapshotId(existingManagerEvaluationId)).isEqualTo(existingSnapshotId).isNotNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void concurrentFirstSavesProduceOneSuccessAndOneConflictInsteadOfServerError() throws Exception {
        Target target = target("QI0010");
        Actor manager = managerActor(target.publicId());
        jdbc.sql("UPDATE evaluation_targets SET status='DRAFT',current_manager_evaluation_id=NULL "
                        + "WHERE public_id=:publicId")
                .param("publicId", target.publicId()).update();
        target = target("QI0010");
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        Target concurrentTarget = target;
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return service.saveManager(manager.employeePublicId(), manager.accountPublicId(),
                                concurrentTarget.publicId(), new EvaluationWorkflowService.ManagerSaveRequest(
                                        concurrentTarget.version(), rankDetails(false), null),
                                "TRACE-CONCURRENT-" + Thread.currentThread().threadId());
                    } catch (ApiException exception) {
                        return exception;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn(EvaluationWorkflowService.ManagerEvaluationResponse.class::isInstance)
                    .hasSize(1);
            assertThat(results).filteredOn(ApiException.class::isInstance).singleElement()
                    .satisfies(result -> assertThat(((ApiException) result).status()).isEqualTo(HttpStatus.CONFLICT));
        } finally {
            executor.shutdownNow();
        }
    }

    private Actor actor(String loginId) {
        return jdbc.sql("""
                SELECT a.public_id account_public_id,e.public_id employee_public_id
                FROM accounts a JOIN employees e ON e.id=a.employee_id WHERE a.login_id_normalized=:loginId
                """).param("loginId", loginId)
                .query((rs, row) -> new Actor(rs.getString("account_public_id"), rs.getString("employee_public_id")))
                .single();
    }

    private Target target(String employeeNo) {
        return jdbc.sql("""
                SELECT t.public_id,t.version FROM evaluation_targets t
                JOIN employees e ON e.id=t.employee_id WHERE e.employee_no=:employeeNo
                """).param("employeeNo", employeeNo)
                .query((rs, row) -> new Target(rs.getString("public_id"), rs.getLong("version"))).single();
    }

    private Actor managerActor(String targetPublicId) {
        return jdbc.sql("""
                SELECT a.public_id account_public_id,e.public_id employee_public_id
                FROM evaluation_targets t JOIN employees e ON e.id=t.evaluator_employee_id
                JOIN accounts a ON a.employee_id=e.id WHERE t.public_id=:publicId
                """).param("publicId", targetPublicId)
                .query((rs, row) -> new Actor(rs.getString("account_public_id"), rs.getString("employee_public_id")))
                .single();
    }

    private List<String> revisionStatuses(String targetPublicId) {
        return jdbc.sql("""
                SELECT m.status FROM manager_evaluations m JOIN evaluation_targets t ON t.id=m.target_id
                WHERE t.public_id=:publicId ORDER BY m.revision_no
                """).param("publicId", targetPublicId).query(String.class).list();
    }

    private long currentManagerEvaluationId(String targetPublicId) {
        return jdbc.sql("SELECT current_manager_evaluation_id FROM evaluation_targets WHERE public_id=:publicId")
                .param("publicId", targetPublicId).query(Long.class).single();
    }

    private long eventManagerEvaluationId(String targetPublicId, String action) {
        return jdbc.sql("""
                SELECT ev.manager_evaluation_id FROM evaluation_workflow_events ev
                JOIN evaluation_targets t ON t.id=ev.target_id
                WHERE t.public_id=:publicId AND ev.action=:action ORDER BY ev.id DESC LIMIT 1
                """).param("publicId", targetPublicId).param("action", action).query(Long.class).single();
    }

    private Long profileSnapshotId(long managerEvaluationId) {
        return jdbc.sql("SELECT profile_status_snapshot_id FROM manager_evaluations WHERE id=:id")
                .param("id", managerEvaluationId).query(Long.class).optional().orElse(null);
    }

    private Target prepareSubmittedTarget(String employeeNo) {
        Target initial = target(employeeNo);
        Actor manager = managerActor(initial.publicId());
        jdbc.sql("""
                UPDATE evaluation_targets SET status='DRAFT',current_manager_evaluation_id=NULL,
                  final_score=NULL,final_grade=NULL,finalized_at=NULL WHERE public_id=:publicId
                """).param("publicId", initial.publicId()).update();
        var saved = service.saveManager(manager.employeePublicId(), manager.accountPublicId(), initial.publicId(),
                new EvaluationWorkflowService.ManagerSaveRequest(initial.version(), rankDetails(true), "承認用総評"),
                "TRACE-PREPARE-SNAPSHOT");
        var submitted = service.submitManager(manager.employeePublicId(), manager.accountPublicId(),
                initial.publicId(), saved.targetVersion(), "TRACE-SUBMIT-SNAPSHOT");
        return new Target(initial.publicId(), submitted.targetVersion());
    }

    private static List<EvaluationWorkflowService.ManagerDetailInput> rankDetails(boolean withComments) {
        List<String> axes = List.of("TECHNICAL", "DESIGN", "BUSINESS", "COMMUNICATION", "DELIVERY", "IMPROVEMENT");
        List<String> ranks = List.of("S", "A", "B", "C", "D", "F");
        List<EvaluationWorkflowService.ManagerDetailInput> details = new ArrayList<>();
        for (int index = 0; index < axes.size(); index++) {
            details.add(new EvaluationWorkflowService.ManagerDetailInput(axes.get(index), ranks.get(index),
                    withComments ? axes.get(index) + "の判断根拠" : null));
        }
        return List.copyOf(details);
    }

    private int count(String table, String targetPublicId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " e JOIN evaluation_targets t ON t.id=e.target_id WHERE t.public_id=:publicId")
                .param("publicId", targetPublicId).query(Integer.class).single();
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private record Actor(String accountPublicId, String employeePublicId) {
    }

    private record Target(String publicId, long version) {
    }

}
