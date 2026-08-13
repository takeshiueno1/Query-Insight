package com.query.insight.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.query.insight.common.ApiException;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
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

        assertThat(jdbc.sql("""
                SELECT request_type FROM master_addition_requests WHERE public_id=:publicId
                """).param("publicId", request.publicId()).query(String.class).single()).isEqualTo("SKILL");
        assertThat(jdbc.sql("""
                SELECT request_description FROM master_addition_requests WHERE public_id=:publicId
                """).param("publicId", request.publicId()).query(String.class).single()).isEqualTo("クラウド設計検証");

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

    @Test
    void v7BackfillsLegacyMasterRequestFieldsWithoutDeletingTheRequest() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:legacy_master_request;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "sa", "", true);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("5")).load().migrate();
        JdbcClient legacyJdbc = JdbcClient.create(dataSource);
        String legacyPayload = "{\"code\":\"" + "L".repeat(1001) + "\"}";
        insertLegacyMasterRequest(legacyJdbc, legacyPayload);

        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("7"));
        assertThat(legacyJdbc.sql("""
                SELECT request_type FROM master_addition_requests
                WHERE public_id='08M00000000000000000000001'
                """).query(String.class).single()).isEqualTo("SKILL");
        assertThat(legacyJdbc.sql("""
                SELECT request_description FROM master_addition_requests
                WHERE public_id='08M00000000000000000000001'
                """).query(String.class).single()).hasSize(1000);
        assertThat(legacyJdbc.sql("""
                SELECT LENGTH(CAST(proposed_payload_json AS VARCHAR)) FROM master_addition_requests
                WHERE public_id='08M00000000000000000000001'
                """).query(Integer.class).single()).isGreaterThan(1000);
    }

    @Test
    void maximumEscapedMasterPayloadUsesShortNameAsRequestDescription() throws Exception {
        Actor employee = actor("QITEST");
        String name = "N".repeat(100);
        var payload = json.createObjectNode()
                .put("code", "A".repeat(40))
                .put("name", name)
                .put("category", "C".repeat(40))
                .put("description", "\"".repeat(500));

        var request = service.create(employee.accountPublicId(), MasterRequestService.Type.SKILL, payload, traceId(10));

        assertThat(jdbc.sql("""
                SELECT LENGTH(CAST(proposed_payload_json AS VARCHAR)) FROM master_addition_requests
                WHERE public_id=:publicId
                """).param("publicId", request.publicId()).query(Integer.class).single()).isGreaterThan(1000);
        assertThat(jdbc.sql("""
                SELECT request_description FROM master_addition_requests WHERE public_id=:publicId
                """).param("publicId", request.publicId()).query(String.class).single()).isEqualTo(name);
    }

    private void insertLegacyMasterRequest(JdbcClient legacyJdbc, String payload) {
        legacyJdbc.sql("""
                INSERT INTO departments(public_id,code,name,status,version,created_at,updated_at)
                VALUES ('01M00000000000000000000001','LEGACY','旧データ部','ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).update();
        legacyJdbc.sql("""
                INSERT INTO employees(public_id,employee_no,last_name,first_name,email,department_id,
                  employment_status,version,created_at,updated_at)
                VALUES ('01M00000000000000000000002','QI-LEGACY','旧','申請','legacy@example.invalid',
                  (SELECT id FROM departments WHERE code='LEGACY'),'ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).update();
        legacyJdbc.sql("""
                INSERT INTO accounts(public_id,employee_id,login_id_normalized,password_hash,status,
                  failed_count,password_changed_at,version)
                VALUES ('02M00000000000000000000001',(SELECT id FROM employees WHERE employee_no='QI-LEGACY'),
                  'legacy','unused','ACTIVE',0,CURRENT_TIMESTAMP,0)
                """).update();
        legacyJdbc.sql("""
                INSERT INTO master_addition_requests(public_id,requested_by_account_id,master_type,
                  proposed_payload_json,status,version,requested_at)
                VALUES ('08M00000000000000000000001',(SELECT id FROM accounts WHERE login_id_normalized='legacy'),
                  'SKILL',CAST(:payload AS JSONB),'SUBMITTED',0,CURRENT_TIMESTAMP)
                """).param("payload", payload).update();
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
