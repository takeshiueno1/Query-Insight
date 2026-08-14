package com.query.insight.dashboard;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.query.insight.common.PublicIdGenerator;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class DashboardControllerIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient jdbc;

    @Test
    void aggregatesOnlyAuthenticatedEmployeesApprovedStatusFinalEvaluationAndUnreadCount() throws Exception {
        Account owner = account("QI0005");
        Account other = account("QI0004");
        jdbc.sql("DELETE FROM notifications WHERE recipient_account_id IN (:ownerId,:otherId)")
                .param("ownerId", owner.id()).param("otherId", other.id()).update();
        insertNotification(owner.id(), "dashboard-owner");
        insertNotification(other.id(), "dashboard-other-1");
        insertNotification(other.id(), "dashboard-other-2");
        String ownerStatusPublicId = latestProfileStatusPublicId("QI0005");
        String otherStatusPublicId = latestProfileStatusPublicId("QI0004");

        mvc.perform(get("/api/v1/dashboard/me")
                        .param("employeePublicId", other.employeePublicId())
                        .param("accountPublicId", other.publicId())
                        .with(general(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.employeeNo").value("QI0005"))
                .andExpect(jsonPath("$.profileStatus.publicId").value(ownerStatusPublicId))
                .andExpect(jsonPath("$.profileStatus.publicId").value(org.hamcrest.Matchers.not(otherStatusPublicId)))
                .andExpect(jsonPath("$.profileStatus.skillScore").isNumber())
                .andExpect(jsonPath("$.profileStatus.knowledgeScore").isNumber())
                .andExpect(jsonPath("$.profileStatus.careerScore").isNumber())
                .andExpect(jsonPath("$.profileStatus.certificationScore").isNumber())
                .andExpect(jsonPath("$.profileStatus.totalScore").isNumber())
                .andExpect(jsonPath("$.profileStatus.totalScore").value(60.82))
                .andExpect(jsonPath("$.profileStatus.grade").value("C"))
                .andExpect(jsonPath("$.profileStatus.editable").value(false))
                .andExpect(jsonPath("$.profileStatus.formulaVersion").value("PROFILE_STATUS_V1"))
                .andExpect(jsonPath("$.profileStatus.calculatedAt").isNotEmpty())
                .andExpect(jsonPath("$.finalManagerEvaluation.status").value("FINALIZED"))
                .andExpect(jsonPath("$.finalManagerEvaluation.finalRank").value("A"))
                .andExpect(jsonPath("$.finalManagerEvaluation.summary")
                        .value("安定した成果と今後の成長可能性を確認しました。"))
                .andExpect(jsonPath("$.finalManagerEvaluation.details.length()").value(6))
                .andExpect(jsonPath("$.finalManagerEvaluation.details[0].managerRank").value("B"))
                .andExpect(jsonPath("$.finalManagerEvaluation.finalScore").doesNotExist())
                .andExpect(jsonPath("$.finalManagerEvaluation.details[0].managerLevel").doesNotExist())
                .andExpect(jsonPath("$.unreadNotifications").value(1))
                .andExpect(jsonPath("$.scores").doesNotExist());
    }

    @Test
    void returnsSafeEmptyStatusAndNullWhenNoManagerEvaluationIsPublished() throws Exception {
        Account owner = account("QITEST");
        clearApprovedProfile(owner.employeeId());
        jdbc.sql("DELETE FROM notifications WHERE recipient_account_id=:accountId")
                .param("accountId", owner.id()).update();

        mvc.perform(get("/api/v1/dashboard/me").with(general(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.employeeNo").value("QITEST"))
                .andExpect(jsonPath("$.profileStatus.skillScore").value(0))
                .andExpect(jsonPath("$.profileStatus.knowledgeScore").value(0))
                .andExpect(jsonPath("$.profileStatus.careerScore").value(0))
                .andExpect(jsonPath("$.profileStatus.certificationScore").value(0))
                .andExpect(jsonPath("$.profileStatus.totalScore").value(0))
                .andExpect(jsonPath("$.profileStatus.grade").value("F"))
                .andExpect(jsonPath("$.profileStatus.missingCategories.length()").value(4))
                .andExpect(jsonPath("$.profileStatus.missingCategories[0]").value("SKILL"))
                .andExpect(jsonPath("$.profileStatus.missingCategories[1]").value("KNOWLEDGE"))
                .andExpect(jsonPath("$.profileStatus.missingCategories[2]").value("CAREER"))
                .andExpect(jsonPath("$.profileStatus.missingCategories[3]").value("CERTIFICATION"))
                .andExpect(jsonPath("$.profileStatus.editable").value(false))
                .andExpect(jsonPath("$.finalManagerEvaluation").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.unreadNotifications").value(0));
    }

    @Test
    void doesNotPublishFinalizedManagerEvaluationOwnedByAnotherTarget() throws Exception {
        Account owner = account("QI0005");
        long otherManagerId = jdbc.sql("""
                SELECT t.current_manager_evaluation_id FROM evaluation_targets t
                JOIN employees e ON e.id=t.employee_id JOIN evaluation_periods p ON p.id=t.period_id
                WHERE e.employee_no='QI0004' AND p.status='OPEN'
                """).query(Long.class).single();
        jdbc.sql("UPDATE manager_evaluations SET status='FINALIZED' WHERE id=:id")
                .param("id", otherManagerId).update();
        jdbc.sql("""
                UPDATE evaluation_targets SET current_manager_evaluation_id=:managerId
                WHERE employee_id=:employeeId AND period_id IN (SELECT id FROM evaluation_periods WHERE status='OPEN')
                """).param("managerId", otherManagerId).param("employeeId", owner.employeeId()).update();

        mvc.perform(get("/api/v1/dashboard/me").with(general(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.employeeNo").value("QI0005"))
                .andExpect(jsonPath("$.finalManagerEvaluation").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void doesNotPublishFinalizedManagerEvaluationFromAnotherPeriod() throws Exception {
        Account owner = account("QI0005");
        long originalManagerId = currentManagerId(owner.employeeId());
        long laterPeriodId = insertOpenPeriod(owner.employeeId(), "期間跨ぎ不整合", LocalDate.of(2099, 1, 1));
        insertTarget(laterPeriodId, owner.employeeId(), "FINALIZED", originalManagerId, "S");

        mvc.perform(get("/api/v1/dashboard/me").with(general(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.employeeNo").value("QI0005"))
                .andExpect(jsonPath("$.finalManagerEvaluation").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void selectsHigherTargetIdWhenOpenPeriodsHaveTheSameStartDate() throws Exception {
        Account owner = account("QITEST");
        LocalDate sameStart = LocalDate.of(2100, 1, 1);
        long lowerPeriodId = insertOpenPeriod(owner.employeeId(), "同日開始1", sameStart);
        insertTarget(lowerPeriodId, owner.employeeId(), "DRAFT", null, null);
        long higherPeriodId = insertOpenPeriod(owner.employeeId(), "同日開始2", sameStart);
        long higherTargetId = insertTarget(higherPeriodId, owner.employeeId(), "DRAFT", null, null);
        finalizeTarget(higherTargetId, higherPeriodId, "同日開始では新しい対象を公開");

        mvc.perform(get("/api/v1/dashboard/me").with(general(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalManagerEvaluation.finalRank").value("S"))
                .andExpect(jsonPath("$.finalManagerEvaluation.summary").value("同日開始では新しい対象を公開"));
    }

    @Test
    void doesNotHideNotificationAuthorizationFailure() throws Exception {
        Account owner = account("QI0005");
        RequestPostProcessor jwtWithMissingAccount = jwt().jwt(token -> token
                        .claim("accountPublicId", PublicIdGenerator.next())
                        .claim("employeePublicId", owner.employeePublicId())
                        .claim("scopes", List.of("SELF")))
                .authorities(new SimpleGrantedAuthority("ROLE_GENERAL"));

        mvc.perform(get("/api/v1/dashboard/me").with(jwtWithMissingAccount))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    private void clearApprovedProfile(long employeeId) {
        jdbc.sql("DELETE FROM profile_status_snapshots WHERE employee_id=:employeeId")
                .param("employeeId", employeeId).update();
        jdbc.sql("DELETE FROM employee_skills WHERE employee_id=:employeeId")
                .param("employeeId", employeeId).update();
        jdbc.sql("DELETE FROM employee_knowledge WHERE employee_id=:employeeId")
                .param("employeeId", employeeId).update();
        jdbc.sql("DELETE FROM career_histories WHERE employee_id=:employeeId")
                .param("employeeId", employeeId).update();
        jdbc.sql("DELETE FROM employee_certifications WHERE employee_id=:employeeId")
                .param("employeeId", employeeId).update();
    }

    private String latestProfileStatusPublicId(String employeeNo) {
        return jdbc.sql("""
                SELECT s.public_id FROM profile_status_snapshots s
                JOIN employees e ON e.id=s.employee_id WHERE e.employee_no=:employeeNo
                ORDER BY s.calculated_at DESC,s.id DESC LIMIT 1
                """).param("employeeNo", employeeNo).query(String.class).single();
    }

    private void insertNotification(long accountId, String dedupeKey) {
        jdbc.sql("""
                INSERT INTO notifications(public_id,recipient_account_id,type,title,body,created_at,dedupe_key)
                VALUES (:publicId,:accountId,'TEST','テスト','本文',:createdAt,:dedupeKey)
                """).param("publicId", PublicIdGenerator.next()).param("accountId", accountId)
                .param("createdAt", Timestamp.from(Instant.parse("2026-08-14T03:00:00Z")))
                .param("dedupeKey", dedupeKey).update();
    }

    private long currentManagerId(long employeeId) {
        return jdbc.sql("""
                SELECT t.current_manager_evaluation_id FROM evaluation_targets t
                JOIN evaluation_periods p ON p.id=t.period_id
                WHERE t.employee_id=:employeeId AND p.status='OPEN' ORDER BY p.start_date DESC,t.id DESC LIMIT 1
                """).param("employeeId", employeeId).query(Long.class).single();
    }

    private long insertOpenPeriod(long employeeId, String name, LocalDate startDate) {
        String publicId = PublicIdGenerator.next();
        long criteriaVersionId = jdbc.sql("""
                SELECT p.criteria_version_id FROM evaluation_periods p JOIN evaluation_targets t ON t.period_id=p.id
                WHERE t.employee_id=:employeeId ORDER BY p.start_date DESC,t.id DESC LIMIT 1
                """).param("employeeId", employeeId).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO evaluation_periods(public_id,name,start_date,end_date,self_deadline,manager_deadline,
                  criteria_version_id,status,version)
                VALUES (:publicId,:name,:startDate,:endDate,:selfDeadline,:managerDeadline,:criteriaVersionId,'OPEN',0)
                """).param("publicId", publicId).param("name", name).param("startDate", Date.valueOf(startDate))
                .param("endDate", Date.valueOf(startDate.plusMonths(6)))
                .param("selfDeadline", Timestamp.valueOf(startDate.plusMonths(1).atStartOfDay()))
                .param("managerDeadline", Timestamp.valueOf(startDate.plusMonths(2).atStartOfDay()))
                .param("criteriaVersionId", criteriaVersionId).update();
        return jdbc.sql("SELECT id FROM evaluation_periods WHERE public_id=:publicId")
                .param("publicId", publicId).query(Long.class).single();
    }

    private long insertTarget(long periodId, long employeeId, String status, Long managerId, String finalGrade) {
        String publicId = PublicIdGenerator.next();
        long evaluatorId = jdbc.sql("""
                SELECT evaluator_employee_id FROM evaluation_targets WHERE employee_id=:employeeId ORDER BY id LIMIT 1
                """).param("employeeId", employeeId).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO evaluation_targets(public_id,period_id,employee_id,evaluator_employee_id,status,version,
                  current_manager_evaluation_id,final_grade,finalized_at)
                VALUES (:publicId,:periodId,:employeeId,:evaluatorId,:status,0,:managerId,:finalGrade,
                  CASE WHEN :status='FINALIZED' THEN CURRENT_TIMESTAMP ELSE NULL END)
                """).param("publicId", publicId).param("periodId", periodId).param("employeeId", employeeId)
                .param("evaluatorId", evaluatorId).param("status", status).param("managerId", managerId)
                .param("finalGrade", finalGrade).update();
        return jdbc.sql("SELECT id FROM evaluation_targets WHERE public_id=:publicId")
                .param("publicId", publicId).query(Long.class).single();
    }

    private void finalizeTarget(long targetId, long periodId, String summary) {
        String managerPublicId = PublicIdGenerator.next();
        jdbc.sql("""
                INSERT INTO manager_evaluations(public_id,target_id,revision_no,status,summary,weighted_score,grade,
                  finalized_at,version)
                VALUES (:publicId,:targetId,1,'FINALIZED',:summary,5.00,'S',CURRENT_TIMESTAMP,0)
                """).param("publicId", managerPublicId).param("targetId", targetId).param("summary", summary).update();
        long managerId = jdbc.sql("SELECT id FROM manager_evaluations WHERE public_id=:publicId")
                .param("publicId", managerPublicId).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO manager_evaluation_details(manager_evaluation_id,axis_code,level,comment)
                SELECT :managerId,c.axis_code,5,'同日開始の確定評価' FROM evaluation_criteria c
                JOIN evaluation_periods p ON p.criteria_version_id=c.criteria_version_id WHERE p.id=:periodId
                """).param("managerId", managerId).param("periodId", periodId).update();
        jdbc.sql("""
                UPDATE evaluation_targets SET status='FINALIZED',current_manager_evaluation_id=:managerId,
                  final_grade='S',finalized_at=CURRENT_TIMESTAMP WHERE id=:targetId
                """).param("managerId", managerId).param("targetId", targetId).update();
    }

    private RequestPostProcessor general(Account account) {
        return jwt().jwt(token -> token.claim("accountPublicId", account.publicId())
                        .claim("employeePublicId", account.employeePublicId())
                        .claim("scopes", List.of("SELF")))
                .authorities(new SimpleGrantedAuthority("ROLE_GENERAL"));
    }

    private Account account(String employeeNo) {
        return jdbc.sql("""
                SELECT a.id,a.public_id,e.id employee_id,e.public_id employee_public_id
                FROM accounts a JOIN employees e ON e.id=a.employee_id WHERE e.employee_no=:employeeNo
                """).param("employeeNo", employeeNo).query((rs, row) -> new Account(rs.getLong("id"),
                        rs.getString("public_id").trim(), rs.getLong("employee_id"),
                        rs.getString("employee_public_id").trim())).single();
    }

    private record Account(long id, String publicId, long employeeId, String employeePublicId) {
    }
}
