package com.query.insight.talent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.query.insight.common.ApiException;
import com.query.insight.talent.TalentSubmission.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ManagerTalentSubmissionIntegrationTests {
    @Autowired
    private TalentSubmissionService service;
    @Autowired
    private TalentSubmissionRepository repository;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private MockMvc mvc;

    @Test
    void currentManagerCanApproveAndOfficialProfileChangesAtomically() {
        Actor employee = actor("QITEST");
        Actor manager = actor("QI0002");
        String masterId = unassignedSkillMaster();
        var draft = service.create(employee.employeePublicId(), Type.SKILL,
                new TalentPayloads.SkillPayload(masterId, 4, new BigDecimal("3.5"),
                        LocalDate.of(2026, 8, 1), "承認対象"), employee.accountPublicId(), traceId(1));
        var submitted = service.submit(employee.employeePublicId(), employee.accountPublicId(),
                draft.publicId(), draft.version(), traceId(2));

        var approved = service.approve(manager.employeePublicId(), manager.accountPublicId(),
                draft.publicId(), submitted.version(), traceId(3));

        assertThat(approved.status()).isEqualTo(TalentSubmission.Status.APPROVED);
        assertThat(jdbc.sql("""
                SELECT es.proficiency_level FROM employee_skills es
                JOIN skill_masters sm ON sm.id=es.skill_id
                JOIN employees e ON e.id=es.employee_id
                WHERE e.employee_no='QITEST' AND sm.public_id=:masterId
                """).param("masterId", masterId).query(Integer.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM talent_submission_events WHERE submission_id=:id AND action='APPROVE'")
                .param("id", draft.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notifications WHERE dedupe_key=:key")
                .param("key", "talent:" + draft.publicId() + ":approved")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action='TALENT_APPROVE' AND target_public_id=:id")
                .param("id", draft.publicId()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void nonAssignedAndFormerManagerReceive404() {
        Actor employee = actor("QITEST");
        Actor manager = actor("QI0002");
        Actor other = actor("QI0006");
        var draft = service.create(employee.employeePublicId(), Type.KNOWLEDGE,
                new TalentPayloads.KnowledgePayload(knowledgeMaster(), 3, "担当実績"),
                employee.accountPublicId(), traceId(4));
        var submitted = service.submit(employee.employeePublicId(), employee.accountPublicId(),
                draft.publicId(), 0, traceId(5));

        assertThatThrownBy(() -> service.approve(other.employeePublicId(), other.accountPublicId(),
                draft.publicId(), submitted.version(), traceId(6)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(404));

        long originalManager = jdbc.sql("SELECT manager_employee_id FROM employees WHERE employee_no='QITEST'")
                .query(Long.class).single();
        long otherId = jdbc.sql("SELECT id FROM employees WHERE employee_no='QI0006'").query(Long.class).single();
        try {
            jdbc.sql("UPDATE employees SET manager_employee_id=:id WHERE employee_no='QITEST'")
                    .param("id", otherId).update();
            assertThatThrownBy(() -> service.approve(manager.employeePublicId(), manager.accountPublicId(),
                    draft.publicId(), submitted.version(), traceId(7)))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.status().value()).isEqualTo(404));
        } finally {
            jdbc.sql("UPDATE employees SET manager_employee_id=:id WHERE employee_no='QITEST'")
                    .param("id", originalManager).update();
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM talent_submission_events WHERE submission_id=:id AND action='APPROVE'")
                .param("id", draft.id()).query(Integer.class).single()).isZero();
    }

    @Test
    void returnRequiresReasonAndKeepsFormalDataUnchanged() {
        Actor employee = actor("QITEST");
        Actor manager = actor("QI0002");
        var draft = service.create(employee.employeePublicId(), Type.CERTIFICATION,
                new TalentPayloads.CertificationPayload(certificationMaster(), LocalDate.of(2026, 1, 1),
                        null, "CERT-1"), employee.accountPublicId(), traceId(8));
        var submitted = service.submit(employee.employeePublicId(), employee.accountPublicId(),
                draft.publicId(), 0, traceId(9));

        assertThatThrownBy(() -> service.returnToEmployee(manager.employeePublicId(), manager.accountPublicId(),
                draft.publicId(), submitted.version(), " ", traceId(10)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(400));
        assertThatThrownBy(() -> service.returnToEmployee(manager.employeePublicId(), manager.accountPublicId(),
                draft.publicId(), submitted.version(), "x".repeat(1001), traceId(11)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(400));

        var returned = service.returnToEmployee(manager.employeePublicId(), manager.accountPublicId(),
                draft.publicId(), submitted.version(), "x".repeat(1000), traceId(12));
        assertThat(returned.status()).isEqualTo(TalentSubmission.Status.RETURNED);
        assertThat(returned.returnReason()).hasSize(1000);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM employee_certifications ec JOIN employees e ON e.id=ec.employee_id "
                        + "WHERE e.employee_no='QITEST' AND ec.public_id=:id")
                .param("id", draft.logicalPublicId()).query(Integer.class).single()).isZero();
    }

    @Test
    void approvingNewRevisionSupersedesOldSubmissionButPreservesItsPayload() {
        Actor employee = actor("QITEST");
        Actor manager = actor("QI0002");
        ExistingSkill existing = jdbc.sql("""
                SELECT sm.public_id master_public_id,es.public_id record_public_id,es.proficiency_level
                FROM employee_skills es JOIN skill_masters sm ON sm.id=es.skill_id
                JOIN employees e ON e.id=es.employee_id WHERE e.employee_no='QITEST' ORDER BY es.id LIMIT 1
                """).query((rs, row) -> new ExistingSkill(rs.getString("master_public_id").trim(),
                        rs.getString("record_public_id").trim(), rs.getInt("proficiency_level"))).single();
        String oldPayload = jdbc.sql("SELECT CAST(payload_json AS VARCHAR) FROM talent_submissions "
                        + "WHERE logical_public_id=:id AND status='APPROVED'")
                .param("id", existing.recordPublicId()).query(String.class).single();
        int nextLevel = existing.level() == 5 ? 4 : existing.level() + 1;
        var draft = service.create(employee.employeePublicId(), Type.SKILL,
                new TalentPayloads.SkillPayload(existing.masterPublicId(), nextLevel, BigDecimal.ONE,
                        LocalDate.of(2026, 8, 1), "新版"), employee.accountPublicId(), traceId(13));
        assertThat(draft.logicalPublicId()).isEqualTo(existing.recordPublicId());
        assertThat(draft.revisionNo()).isEqualTo(2);
        var submitted = service.submit(employee.employeePublicId(), employee.accountPublicId(),
                draft.publicId(), 0, traceId(14));

        service.approve(manager.employeePublicId(), manager.accountPublicId(), draft.publicId(),
                submitted.version(), traceId(15));

        assertThat(jdbc.sql("SELECT status FROM talent_submissions WHERE logical_public_id=:id ORDER BY revision_no")
                .param("id", existing.recordPublicId()).query(String.class).list())
                .containsExactly("SUPERSEDED", "APPROVED");
        assertThat(jdbc.sql("SELECT CAST(payload_json AS VARCHAR) FROM talent_submissions "
                        + "WHERE logical_public_id=:id AND revision_no=1")
                .param("id", existing.recordPublicId()).query(String.class).single()).isEqualTo(oldPayload);
        assertThat(jdbc.sql("SELECT proficiency_level FROM employee_skills WHERE public_id=:id")
                .param("id", existing.recordPublicId()).query(Integer.class).single()).isEqualTo(nextLevel);
    }

    @Test
    void managerApiListsDetailsAndRejectsPayloadFieldsOnApproval() throws Exception {
        Actor employee = actor("QITEST");
        var draft = service.create(employee.employeePublicId(), Type.CAREER,
                new TalentPayloads.CareerPayload("API確認案件", "IT", "担当", LocalDate.of(2026, 8, 1),
                        null, "概要", "成果", "Java"), employee.accountPublicId(), traceId(16));
        var submitted = service.submit(employee.employeePublicId(), employee.accountPublicId(),
                draft.publicId(), 0, traceId(17));

        mvc.perform(get("/api/v1/manager/talent-submissions").param("status", "SUBMITTED")
                        .with(managerJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.publicId == '%s')]", draft.publicId()).exists());
        mvc.perform(get("/api/v1/manager/talent-submissions/{id}", draft.publicId()).with(managerJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submission.payload.projectName").value("API確認案件"))
                .andExpect(jsonPath("$.employee.publicId").isNotEmpty());

        mvc.perform(post("/api/v1/manager/talent-submissions/{id}/approve", draft.publicId())
                        .with(managerJwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":%d,\"level\":5}".formatted(submitted.version())))
                .andExpect(status().isBadRequest());
        assertThat(repository.findByPublicId(draft.publicId()).orElseThrow().status())
                .isEqualTo(TalentSubmission.Status.SUBMITTED);
    }

    @Test
    void failedOfficialWriteRollsBackDecisionEventNotificationAndAudit() {
        Actor employee = actor("QITEST");
        Actor manager = actor("QI0002");
        ExistingSkill existing = jdbc.sql("""
                SELECT sm.public_id master_public_id,es.public_id record_public_id,es.proficiency_level
                FROM employee_skills es JOIN skill_masters sm ON sm.id=es.skill_id
                JOIN employees e ON e.id=es.employee_id WHERE e.employee_no='QITEST' ORDER BY es.id LIMIT 1
                """).query((rs, row) -> new ExistingSkill(rs.getString("master_public_id").trim(),
                        rs.getString("record_public_id").trim(), rs.getInt("proficiency_level"))).single();
        long employeeId = jdbc.sql("SELECT id FROM employees WHERE employee_no='QITEST'").query(Long.class).single();
        var duplicate = repository.createDraft(employeeId, Type.SKILL,
                new TalentPayloads.SkillPayload(existing.masterPublicId(), 5, BigDecimal.ONE,
                        LocalDate.of(2026, 8, 1), "制約違反確認"), null, Instant.now());
        var submitted = service.submit(employee.employeePublicId(), employee.accountPublicId(),
                duplicate.publicId(), 0, traceId(18));

        assertThatThrownBy(() -> service.approve(manager.employeePublicId(), manager.accountPublicId(),
                duplicate.publicId(), submitted.version(), traceId(19)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(repository.findByPublicId(duplicate.publicId()).orElseThrow().status())
                .isEqualTo(TalentSubmission.Status.SUBMITTED);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM talent_submission_events WHERE submission_id=:id AND action='APPROVE'")
                .param("id", duplicate.id()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notifications WHERE dedupe_key=:key")
                .param("key", "talent:" + duplicate.publicId() + ":approved")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action='TALENT_APPROVE' AND target_public_id=:id")
                .param("id", duplicate.publicId()).query(Integer.class).single()).isZero();
    }

    private Actor actor(String employeeNo) {
        return jdbc.sql("""
                SELECT e.public_id employee_public_id,a.public_id account_public_id
                FROM employees e JOIN accounts a ON a.employee_id=e.id WHERE e.employee_no=:employeeNo
                """).param("employeeNo", employeeNo)
                .query((rs, row) -> new Actor(rs.getString("employee_public_id").trim(),
                        rs.getString("account_public_id").trim())).single();
    }

    private String unassignedSkillMaster() {
        return jdbc.sql("""
                SELECT sm.public_id FROM skill_masters sm WHERE sm.status='ACTIVE' AND NOT EXISTS (
                  SELECT 1 FROM employee_skills es JOIN employees e ON e.id=es.employee_id
                  WHERE es.skill_id=sm.id AND e.employee_no='QITEST') ORDER BY sm.id LIMIT 1
                """).query(String.class).single();
    }

    private String knowledgeMaster() {
        return jdbc.sql("SELECT public_id FROM knowledge_masters WHERE status='ACTIVE' ORDER BY id LIMIT 1")
                .query(String.class).single();
    }

    private String certificationMaster() {
        return jdbc.sql("""
                SELECT cm.public_id FROM certification_masters cm WHERE cm.status='ACTIVE' AND NOT EXISTS (
                  SELECT 1 FROM employee_certifications ec JOIN employees e ON e.id=ec.employee_id
                  WHERE ec.certification_id=cm.id AND e.employee_no='QITEST') ORDER BY cm.id LIMIT 1
                """).query(String.class).single();
    }

    private String traceId(int value) {
        return "01K" + String.format("%023d", value);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor managerJwt() {
        Actor manager = actor("QI0002");
        return jwt().jwt(token -> token.claim("accountPublicId", manager.accountPublicId())
                        .claim("employeePublicId", manager.employeePublicId()).claim("roles", List.of("OFFICER"))
                        .claim("scopes", List.of("SUBORDINATES")))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OFFICER"));
    }

    private record Actor(String employeePublicId, String accountPublicId) {
    }

    private record ExistingSkill(String masterPublicId, String recordPublicId, int level) {
    }
}
