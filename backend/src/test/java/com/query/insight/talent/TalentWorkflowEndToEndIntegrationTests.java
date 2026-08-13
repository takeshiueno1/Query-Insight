package com.query.insight.talent;

import static org.assertj.core.api.Assertions.assertThat;

import com.query.insight.talent.TalentSubmission.Type;
import com.query.insight.talent.attachment.FileScanClient;
import com.query.insight.talent.attachment.TalentAttachmentService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class TalentWorkflowEndToEndIntegrationTests {
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes();

    @Autowired
    private TalentSubmissionService submissions;
    @Autowired
    private TalentAttachmentService attachments;
    @Autowired
    private TalentProfileService profiles;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void employeeAttachmentSubmissionManagerApprovalAndProfilePublicationCompleteAtomically() {
        Actor employee = actor("QITEST");
        Actor manager = actor("QI0002");
        Master master = unassignedSkillMaster();
        var draft = submissions.create(employee.employeePublicId(), Type.SKILL,
                new TalentPayloads.SkillPayload(master.publicId(), 4, new BigDecimal("2.5"),
                        LocalDate.of(2026, 8, 1), "統合確認の根拠"),
                employee.accountPublicId(), traceId(1));
        var attachment = attachments.upload(employee.employeePublicId(), draft.publicId(), draft.version(),
                new MockMultipartFile("file", "evidence.pdf", "application/pdf", PDF));

        var submitted = submissions.submit(employee.employeePublicId(), employee.accountPublicId(),
                draft.publicId(), attachment.submissionVersion(), traceId(2));
        var approved = submissions.approve(manager.employeePublicId(), manager.accountPublicId(),
                draft.publicId(), submitted.version(), traceId(3));

        assertThat(attachment.scanStatus()).isEqualTo("CLEAN");
        assertThat(approved.status()).isEqualTo(TalentSubmission.Status.APPROVED);
        assertThat(profiles.findAccessible(employee.employeePublicId(), employee.employeePublicId(),
                Set.of("GENERAL")).skills()).anySatisfy(skill -> {
                    assertThat(skill.code()).isEqualTo(master.code());
                    assertThat(skill.level()).isEqualTo(4);
                });
        assertThat(jdbc.sql("SELECT action FROM talent_submission_events WHERE submission_id=:id ORDER BY id")
                .param("id", draft.id()).query(String.class).list()).contains("CREATE", "SUBMIT", "APPROVE");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notifications WHERE dedupe_key LIKE :key")
                .param("key", "talent:" + draft.publicId() + ":%")
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE target_public_id=:id")
                .param("id", draft.publicId()).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void allFourTalentTypesCanBeSubmittedAndApprovedWithoutAttachments() {
        Actor employee = actor("QITEST");
        Actor manager = actor("QI0002");
        var cases = java.util.List.of(
                new TalentCase(Type.SKILL, new TalentPayloads.SkillPayload(
                        unassignedMaster("skill_masters", "employee_skills", "skill_id").publicId(), 3,
                        new BigDecimal("1.0"), LocalDate.of(2026, 8, 1), "添付なしのスキル根拠")),
                new TalentCase(Type.KNOWLEDGE, new TalentPayloads.KnowledgePayload(
                        unassignedMaster("knowledge_masters", "employee_knowledge", "knowledge_id").publicId(),
                        3, "添付なしの知識根拠")),
                new TalentCase(Type.CAREER, new TalentPayloads.CareerPayload(
                        "添付なし経歴", "IT", "担当者", LocalDate.of(2025, 4, 1), null,
                        "業務概要", "業務成果", "Java")),
                new TalentCase(Type.CERTIFICATION, new TalentPayloads.CertificationPayload(
                        unassignedMaster("certification_masters", "employee_certifications", "certification_id")
                                .publicId(),
                        LocalDate.of(2026, 7, 1), null, null)));

        int trace = 10;
        for (TalentCase talentCase : cases) {
            var draft = submissions.create(employee.employeePublicId(), talentCase.type(), talentCase.payload(),
                    employee.accountPublicId(), traceId(trace++));
            var submitted = submissions.submit(employee.employeePublicId(), employee.accountPublicId(),
                    draft.publicId(), draft.version(), traceId(trace++));
            var approved = submissions.approve(manager.employeePublicId(), manager.accountPublicId(),
                    draft.publicId(), submitted.version(), traceId(trace++));

            assertThat(approved.status()).isEqualTo(TalentSubmission.Status.APPROVED);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM talent_attachments WHERE submission_id=:id")
                    .param("id", draft.id()).query(Integer.class).single()).isZero();
        }
    }

    private Actor actor(String employeeNo) {
        return jdbc.sql("""
                SELECT e.public_id employee_public_id,a.public_id account_public_id
                FROM employees e JOIN accounts a ON a.employee_id=e.id WHERE e.employee_no=:employeeNo
                """).param("employeeNo", employeeNo)
                .query((rs, row) -> new Actor(rs.getString("employee_public_id").trim(),
                        rs.getString("account_public_id").trim())).single();
    }

    private Master unassignedSkillMaster() {
        return jdbc.sql("""
                SELECT sm.public_id,sm.code FROM skill_masters sm WHERE sm.status='ACTIVE' AND NOT EXISTS (
                  SELECT 1 FROM employee_skills es JOIN employees e ON e.id=es.employee_id
                  WHERE es.skill_id=sm.id AND e.employee_no='QITEST') ORDER BY sm.id LIMIT 1
                """).query((rs, row) -> new Master(rs.getString("public_id").trim(), rs.getString("code"))).single();
    }

    private Master unassignedMaster(String masterTable, String employeeTable, String masterIdColumn) {
        return jdbc.sql("SELECT m.public_id,m.code FROM " + masterTable + " m WHERE m.status='ACTIVE' AND NOT EXISTS ("
                        + "SELECT 1 FROM " + employeeTable + " x JOIN employees e ON e.id=x.employee_id "
                        + "WHERE x." + masterIdColumn + "=m.id AND e.employee_no='QITEST') ORDER BY m.id LIMIT 1")
                .query((rs, row) -> new Master(rs.getString("public_id").trim(), rs.getString("code"))).single();
    }

    private String traceId(int value) {
        return "01M" + String.format("%023d", value);
    }

    private record Actor(String employeePublicId, String accountPublicId) {
    }

    private record Master(String publicId, String code) {
    }

    private record TalentCase(Type type, TalentPayloads.Payload payload) {
    }

    @TestConfiguration
    static class ScanConfiguration {
        @Bean
        @Primary
        FileScanClient cleanFileScanClient() {
            return content -> FileScanClient.Result.CLEAN;
        }
    }
}
