package com.query.insight.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.query.insight.audit.AuditService;
import com.query.insight.common.ApiException;
import com.query.insight.status.ProfileStatusService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
class AiAnalysisFallbackIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-08-14T03:00:00Z");

    @Autowired private JdbcClient jdbc;
    @Autowired private AuditService audit;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProfileStatusService profileStatuses;
    @Autowired private AnalysisPrivacySanitizer privacySanitizer;
    private Actor actor;

    @BeforeEach
    void setUp() {
        actor = jdbc.sql("""
                SELECT e.public_id employee_public_id,a.public_id account_public_id
                FROM employees e JOIN accounts a ON a.employee_id=e.id WHERE e.employee_no='QITEST'
                """).query((rs, row) -> new Actor(rs.getString("employee_public_id").trim(),
                        rs.getString("account_public_id").trim())).single();
    }

    @Test
    void disabledAiUsesPrototypeWithoutCallingClientAndPersistsAudit() {
        StubClient client = new StubClient();
        var response = service(client, false).create(actor.employeePublicId(), actor.accountPublicId(), traceId(1));

        assertThat(response.analysisMode()).isEqualTo("PROTOTYPE");
        assertThat(response.model()).isEqualTo("ルールベース V1");
        assertThat(response.toString()).contains("得意分野").doesNotContain("専門知識");
        assertThat(client.calls).isZero();
        assertPersistedAndAudited(response.publicId(), "PROTOTYPE", "ルールベース V1", traceId(1));
    }

    @Test
    void connectionRefusedResourceAccessAndTimeoutUsePrototype() {
        for (RuntimeException failure : List.of(
                new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "AI_PROVIDER_UNAVAILABLE", "接続できません"),
                new ResourceAccessException("Connection refused"),
                new ApiException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,
                        "AI_PROVIDER_TIMEOUT", "タイムアウトしました"))) {
            StubClient client = new StubClient();
            client.failure = failure;

            var response = service(client, true).create(actor.employeePublicId(), actor.accountPublicId(), traceId(2));

            assertThat(response.analysisMode()).isEqualTo("PROTOTYPE");
            assertThat(client.calls).isEqualTo(1);
        }
    }

    @Test
    void invalidSchemaAndSerializationFailuresRemainControlledErrors() {
        StubClient invalid = new StubClient();
        invalid.failure = new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                "AI_RESPONSE_INVALID", "AI分析の応答形式が不正です");
        assertThatThrownBy(() -> service(invalid, true)
                .create(actor.employeePublicId(), actor.accountPublicId(), traceId(3)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AI_RESPONSE_INVALID"));

        StubClient serialization = new StubClient();
        serialization.failure = new IllegalStateException("AI analysis data could not be serialized");
        assertThatThrownBy(() -> service(serialization, true)
                .create(actor.employeePublicId(), actor.accountPublicId(), traceId(4)))
                .isInstanceOf(IllegalStateException.class);

        StubClient prohibited = new StubClient();
        prohibited.failure = new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                "AI_RESPONSE_PROHIBITED", "AI分析の応答に人事判断またはランク判定が含まれています");
        assertThatThrownBy(() -> service(prohibited, true)
                .create(actor.employeePublicId(), actor.accountPublicId(), traceId(6)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AI_RESPONSE_PROHIBITED"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_analysis_results WHERE generated_at=:generatedAt")
                .param("generatedAt", java.sql.Timestamp.from(NOW)).query(Integer.class).single()).isZero();
    }

    @Test
    void everyAiBoundFreeTextRemovesStructuredIdentityAndGenericIdentifiers() {
        actor = actor("QI0005");
        StubClient client = new StubClient();
        SensitiveIdentity identity = jdbc.sql("""
                SELECT e.last_name,e.first_name,e.email,e.employee_no,e.public_id employee_public_id,
                  d.name department_name,d.code department_code,d.public_id department_public_id,
                  a.public_id account_public_id,a.login_id_normalized,t.public_id target_public_id
                FROM employees e
                JOIN accounts a ON a.employee_id=e.id
                JOIN evaluation_targets t ON t.employee_id=e.id
                JOIN evaluation_periods p ON p.id=t.period_id AND p.status='OPEN'
                LEFT JOIN departments d ON d.id=e.department_id
                WHERE e.public_id=:employeePublicId
                ORDER BY p.start_date DESC LIMIT 1
                """).param("employeePublicId", actor.employeePublicId())
                .query((rs, row) -> new SensitiveIdentity(rs.getString("last_name"), rs.getString("first_name"),
                        rs.getString("email"), rs.getString("employee_no"), rs.getString("employee_public_id").trim(),
                        rs.getString("department_name"), rs.getString("department_code"),
                        rs.getString("department_public_id").trim(), rs.getString("account_public_id").trim(),
                        rs.getString("login_id_normalized"), rs.getString("target_public_id").trim()))
                .single();
        String genericEmail = "other.person@example.net";
        String genericPublicId = com.query.insight.common.PublicIdGenerator.next();
        String fullName = identity.lastName() + identity.firstName();
        String sensitiveText = String.join(" / ", identity.values()) + " / " + fullName + " / "
                + identity.lastName() + " " + identity.firstName() + " / " + genericEmail + " / " + genericPublicId;
        seedSensitiveAiInput(identity, sensitiveText, genericEmail, genericPublicId);

        var response = service(client, true).create(
                actor.employeePublicId(), actor.accountPublicId(), traceId(7));

        String captured = client.periodName + "\n" + client.axes + "\n" + client.talent;
        assertThat(captured).contains("[除去]");
        assertThat(captured).doesNotContain(identity.values().toArray(String[]::new))
                .doesNotContain(fullName, identity.lastName() + " " + identity.firstName(),
                        genericEmail, genericPublicId);
        assertThat(response.periodName()).isEqualTo(genericEmail);
        assertThat(jdbc.sql("""
                SELECT comment FROM manager_evaluation_details WHERE manager_evaluation_id=(
                  SELECT current_manager_evaluation_id FROM evaluation_targets WHERE public_id=:targetPublicId)
                LIMIT 1
                """).param("targetPublicId", identity.targetPublicId()).query(String.class).single())
                .isEqualTo(sensitiveText);
    }

    @Test
    void aiRequestRemovesOtherEmployeesAndDepartmentsFromFreeText() {
        actor = actor("QI0005");
        StubClient client = new StubClient();
        OtherIdentity other = jdbc.sql("""
                SELECT e.employee_no,e.last_name,e.first_name,e.email,e.public_id employee_public_id,
                  d.code department_code,d.name department_name,d.public_id department_public_id,
                  a.public_id account_public_id,t.public_id target_public_id
                FROM employees e
                JOIN accounts a ON a.employee_id=e.id
                JOIN evaluation_targets t ON t.employee_id=e.id
                LEFT JOIN departments d ON d.id=e.department_id
                WHERE e.employee_no='QI0006'
                ORDER BY t.id LIMIT 1
                """).query((rs, row) -> new OtherIdentity(rs.getString("employee_no"),
                        rs.getString("last_name"), rs.getString("first_name"), rs.getString("email"),
                        rs.getString("employee_public_id").trim(), rs.getString("department_code"),
                        rs.getString("department_name"), rs.getString("department_public_id").trim(),
                        rs.getString("account_public_id").trim(), rs.getString("target_public_id").trim()))
                .single();
        String genericEmployeeNo = "QI12345";
        String sensitiveText = String.join(" / ", other.values()) + " / "
                + other.lastName() + other.firstName() + " / "
                + other.lastName() + " " + other.firstName() + " / " + genericEmployeeNo;
        jdbc.sql("""
                UPDATE manager_evaluation_details SET comment=:value WHERE manager_evaluation_id=(
                  SELECT t.current_manager_evaluation_id FROM evaluation_targets t
                  JOIN employees e ON e.id=t.employee_id WHERE e.public_id=:employeePublicId)
                """).param("value", sensitiveText).param("employeePublicId", actor.employeePublicId()).update();

        service(client, true).create(actor.employeePublicId(), actor.accountPublicId(), traceId(8));

        String captured = client.axes.toString();
        assertThat(captured).contains("[除去]")
                .doesNotContain(other.values().toArray(String[]::new))
                .doesNotContain(other.lastName() + other.firstName(),
                        other.lastName() + " " + other.firstName(), genericEmployeeNo);
    }

    @Test
    void aiRequestRemovesOneCharacterLastAndFirstNamesOfAnotherEmployee() {
        actor = actor("QI0005");
        String lastName = "李";
        String firstName = "蓮";
        jdbc.sql("""
                INSERT INTO employees(public_id,employee_no,last_name,first_name,email,employment_status,
                  version,created_at,updated_at)
                VALUES (:publicId,'QI9001',:lastName,:firstName,'one-character-name@example.invalid','ACTIVE',
                  0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).param("publicId", com.query.insight.common.PublicIdGenerator.next())
                .param("lastName", lastName).param("firstName", firstName).update();
        String sensitiveText = "別社員: " + lastName + " / " + firstName + " / " + lastName + firstName;
        jdbc.sql("""
                UPDATE manager_evaluation_details SET comment=:value WHERE manager_evaluation_id=(
                  SELECT t.current_manager_evaluation_id FROM evaluation_targets t
                  JOIN employees e ON e.id=t.employee_id WHERE e.public_id=:employeePublicId)
                """).param("value", sensitiveText).param("employeePublicId", actor.employeePublicId()).update();
        StubClient client = new StubClient();

        service(client, true).create(actor.employeePublicId(), actor.accountPublicId(), traceId(9));

        assertThat(client.axes.toString()).contains("[除去]").doesNotContain(lastName, firstName);
    }

    @Test
    void sanitizerRemovesEnglishIdentityCaseInsensitivelyAndTreatsRegexCharactersLiterally() {
        jdbc.sql("""
                UPDATE employees SET last_name='Alice',first_name='Smith'
                WHERE employee_no='QI0006'
                """).update();
        jdbc.sql("""
                UPDATE departments SET code='DevTeam',name='Dév.Team+[A]'
                WHERE id=(SELECT department_id FROM employees WHERE employee_no='QI0006')
                """).update();
        var emptyTalent = new AiAnalysisClient.TalentProfileInput(null, 0,
                List.of(), List.of(), List.of(), List.of());

        var sanitized = privacySanitizer.sanitize(
                "alice smith / DEVTEAM / dÉv.team+[a] / dévXteamA", List.of(), emptyTalent);

        assertThat(sanitized.periodName())
                .isEqualTo("[除去] / [除去] / [除去] / dévXteamA");
    }

    @Test
    void successfulAiKeepsModelOutputPersistenceAuditAndExcludesPrivateEvaluation() {
        actor = actor("QI0005");
        StubClient client = new StubClient();
        Identity identity = jdbc.sql("""
                SELECT e.last_name,e.first_name,e.email,e.employee_no,d.name department_name
                FROM employees e LEFT JOIN departments d ON d.id=e.department_id
                WHERE e.public_id=:employeePublicId
                """).param("employeePublicId", actor.employeePublicId())
                .query((rs, row) -> new Identity(rs.getString("last_name"), rs.getString("first_name"),
                        rs.getString("email"), rs.getString("employee_no"), rs.getString("department_name")))
                .single();
        insertUnapprovedSkill(actor.employeePublicId());
        jdbc.sql("""
                UPDATE self_evaluation_details SET evidence='PRIVATE_SELF_EVIDENCE'
                WHERE target_id=(SELECT t.id FROM evaluation_targets t JOIN employees e ON e.id=t.employee_id
                  WHERE e.public_id=:employeePublicId)
                """).param("employeePublicId", actor.employeePublicId()).update();

        var response = service(client, true).create(actor.employeePublicId(), actor.accountPublicId(), traceId(5));

        assertThat(response.analysisMode()).isEqualTo("AI");
        assertThat(response.model()).isEqualTo("test-model");
        assertThat(response.summary()).isEqualTo("AIによる育成助言");
        assertThat(client.axes).hasSize(6);
        assertThat(client.axes.toString()).doesNotContain("PRIVATE_SELF_EVIDENCE");
        assertThat(client.talent.toString()).doesNotContain("UNAPPROVED_TALENT_EVIDENCE",
                actor.employeePublicId(), actor.accountPublicId(), identity.lastName(), identity.firstName(),
                identity.email(), identity.employeeNo(), identity.departmentName());
        assertPersistedAndAudited(response.publicId(), "OLLAMA", "test-model", traceId(5));
    }

    private AiAnalysisService service(StubClient client, boolean enabled) {
        return new AiAnalysisService(jdbc, client, audit, objectMapper,
                new PrototypeAnalysisService(Clock.fixed(NOW, ZoneOffset.UTC)), profileStatuses, privacySanitizer,
                Clock.fixed(NOW, ZoneOffset.UTC), enabled, "ollama");
    }

    private Actor actor(String employeeNo) {
        return jdbc.sql("""
                SELECT e.public_id employee_public_id,a.public_id account_public_id
                FROM employees e JOIN accounts a ON a.employee_id=e.id WHERE e.employee_no=:employeeNo
                """).param("employeeNo", employeeNo)
                .query((rs, row) -> new Actor(rs.getString("employee_public_id").trim(),
                        rs.getString("account_public_id").trim())).single();
    }

    private void insertUnapprovedSkill(String employeePublicId) {
        String publicId = com.query.insight.common.PublicIdGenerator.next();
        jdbc.sql("""
                INSERT INTO employee_skills(public_id,employee_id,skill_id,proficiency_level,years_experience,
                  last_used_on,evidence,created_at,updated_at)
                SELECT :publicId,e.id,sm.id,5,1.0,CURRENT_DATE,'UNAPPROVED_TALENT_EVIDENCE',
                  CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                FROM employees e JOIN skill_masters sm ON sm.status='ACTIVE'
                WHERE e.public_id=:employeePublicId AND NOT EXISTS (
                  SELECT 1 FROM employee_skills es WHERE es.employee_id=e.id AND es.skill_id=sm.id)
                ORDER BY sm.id LIMIT 1
                """).param("publicId", publicId).param("employeePublicId", employeePublicId).update();
    }

    private void seedSensitiveAiInput(SensitiveIdentity identity, String sensitiveText,
            String genericEmail, String genericPublicId) {
        jdbc.sql("""
                UPDATE evaluation_periods SET name=:value WHERE id=(
                  SELECT t.period_id FROM evaluation_targets t WHERE t.public_id=:targetPublicId)
                """).param("value", genericEmail).param("targetPublicId", identity.targetPublicId()).update();
        jdbc.sql("""
                UPDATE manager_evaluation_details SET comment=:value WHERE manager_evaluation_id=(
                  SELECT t.current_manager_evaluation_id FROM evaluation_targets t
                  WHERE t.public_id=:targetPublicId)
                """).param("value", sensitiveText).param("targetPublicId", identity.targetPublicId()).update();
        jdbc.sql("""
                UPDATE employee_skills SET evidence=:value WHERE employee_id=(
                  SELECT id FROM employees WHERE public_id=:employeePublicId)
                """).param("value", sensitiveText).param("employeePublicId", identity.employeePublicId()).update();
        jdbc.sql("""
                UPDATE skill_masters SET name=:value WHERE id=(SELECT es.skill_id FROM employee_skills es
                  JOIN talent_submissions ts ON ts.logical_public_id=es.public_id AND ts.status='APPROVED'
                  WHERE es.employee_id=(SELECT id FROM employees WHERE public_id=:employeePublicId) LIMIT 1)
                """).param("value", identity.email()).param("employeePublicId", identity.employeePublicId()).update();
        jdbc.sql("""
                UPDATE employee_knowledge SET evidence=:value WHERE employee_id=(
                  SELECT id FROM employees WHERE public_id=:employeePublicId)
                """).param("value", sensitiveText).param("employeePublicId", identity.employeePublicId()).update();
        jdbc.sql("""
                UPDATE knowledge_masters SET name=:value WHERE id=(SELECT ek.knowledge_id FROM employee_knowledge ek
                  JOIN talent_submissions ts ON ts.logical_public_id=ek.public_id AND ts.status='APPROVED'
                  WHERE ek.employee_id=(SELECT id FROM employees WHERE public_id=:employeePublicId) LIMIT 1)
                """).param("value", identity.employeeNo()).param("employeePublicId", identity.employeePublicId()).update();
        jdbc.sql("""
                UPDATE career_histories SET role_name=:role,industry=:industry,summary=:summary,
                  achievements=:achievements,technologies=:technologies
                WHERE employee_id=(SELECT id FROM employees WHERE public_id=:employeePublicId)
                """).param("role", identity.targetPublicId()).param("industry", genericEmail)
                .param("summary", sensitiveText).param("achievements", genericPublicId)
                .param("technologies", sensitiveText).param("employeePublicId", identity.employeePublicId()).update();
        jdbc.sql("""
                UPDATE certification_masters SET name=:name,issuer=:issuer WHERE id=(
                  SELECT ec.certification_id FROM employee_certifications ec
                  JOIN talent_submissions ts ON ts.logical_public_id=ec.public_id AND ts.status='APPROVED'
                  WHERE ec.employee_id=(SELECT id FROM employees WHERE public_id=:employeePublicId) LIMIT 1)
                """).param("name", identity.departmentName()).param("issuer", identity.departmentCode())
                .param("employeePublicId", identity.employeePublicId()).update();
    }

    private void assertPersistedAndAudited(String publicId, String provider, String model, String traceId) {
        assertThat(jdbc.sql("SELECT provider || ':' || model FROM ai_analysis_results WHERE public_id=:publicId")
                .param("publicId", publicId).query(String.class).single()).isEqualTo(provider + ":" + model);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action='AI_ANALYSIS_CREATED' AND trace_id=:traceId")
                .param("traceId", traceId).query(Integer.class).single()).isEqualTo(1);
    }

    private static String traceId(int value) {
        return "01A" + String.format("%023d", value);
    }

    private record Actor(String employeePublicId, String accountPublicId) {
    }

    private record Identity(String lastName, String firstName, String email, String employeeNo,
            String departmentName) {
    }

    private record SensitiveIdentity(String lastName, String firstName, String email, String employeeNo,
            String employeePublicId, String departmentName, String departmentCode, String departmentPublicId,
            String accountPublicId, String loginId, String targetPublicId) {
        List<String> values() {
            return List.of(lastName, firstName, email, employeeNo, employeePublicId, departmentName,
                    departmentCode, departmentPublicId, accountPublicId, loginId, targetPublicId);
        }
    }

    private record OtherIdentity(String employeeNo, String lastName, String firstName, String email,
            String employeePublicId, String departmentCode, String departmentName, String departmentPublicId,
            String accountPublicId, String targetPublicId) {
        List<String> values() {
            return List.of(employeeNo, lastName, firstName, email, employeePublicId, departmentCode,
                    departmentName, departmentPublicId, accountPublicId, targetPublicId);
        }
    }

    private static final class StubClient implements AiAnalysisClient {
        int calls;
        RuntimeException failure;
        String periodName;
        List<AxisInput> axes = List.of();
        TalentProfileInput talent;

        @Override
        public AnalysisPayload analyze(String periodName, List<AxisInput> axes, TalentProfileInput talentProfile) {
            calls++;
            this.periodName = periodName;
            this.axes = List.copyOf(axes);
            this.talent = talentProfile;
            if (failure != null) throw failure;
            return new AnalysisPayload("AIによる育成助言",
                    List.of(new Insight("強み", "公開済み情報")),
                    List.of(new Insight("成長課題", "公開済み情報")),
                    List.of(new RecommendedAction("次の行動", "HIGH")));
        }

        @Override public String model() { return "test-model"; }
        @Override public String provider() { return "OLLAMA"; }
    }
}
