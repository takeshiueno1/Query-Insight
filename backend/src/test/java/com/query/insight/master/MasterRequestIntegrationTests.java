package com.query.insight.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.query.insight.common.ApiException;
import com.query.insight.common.PublicIdGenerator;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
class MasterRequestIntegrationTests {
    @Autowired
    private MasterRequestService service;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void employeeCreatesSimplifiedRequestWithOnlyTypeAndDescription() throws Exception {
        Actor employee = actor("QITEST");

        mvc.perform(post("/api/v1/master-requests")
                        .with(jwt().jwt(token -> token.claim("accountPublicId", employee.accountPublicId())
                                .claim("roles", Set.of("GENERAL"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"  クラウド資格  ","description":"  AWS認定の追加を希望します  "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("クラウド資格"))
                .andExpect(jsonPath("$.description").value("AWS認定の追加を希望します"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.requesterName").isNotEmpty());
    }

    @Test
    void simplifiedRequestApprovalDoesNotCreateAMaster() {
        Actor employee = actor("QITEST");
        Actor administrator = actor("QI0001");
        int skillsBefore = count("skill_masters");
        int certificationsBefore = count("certification_masters");
        var request = service.create(employee.accountPublicId(), "クラウド資格",
                "AWS認定の追加を希望します", traceId(1));

        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM master_addition_requests WHERE public_id=:publicId
                  AND request_type='クラウド資格' AND request_description='AWS認定の追加を希望します'
                  AND master_type IS NULL AND proposed_payload_json IS NULL AND created_master_public_id IS NULL
                """).param("publicId", request.publicId()).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM audit_logs WHERE target_public_id=:publicId AND action='MASTER_REQUEST_SUBMIT'
                """).param("publicId", request.publicId()).query(Integer.class).single()).isOne();

        var approved = service.approve(administrator.accountPublicId(), Set.of("ADMIN"),
                request.publicId(), request.version(), traceId(2));

        assertThat(approved.status()).isEqualTo(MasterRequestService.Status.APPROVED);
        assertThat(approved.createdMasterPublicId()).isNull();
        assertThat(count("skill_masters")).isEqualTo(skillsBefore);
        assertThat(count("certification_masters")).isEqualTo(certificationsBefore);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notifications WHERE dedupe_key=:key")
                .param("key", "master-request:" + request.publicId() + ":approved")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM audit_logs WHERE target_public_id=:publicId AND action='MASTER_REQUEST_APPROVE'
                """).param("publicId", request.publicId()).query(Integer.class).single()).isOne();
        assertThatThrownBy(() -> service.approve(administrator.accountPublicId(), Set.of("ADMIN"),
                request.publicId(), request.version(), traceId(3)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(409));
    }

    @Test
    void officersCannotReviewMasterRequests() {
        Actor employee = actor("QITEST");
        Actor executive = actor("QI0039");
        Actor manager = actor("QI0002");
        var request = service.create(employee.accountPublicId(), "資格", "追加資格の提案", traceId(4));
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
    void administratorReturnsRequestWithNotificationAuditAndOptimisticLocking() {
        Actor employee = actor("QITEST");
        Actor administrator = actor("QI0001");
        var request = service.create(employee.accountPublicId(), "業務分野", "新しい選択肢が必要です", traceId(6));

        var returned = service.returnRequest(administrator.accountPublicId(), Set.of("ADMIN"),
                request.publicId(), request.version(), "説明を具体化してください", traceId(7));

        assertThat(returned.status()).isEqualTo(MasterRequestService.Status.RETURNED);
        assertThat(returned.version()).isEqualTo(1);
        assertThat(returned.returnReason()).isEqualTo("説明を具体化してください");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notifications WHERE dedupe_key=:key")
                .param("key", "master-request:" + request.publicId() + ":returned")
                .query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM audit_logs WHERE target_public_id=:publicId AND action='MASTER_REQUEST_RETURN'
                """).param("publicId", request.publicId()).query(Integer.class).single()).isOne();
        assertThatThrownBy(() -> service.returnRequest(administrator.accountPublicId(), Set.of("ADMIN"),
                request.publicId(), request.version(), "再度差し戻す", traceId(8)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(409));
    }

    @Test
    void simplifiedRequestRejectsBlankOverLimitAndLegacyPayloadFields() throws Exception {
        Actor employee = actor("QITEST");
        var authentication = jwt().jwt(token -> token.claim("accountPublicId", employee.accountPublicId()))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_GENERAL"));

        mvc.perform(post("/api/v1/master-requests").with(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"   \",\"description\":\"説明\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/master-requests").with(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("type", "T".repeat(101), "description", "説明"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/master-requests").with(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("type", "資格", "description", "D".repeat(1001)))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/master-requests").with(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"資格\",\"description\":\"説明\",\"payload\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void legacyRequestRemainsReadableAndCanUseItsCompletePayloadOnApproval() throws Exception {
        Actor employee = actor("QITEST");
        Actor administrator = actor("QI0001");
        String publicId = PublicIdGenerator.next();
        jdbc.sql("""
                INSERT INTO master_addition_requests(public_id,requested_by_account_id,master_type,
                  proposed_payload_json,request_type,request_description,status,version,requested_at)
                VALUES (:publicId,(SELECT id FROM accounts WHERE public_id=:accountId),'SKILL',
                  CAST(:payload AS JSONB),'SKILL','旧クラウド技術の申請','SUBMITTED',0,CURRENT_TIMESTAMP)
                """).param("publicId", publicId).param("accountId", employee.accountPublicId())
                .param("payload", """
                        {"code":"LEGACY_CLOUD_TASK6","name":"旧クラウド技術","category":"技術","description":"旧契約"}
                        """).update();

        var legacy = service.mine(employee.accountPublicId()).stream()
                .filter(item -> item.publicId().equals(publicId)).findFirst().orElseThrow();
        assertThat(legacy.type()).isEqualTo("SKILL");
        assertThat(legacy.description()).isEqualTo("旧クラウド技術の申請");

        var approved = service.approve(administrator.accountPublicId(), Set.of("ADMIN"),
                publicId, legacy.version(), traceId(9));
        assertThat(approved.createdMasterPublicId()).isNotBlank();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM skill_masters WHERE code='LEGACY_CLOUD_TASK6'")
                .query(Integer.class).single()).isOne();
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

        assertThat(flyway.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("9"));
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
    void maximumSimplifiedFieldsAreStoredWithoutLegacyPayload() {
        Actor employee = actor("QITEST");
        String type = "T".repeat(100);
        String description = "D".repeat(1000);
        var request = service.create(employee.accountPublicId(), type, description, traceId(10));

        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM master_addition_requests WHERE public_id=:publicId
                  AND request_type=:type AND request_description=:description
                  AND master_type IS NULL AND proposed_payload_json IS NULL
                """).param("publicId", request.publicId()).param("type", type)
                .param("description", description).query(Integer.class).single()).isOne();
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

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private String traceId(int value) {
        return "01M" + String.format("%023d", value);
    }

    private record Actor(String accountPublicId) {
    }
}
