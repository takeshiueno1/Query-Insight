package com.query.insight.dashboard;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.query.insight.common.PublicIdGenerator;
import java.sql.Timestamp;
import java.time.Instant;
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
