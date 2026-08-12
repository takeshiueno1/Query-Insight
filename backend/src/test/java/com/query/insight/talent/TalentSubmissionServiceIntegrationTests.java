package com.query.insight.talent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.query.insight.common.ApiException;
import com.query.insight.talent.TalentSubmission.Type;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class TalentSubmissionServiceIntegrationTests {
    @Autowired
    private TalentSubmissionService service;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void employeeCreatesSavesAndSubmitsOwnSkillWithOneNotificationAndAudit() {
        Actor employee = actor("QITEST");
        var draft = service.create(employee.employeePublicId(), Type.CAREER,
                career("サービス正常系", "担当実績"), employee.accountPublicId(), traceId(1));
        var saved = service.update(employee.employeePublicId(), draft.publicId(), 0,
                career("サービス正常系", "更新実績"), employee.accountPublicId(), traceId(2));
        var submitted = service.submit(employee.employeePublicId(), employee.accountPublicId(),
                draft.publicId(), saved.version(), traceId(3));

        assertThat(submitted.status()).isEqualTo(TalentSubmission.Status.SUBMITTED);
        assertThat(service.mine(employee.employeePublicId(), Type.CAREER))
                .anySatisfy(row -> assertThat(row.publicId()).isEqualTo(draft.publicId()));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM talent_submission_events WHERE submission_id=:id")
                .param("id", draft.id()).query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notifications WHERE dedupe_key=:key")
                .param("key", "talent:" + draft.publicId() + ":submit:" + submitted.version())
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE target_public_id=:id "
                        + "AND action IN ('TALENT_CREATE','TALENT_SAVE','TALENT_SUBMIT')")
                .param("id", draft.publicId()).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void anotherEmployeeAndStaleVersionCannotEdit() {
        Actor employee = actor("QITEST");
        var draft = service.create(employee.employeePublicId(), Type.CAREER,
                career("所有者検証", "担当実績"),
                employee.accountPublicId(), traceId(4));

        assertThatThrownBy(() -> service.update(actor("QI0003").employeePublicId(), draft.publicId(), 0,
                career("所有者検証", "不正更新"),
                actor("QI0003").accountPublicId(), traceId(5)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status().value()).isEqualTo(404));
        service.update(employee.employeePublicId(), draft.publicId(), 0,
                career("所有者検証", "正規更新"),
                employee.accountPublicId(), traceId(6));
        assertThatThrownBy(() -> service.submit(employee.employeePublicId(), employee.accountPublicId(),
                draft.publicId(), 0, traceId(7)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status().value()).isEqualTo(409));
    }

    @Test
    void resubmissionAfterReturnUsesSameLogicalIdAndNextRevision() {
        Actor employee = actor("QITEST");
        var draft = service.create(employee.employeePublicId(), Type.CAREER,
                career("再提出検証", "初版"),
                employee.accountPublicId(), traceId(8));
        jdbc.sql("UPDATE talent_submissions SET status='RETURNED',return_reason='根拠を追記してください' WHERE id=:id")
                .param("id", draft.id()).update();

        var revised = service.update(employee.employeePublicId(), draft.publicId(), 0,
                career("再提出検証", "根拠を追記"),
                employee.accountPublicId(), traceId(9));

        assertThat(revised.publicId()).isNotEqualTo(draft.publicId());
        assertThat(revised.logicalPublicId()).isEqualTo(draft.logicalPublicId());
        assertThat(revised.revisionNo()).isEqualTo(draft.revisionNo() + 1);
        assertThat(revised.status()).isEqualTo(TalentSubmission.Status.DRAFT);
        assertThat(jdbc.sql("SELECT status FROM talent_submissions WHERE id=:id")
                .param("id", draft.id()).query(String.class).single()).isEqualTo("RETURNED");
        assertThat(jdbc.sql("SELECT CAST(payload_json AS VARCHAR) FROM talent_submissions WHERE id=:id")
                .param("id", draft.id()).query(String.class).single()).contains("初版");
    }

    @Test
    void submittedVersionCannotBeEditedAndStaleSubmitDoesNotDuplicateNotification() {
        Actor employee = actor("QITEST");
        var draft = service.create(employee.employeePublicId(), Type.CAREER,
                career("状態競合検証", "担当実績"), employee.accountPublicId(), traceId(10));
        var submitted = service.submit(employee.employeePublicId(), employee.accountPublicId(),
                draft.publicId(), 0, traceId(11));

        assertThatThrownBy(() -> service.update(employee.employeePublicId(), draft.publicId(), submitted.version(),
                career("状態競合検証", "変更"), employee.accountPublicId(), traceId(12)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("TALENT_STATE_CONFLICT"));
        assertThatThrownBy(() -> service.submit(employee.employeePublicId(), employee.accountPublicId(),
                draft.publicId(), 0, traceId(13)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status().value()).isEqualTo(409));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notifications WHERE dedupe_key LIKE :key")
                .param("key", "talent:" + draft.publicId() + ":submit:%")
                .query(Integer.class).single()).isEqualTo(1);
    }

    private Actor actor(String employeeNo) {
        return jdbc.sql("""
                SELECT e.public_id employee_public_id,a.public_id account_public_id
                FROM employees e JOIN accounts a ON a.employee_id=e.id WHERE e.employee_no=:employeeNo
                """).param("employeeNo", employeeNo)
                .query((rs, row) -> new Actor(rs.getString("employee_public_id").trim(),
                        rs.getString("account_public_id").trim())).single();
    }

    private TalentPayloads.CareerPayload career(String projectName, String achievements) {
        return new TalentPayloads.CareerPayload(projectName, "IT", "担当", LocalDate.of(2026, 8, 1),
                null, "概要", achievements, "Java");
    }

    private String traceId(int value) {
        return "01J" + String.format("%023d", value);
    }

    private record Actor(String employeePublicId, String accountPublicId) {
    }
}
