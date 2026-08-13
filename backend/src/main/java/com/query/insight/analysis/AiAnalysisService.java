package com.query.insight.analysis;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.query.insight.audit.AuditService;
import com.query.insight.common.ApiException;
import com.query.insight.common.Hashing;
import com.query.insight.common.PublicIdGenerator;
import com.query.insight.status.ProfileStatusService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AiAnalysisService {
    private final JdbcClient jdbc;
    private final AiAnalysisClient client;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final PrototypeAnalysisService prototype;
    private final ProfileStatusService profileStatuses;
    private final Clock clock;
    private final boolean enabled;
    private final String configuredProvider;

    @Autowired
    public AiAnalysisService(JdbcClient jdbc, AiAnalysisClient client, AuditService audit,
            ObjectMapper objectMapper, PrototypeAnalysisService prototype, ProfileStatusService profileStatuses,
            @Value("${app.features.ai-enabled}") boolean enabled,
            @Value("${app.ai.provider:ollama}") String configuredProvider) {
        this(jdbc, client, audit, objectMapper, prototype, profileStatuses, Clock.systemUTC(), enabled,
                configuredProvider);
    }

    AiAnalysisService(JdbcClient jdbc, AiAnalysisClient client, AuditService audit,
            ObjectMapper objectMapper, PrototypeAnalysisService prototype, ProfileStatusService profileStatuses,
            Clock clock, boolean enabled, String configuredProvider) {
        this.jdbc = jdbc;
        this.client = client;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.prototype = prototype;
        this.profileStatuses = profileStatuses;
        this.clock = clock;
        this.enabled = enabled;
        this.configuredProvider = configuredProvider;
    }

    public AnalysisResponse create(String employeePublicId, String accountPublicId, String traceId) {
        if (enabled && !configuredProvider.equalsIgnoreCase(client.provider())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_NOT_CONFIGURED",
                    "利用可能なAIプロバイダーはollamaです");
        }
        Context context = context(employeePublicId);
        var profileStatus = profileStatuses.currentForEmployee(employeePublicId);
        List<AiAnalysisClient.AxisInput> axes = jdbc.sql("""
                SELECT c.axis_code,c.display_name,m.level,m.comment evidence
                FROM evaluation_criteria c
                JOIN evaluation_periods p ON p.criteria_version_id=c.criteria_version_id
                JOIN evaluation_targets t ON t.period_id=p.id
                JOIN manager_evaluation_details m ON m.manager_evaluation_id=t.current_manager_evaluation_id
                  AND m.axis_code=c.axis_code
                WHERE t.id=:targetId AND t.status='FINALIZED' ORDER BY c.sort_order
                """).param("targetId", context.targetId())
                .query((rs, row) -> new AiAnalysisClient.AxisInput(rs.getString("axis_code"),
                        rs.getString("display_name"), rs.getInt("level"), rs.getString("evidence")))
                .list();
        AiAnalysisClient.TalentProfileInput talentProfile = talentProfile(context.employeeId());
        String requestFingerprint = fingerprint(context.targetPublicId(), profileStatus, axes, talentProfile);
        AnalysisResponse analysis = analyze(context, profileStatus, axes, talentProfile);
        AiAnalysisClient.AnalysisPayload payload = new AiAnalysisClient.AnalysisPayload(analysis.summary(),
                analysis.strengths(), analysis.growthAreas(), analysis.recommendedActions());
        Instant generatedAt = analysis.generatedAt();
        String publicId = PublicIdGenerator.next();
        long accountId = jdbc.sql("SELECT id FROM accounts WHERE public_id=:publicId")
                .param("publicId", accountPublicId).query(Long.class).single();
        String provider = "AI".equals(analysis.analysisMode()) ? client.provider() : "PROTOTYPE";
        jdbc.sql("""
                INSERT INTO ai_analysis_results(public_id,employee_id,evaluation_target_id,provider,model,
                  request_fingerprint,response_json,generated_at,generated_by_account_id)
                VALUES (:publicId,:employeeId,:targetId,:provider,:model,:fingerprint,
                  CAST(:response AS JSONB),:generatedAt,:accountId)
                """).param("publicId", publicId).param("employeeId", context.employeeId())
                .param("targetId", context.targetId()).param("provider", provider)
                .param("model", analysis.model())
                .param("fingerprint", requestFingerprint).param("response", toJson(payload))
                .param("generatedAt", Timestamp.from(generatedAt)).param("accountId", accountId).update();
        audit.record(accountId, "AI_ANALYSIS_CREATED", "EVALUATION_TARGET", context.targetPublicId(),
                "SUCCESS", "SELF", traceId == null ? PublicIdGenerator.next() : traceId);
        return new AnalysisResponse(publicId, context.periodName(), payload.summary(), payload.strengths(),
                payload.growthAreas(), payload.recommendedActions(), analysis.model(), generatedAt,
                analysis.analysisMode());
    }

    private AnalysisResponse analyze(Context context, ProfileStatusService.ProfileStatusResponse profileStatus,
            List<AiAnalysisClient.AxisInput> axes, AiAnalysisClient.TalentProfileInput talentProfile) {
        if (!enabled) return prototype.analyze(profileStatus, talentProfile);
        try {
            AiAnalysisClient.AnalysisPayload payload = client.analyze(context.periodName(), axes, talentProfile);
            return new AnalysisResponse(null, context.periodName(), payload.summary(), payload.strengths(),
                    payload.growthAreas(), payload.recommendedActions(), client.model(), clock.instant(), "AI");
        } catch (ResourceAccessException exception) {
            return prototype.analyze(profileStatus, talentProfile);
        } catch (ApiException exception) {
            if (List.of("AI_PROVIDER_UNAVAILABLE", "AI_PROVIDER_TIMEOUT").contains(exception.code())) {
                return prototype.analyze(profileStatus, talentProfile);
            }
            throw exception;
        }
    }

    private Context context(String employeePublicId) {
        return jdbc.sql("""
                SELECT e.id employee_id,t.id target_id,t.public_id target_public_id,p.name period_name
                FROM evaluation_targets t
                JOIN employees e ON e.id=t.employee_id
                JOIN evaluation_periods p ON p.id=t.period_id
                WHERE e.public_id=:employeePublicId AND p.status='OPEN'
                ORDER BY p.start_date DESC LIMIT 1
                """).param("employeePublicId", employeePublicId)
                .query((rs, row) -> new Context(rs.getLong("employee_id"), rs.getLong("target_id"),
                        rs.getString("target_public_id"), rs.getString("period_name")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVALUATION_NOT_FOUND",
                        "AI分析対象の評価がありません"));
    }

    private AiAnalysisClient.TalentProfileInput talentProfile(long employeeId) {
        List<AiAnalysisClient.SkillInput> skills = jdbc.sql("""
                SELECT sm.name,es.proficiency_level,es.years_experience,es.evidence
                FROM employee_skills es JOIN skill_masters sm ON sm.id=es.skill_id
                WHERE es.employee_id=:employeeId AND EXISTS (
                  SELECT 1 FROM talent_submissions ts WHERE ts.employee_id=es.employee_id
                    AND ts.talent_type='SKILL' AND ts.logical_public_id=es.public_id AND ts.status='APPROVED')
                ORDER BY es.proficiency_level DESC,sm.name LIMIT 12
                """).param("employeeId", employeeId)
                .query((rs, row) -> new AiAnalysisClient.SkillInput(rs.getString("name"),
                        rs.getInt("proficiency_level"), rs.getDouble("years_experience"), rs.getString("evidence")))
                .list();
        List<AiAnalysisClient.KnowledgeInput> knowledge = jdbc.sql("""
                SELECT km.name,ek.proficiency_level,ek.evidence
                FROM employee_knowledge ek JOIN knowledge_masters km ON km.id=ek.knowledge_id
                WHERE ek.employee_id=:employeeId AND EXISTS (
                  SELECT 1 FROM talent_submissions ts WHERE ts.employee_id=ek.employee_id
                    AND ts.talent_type='KNOWLEDGE' AND ts.logical_public_id=ek.public_id AND ts.status='APPROVED')
                ORDER BY ek.proficiency_level DESC,km.name LIMIT 10
                """).param("employeeId", employeeId)
                .query((rs, row) -> new AiAnalysisClient.KnowledgeInput(rs.getString("name"),
                        rs.getInt("proficiency_level"), rs.getString("evidence"))).list();
        List<AiAnalysisClient.ExperienceInput> experiences = jdbc.sql("""
                SELECT role_name,industry,summary,achievements,technologies FROM career_histories
                WHERE employee_id=:employeeId AND EXISTS (
                  SELECT 1 FROM talent_submissions ts WHERE ts.employee_id=career_histories.employee_id
                    AND ts.talent_type='CAREER' AND ts.logical_public_id=career_histories.public_id
                    AND ts.status='APPROVED') ORDER BY start_date DESC LIMIT 5
                """).param("employeeId", employeeId)
                .query((rs, row) -> new AiAnalysisClient.ExperienceInput(rs.getString("role_name"),
                        rs.getString("industry"), rs.getString("summary"), rs.getString("achievements"),
                        rs.getString("technologies"))).list();
        List<AiAnalysisClient.CertificationInput> certifications = jdbc.sql("""
                SELECT cm.name,cm.issuer FROM employee_certifications ec
                JOIN certification_masters cm ON cm.id=ec.certification_id
                WHERE ec.employee_id=:employeeId AND ec.verification_status='VERIFIED'
                  AND EXISTS (SELECT 1 FROM talent_submissions ts WHERE ts.employee_id=ec.employee_id
                    AND ts.talent_type='CERTIFICATION' AND ts.logical_public_id=ec.public_id
                    AND ts.status='APPROVED')
                ORDER BY ec.acquired_on DESC LIMIT 10
                """).param("employeeId", employeeId)
                .query((rs, row) -> new AiAnalysisClient.CertificationInput(rs.getString("name"),
                        rs.getString("issuer"))).list();
        return new AiAnalysisClient.TalentProfileInput(null, 0,
                skills, knowledge, experiences, certifications);
    }

    private String fingerprint(String targetPublicId, ProfileStatusService.ProfileStatusResponse profileStatus,
            List<AiAnalysisClient.AxisInput> axes,
            AiAnalysisClient.TalentProfileInput talentProfile) {
        return Hashing.sha256(targetPublicId + ":" + toJson(profileStatus) + ":" + toJson(axes)
                + ":" + toJson(talentProfile));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("AI analysis data could not be serialized", exception);
        }
    }

    record Context(long employeeId, long targetId, String targetPublicId, String periodName) {
    }

    public record AnalysisResponse(String publicId, String periodName, String summary,
            List<AiAnalysisClient.Insight> strengths, List<AiAnalysisClient.Insight> growthAreas,
            List<AiAnalysisClient.RecommendedAction> recommendedActions, String model, Instant generatedAt,
            String analysisMode) {
    }
}
