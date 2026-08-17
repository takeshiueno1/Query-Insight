package com.query.insight.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("local")
class ExecutiveDashboardIntegrationTests {
    @Autowired
    private EvaluationWorkflowService service;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void localDataContainsDecisionReadyMixedStatusesAndDepartmentDistribution() {
        String executive = jdbc.sql("SELECT a.public_id FROM accounts a JOIN employees e ON e.id=a.employee_id "
                        + "WHERE e.employee_no='QI0039'")
                .query(String.class).single();

        var dashboard = service.executiveDashboard(executive);

        assertThat(dashboard.counts().pending()).isGreaterThanOrEqualTo(1);
        assertThat(dashboard.counts().finalized()).isGreaterThanOrEqualTo(1);
        assertThat(dashboard.distributions()).isNotEmpty();
        assertThat(dashboard.distributions()).extracting(EvaluationWorkflowService.Distribution::grade)
                .allMatch(grade -> java.util.Set.of("S", "A", "B", "C", "D", "F").contains(grade));
        assertThat(dashboard.items()).extracting(EvaluationWorkflowService.ExecutiveListItem::status)
                .contains("EXECUTIVE_REVIEW", "FINALIZED", "MANAGER_RETURNED");
        assertThat(dashboard.items()).filteredOn(item -> "FINALIZED".equals(item.status()))
                .extracting(EvaluationWorkflowService.ExecutiveListItem::finalGrade)
                .allSatisfy(grade -> assertThat(grade).isIn("S", "A", "B", "C", "D", "F"));
    }

    @Test
    @Transactional
    void pendingItemExposesSubmittedManagerGradeWithoutPublishingFinalGrade() {
        String targetPublicId = targetPublicId("QI0016");
        var manager = managerActor(targetPublicId);
        jdbc.sql("UPDATE evaluation_targets SET status='DRAFT',current_manager_evaluation_id=NULL,"
                        + "final_score=NULL,final_grade=NULL,finalized_at=NULL WHERE public_id=:publicId")
                .param("publicId", targetPublicId).update();
        long version = jdbc.sql("SELECT version FROM evaluation_targets WHERE public_id=:publicId")
                .param("publicId", targetPublicId).query(Long.class).single();

        var saved = service.saveManager(manager.employeePublicId(), manager.accountPublicId(), targetPublicId,
                new EvaluationWorkflowService.ManagerSaveRequest(version, List.of(
                        detail("TECHNICAL", "S"), detail("DESIGN", "A"), detail("BUSINESS", "B"),
                        detail("COMMUNICATION", "C"), detail("DELIVERY", "D"), detail("IMPROVEMENT", "F")),
                        "経営承認に必要な総評です"), "TRACE-DASHBOARD-SAVE");
        service.submitManager(manager.employeePublicId(), manager.accountPublicId(), targetPublicId,
                saved.targetVersion(), "TRACE-DASHBOARD-SUBMIT");

        var dashboard = service.executiveDashboard(accountPublicId("QI0039"));
        var item = dashboard.items().stream().filter(row -> targetPublicId.equals(row.publicId())).findFirst()
                .orElseThrow();
        assertThat(item.status()).isEqualTo("EXECUTIVE_REVIEW");
        assertThat(item.managerGrade()).isEqualTo("C");
        assertThat(item.finalGrade()).isNull();
    }

    @Test
    @Transactional
    void crossTargetManagerEvaluationPointerCannotLeakAnotherEmployeesGradeOrRemoveTheListRow() {
        String sourceTargetPublicId = prepareSubmittedTarget("QI0013", "S", "SOURCE");
        String affectedTargetPublicId = prepareSubmittedTarget("QI0014", "C", "AFFECTED");
        String executive = accountPublicId("QI0039");
        var before = service.executiveDashboard(executive);
        assertThat(item(before, sourceTargetPublicId).managerGrade()).isEqualTo("S");
        assertThat(item(before, affectedTargetPublicId).managerGrade()).isEqualTo("C");
        var unaffectedBefore = before.items().stream()
                .filter(row -> !affectedTargetPublicId.equals(row.publicId()))
                .map(row -> row.publicId() + ":" + row.managerGrade()).toList();

        long sourceManagerEvaluationId = jdbc.sql(
                        "SELECT current_manager_evaluation_id FROM evaluation_targets WHERE public_id=:publicId")
                .param("publicId", sourceTargetPublicId).query(Long.class).single();
        jdbc.sql("UPDATE evaluation_targets SET current_manager_evaluation_id=:managerId WHERE public_id=:publicId")
                .param("managerId", sourceManagerEvaluationId).param("publicId", affectedTargetPublicId).update();

        var after = service.executiveDashboard(executive);
        assertThat(after.items()).hasSameSizeAs(before.items());
        assertThat(item(after, affectedTargetPublicId).managerGrade()).isNull();
        assertThat(item(after, sourceTargetPublicId).managerGrade()).isEqualTo("S");
        assertThat(after.items().stream().filter(row -> !affectedTargetPublicId.equals(row.publicId()))
                .map(row -> row.publicId() + ":" + row.managerGrade()).toList())
                .containsExactlyElementsOf(unaffectedBefore);
    }

    @Test
    void draftManagerEvaluationIsNotPublishedAsAnExecutiveGrade() {
        String targetPublicId = targetPublicId("QI0007");
        var storedDraft = jdbc.sql("""
                SELECT m.status,m.grade FROM evaluation_targets t
                JOIN manager_evaluations m ON m.id=t.current_manager_evaluation_id
                WHERE t.public_id=:publicId
                """).param("publicId", targetPublicId)
                .query((rs, row) -> new StoredManagerEvaluation(rs.getString("status"), rs.getString("grade")))
                .single();
        assertThat(storedDraft.status()).isEqualTo("DRAFT");
        assertThat(storedDraft.grade()).isNotNull();

        var dashboard = service.executiveDashboard(accountPublicId("QI0039"));

        assertThat(item(dashboard, targetPublicId).status()).isEqualTo("MANAGER_RETURNED");
        assertThat(item(dashboard, targetPublicId).managerGrade()).isNull();
    }

    private String prepareSubmittedTarget(String employeeNo, String rank, String traceSuffix) {
        String targetPublicId = targetPublicId(employeeNo);
        var manager = managerActor(targetPublicId);
        jdbc.sql("UPDATE evaluation_targets SET status='DRAFT',current_manager_evaluation_id=NULL,"
                        + "final_score=NULL,final_grade=NULL,finalized_at=NULL WHERE public_id=:publicId")
                .param("publicId", targetPublicId).update();
        long version = jdbc.sql("SELECT version FROM evaluation_targets WHERE public_id=:publicId")
                .param("publicId", targetPublicId).query(Long.class).single();
        var saved = service.saveManager(manager.employeePublicId(), manager.accountPublicId(), targetPublicId,
                new EvaluationWorkflowService.ManagerSaveRequest(version, List.of(
                        detail("TECHNICAL", rank), detail("DESIGN", rank), detail("BUSINESS", rank),
                        detail("COMMUNICATION", rank), detail("DELIVERY", rank), detail("IMPROVEMENT", rank)),
                        "経営承認に必要な総評です"), "TRACE-" + traceSuffix + "-SAVE");
        service.submitManager(manager.employeePublicId(), manager.accountPublicId(), targetPublicId,
                saved.targetVersion(), "TRACE-" + traceSuffix + "-SUBMIT");
        return targetPublicId;
    }

    private EvaluationWorkflowService.ExecutiveListItem item(
            EvaluationWorkflowService.ExecutiveDashboard dashboard, String targetPublicId) {
        return dashboard.items().stream().filter(row -> targetPublicId.equals(row.publicId())).findFirst()
                .orElseThrow();
    }

    private EvaluationWorkflowService.ManagerDetailInput detail(String axisCode, String rank) {
        return new EvaluationWorkflowService.ManagerDetailInput(axisCode, rank, axisCode + "の判断根拠");
    }

    private String targetPublicId(String employeeNo) {
        return jdbc.sql("SELECT t.public_id FROM evaluation_targets t JOIN employees e ON e.id=t.employee_id "
                        + "WHERE e.employee_no=:employeeNo")
                .param("employeeNo", employeeNo).query(String.class).single();
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

    private String accountPublicId(String employeeNo) {
        return jdbc.sql("SELECT a.public_id FROM accounts a JOIN employees e ON e.id=a.employee_id "
                        + "WHERE e.employee_no=:employeeNo")
                .param("employeeNo", employeeNo).query(String.class).single();
    }

    private record Actor(String accountPublicId, String employeePublicId) {
    }

    private record StoredManagerEvaluation(String status, String grade) {
    }
}
