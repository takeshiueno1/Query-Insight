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
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_analysis_results WHERE generated_at=:generatedAt")
                .param("generatedAt", java.sql.Timestamp.from(NOW)).query(Integer.class).single()).isZero();
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
                new PrototypeAnalysisService(Clock.fixed(NOW, ZoneOffset.UTC)), profileStatuses,
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

    private static final class StubClient implements AiAnalysisClient {
        int calls;
        RuntimeException failure;
        List<AxisInput> axes = List.of();
        TalentProfileInput talent;

        @Override
        public AnalysisPayload analyze(String periodName, List<AxisInput> axes, TalentProfileInput talentProfile) {
            calls++;
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
