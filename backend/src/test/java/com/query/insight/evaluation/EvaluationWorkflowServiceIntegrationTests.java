package com.query.insight.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.query.insight.common.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

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
    void assignedManagerCanSubmitAndExecutiveCanFinalizeWithHistoryAndNotifications() {
        Actor manager = actor("manager@query.local");
        Actor executive = actor("qi0039@query.local");
        Target target = target("QI0003");
        List<EvaluationWorkflowService.ManagerDetailInput> details = selfDetails(target.publicId()).stream()
                .map(detail -> new EvaluationWorkflowService.ManagerDetailInput(detail.axisCode(),
                        "TECHNICAL".equals(detail.axisCode()) ? different(detail.level()) : detail.level(),
                        "TECHNICAL".equals(detail.axisCode()) ? "自己評価との差を成果物で確認したため" : null))
                .toList();

        var saved = service.saveManager(manager.employeePublicId(), manager.accountPublicId(), target.publicId(),
                new EvaluationWorkflowService.ManagerSaveRequest(target.version(), details, "期待役割を安定して果たしています"),
                "TRACE-MANAGER-SAVE");
        var submitted = service.submitManager(manager.employeePublicId(), manager.accountPublicId(), target.publicId(),
                saved.targetVersion(), "TRACE-MANAGER-SUBMIT");
        var finalized = service.approve(executive.accountPublicId(), target.publicId(), submitted.targetVersion(),
                "経営判断に利用できる内容です", "TRACE-EXEC-APPROVE");

        assertThat(finalized.status()).isEqualTo("FINALIZED");
        assertThat(finalized.finalScore()).isNotNull();
        assertThat(finalized.finalGrade()).isNotBlank();
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

        assertThatThrownBy(() -> service.managerDetail(otherManager.employeePublicId(), target.publicId()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND));
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
        Target target = target("QI0004");
        Actor manager = managerActor(target.publicId());

        var returned = service.returnToManager(executive.accountPublicId(), target.publicId(), target.version(),
                "経営判断に必要な補足が不足しています", "TRACE-EXEC-RETURN");
        assertThat(revisionStatuses(target.publicId())).containsExactly("SUBMITTED", "DRAFT");

        var submitted = service.submitManager(manager.employeePublicId(), manager.accountPublicId(), target.publicId(),
                returned.targetVersion(), "TRACE-MANAGER-RESUBMIT");
        var finalized = service.approve(executive.accountPublicId(), target.publicId(), submitted.targetVersion(),
                null, "TRACE-EXEC-REAPPROVE");
        var reopened = service.reopen(executive.accountPublicId(), target.publicId(), finalized.targetVersion(),
                "配置判断の前提が変更されたため", "TRACE-EXEC-REOPEN");

        assertThat(reopened.status()).isEqualTo("MANAGER_RETURNED");
        assertThat(revisionStatuses(target.publicId())).containsExactly("SUBMITTED", "FINALIZED", "DRAFT");
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

    private List<SelfDetail> selfDetails(String targetPublicId) {
        return jdbc.sql("""
                SELECT d.axis_code,d.level FROM self_evaluation_details d
                JOIN evaluation_targets t ON t.id=d.target_id WHERE t.public_id=:publicId ORDER BY d.axis_code
                """).param("publicId", targetPublicId)
                .query((rs, row) -> new SelfDetail(rs.getString("axis_code"), rs.getInt("level"))).list();
    }

    private int count(String table, String targetPublicId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " e JOIN evaluation_targets t ON t.id=e.target_id WHERE t.public_id=:publicId")
                .param("publicId", targetPublicId).query(Integer.class).single();
    }

    private static int different(int level) {
        return level == 5 ? 4 : level + 1;
    }

    private record Actor(String accountPublicId, String employeePublicId) {
    }

    private record Target(String publicId, long version) {
    }

    private record SelfDetail(String axisCode, int level) {
    }
}
