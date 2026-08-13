package com.query.insight.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.query.insight.common.ApiException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("local")
class MasterRequestIntegrationTests {
    @Autowired
    private MasterRequestService service;
    @Autowired
    private JdbcClient jdbc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void employeeRequestsMissingSkillAndAdministratorApprovesIt() throws Exception {
        Actor employee = actor("QITEST");
        Actor administrator = actor("QI0001");
        var request = service.create(employee.accountPublicId(), MasterRequestService.Type.SKILL,
                json.readTree("""
                        {"code":"NEW_CLOUD_ARCH","name":"クラウド設計検証","category":"技術",
                        "description":"検証用の追加スキル"}
                        """), traceId(1));

        var approved = service.approve(administrator.accountPublicId(), Set.of("ADMIN"),
                request.publicId(), request.version(), traceId(2));

        assertThat(approved.status()).isEqualTo(MasterRequestService.Status.APPROVED);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM skill_masters WHERE code='NEW_CLOUD_ARCH' AND status='ACTIVE'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notifications WHERE dedupe_key=:key")
                .param("key", "master-request:" + request.publicId() + ":approved")
                .query(Integer.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> service.approve(administrator.accountPublicId(), Set.of("ADMIN"),
                request.publicId(), request.version(), traceId(3)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(409));
    }

    @Test
    void officersCannotReviewMasterRequests() throws Exception {
        Actor employee = actor("QITEST");
        Actor executive = actor("QI0039");
        Actor manager = actor("QI0002");
        var request = service.create(employee.accountPublicId(), MasterRequestService.Type.CERTIFICATION,
                json.readTree("""
                        {"code":"QI_ARCH_2026","name":"Query Insight Architect",
                        "issuer":"Query Insight Association"}
                        """), traceId(4));
        assertThatThrownBy(() -> service.adminList(executive.accountPublicId(), Set.of("OFFICER"),
                MasterRequestService.Status.SUBMITTED))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(403));
        assertThatThrownBy(() -> service.approve(manager.accountPublicId(), Set.of("OFFICER"),
                request.publicId(), request.version(), traceId(5)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(403));
    }

    @Test
    void duplicateNormalizedCodeAndNameReturn409WithoutSecondMaster() throws Exception {
        Actor employee = actor("QITEST");
        Actor administrator = actor("QI0001");
        String existingName = jdbc.sql("SELECT name FROM skill_masters WHERE code='JAVA'")
                .query(String.class).single();
        var codeDuplicate = service.create(employee.accountPublicId(), MasterRequestService.Type.SKILL,
                json.readTree("""
                        {"code":"java","name":"別名Java","category":"技術","description":"重複"}
                        """), traceId(6));
        assertThatThrownBy(() -> service.approve(administrator.accountPublicId(), Set.of("ADMIN"),
                codeDuplicate.publicId(), 0, traceId(7)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(409));

        var nameDuplicate = service.create(employee.accountPublicId(), MasterRequestService.Type.SKILL,
                json.readTree("""
                        {"code":"UNIQUE_TEST_CODE","name":"  %s  ","category":"技術","description":"重複"}
                        """.formatted(existingName.toLowerCase())), traceId(8));
        assertThatThrownBy(() -> service.approve(administrator.accountPublicId(), Set.of("ADMIN"),
                nameDuplicate.publicId(), 0, traceId(9)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(409));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM skill_masters WHERE code='UNIQUE_TEST_CODE'")
                .query(Integer.class).single()).isZero();
    }

    private Actor actor(String employeeNo) {
        return jdbc.sql("""
                SELECT a.public_id FROM accounts a JOIN employees e ON e.id=a.employee_id
                WHERE e.employee_no=:employeeNo
                """).param("employeeNo", employeeNo)
                .query((rs, row) -> new Actor(rs.getString("public_id").trim())).single();
    }

    private String traceId(int value) {
        return "01M" + String.format("%023d", value);
    }

    private record Actor(String accountPublicId) {
    }
}
