package com.query.insight.analysis;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.query.insight.audit.AuditService;
import com.query.insight.common.ApiException;
import com.query.insight.common.Hashing;
import com.query.insight.common.PublicIdGenerator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AiAnalysisService {
    private final JdbcClient jdbc;
    private final OpenAiAnalysisClient client;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String apiKey;

    public AiAnalysisService(JdbcClient jdbc, OpenAiAnalysisClient client, AuditService audit,
            ObjectMapper objectMapper, @Value("${app.features.ai-enabled}") boolean enabled,
            @Value("${app.openai.api-key:}") String apiKey) {
        this.jdbc = jdbc;
        this.client = client;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.apiKey = apiKey;
    }

    public AnalysisResponse create(String employeePublicId, String accountPublicId, String traceId) {
        if (!enabled) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_FEATURE_DISABLED",
                    "AI分析は設定で無効になっています");
        }
        if (apiKey.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_NOT_CONFIGURED",
                    "OpenAI APIキーが設定されていません");
        }
        Context context = context(employeePublicId);
        List<OpenAiAnalysisClient.AxisInput> axes = jdbc.sql("""
                SELECT c.axis_code,c.display_name,s.level,s.evidence
                FROM evaluation_criteria c
                JOIN evaluation_periods p ON p.criteria_version_id=c.criteria_version_id
                JOIN evaluation_targets t ON t.period_id=p.id
                JOIN self_evaluation_details s ON s.target_id=t.id AND s.axis_code=c.axis_code
                WHERE t.id=:targetId ORDER BY c.sort_order
                """).param("targetId", context.targetId())
                .query((rs, row) -> new OpenAiAnalysisClient.AxisInput(rs.getString("axis_code"),
                        rs.getString("display_name"), rs.getInt("level"), rs.getString("evidence")))
                .list();
        if (axes.size() != 6) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_INPUT_INCOMPLETE",
                    "AI分析には6軸すべての評価と根拠が必要です");
        }

        String requestFingerprint = fingerprint(context.targetPublicId(), axes);
        OpenAiAnalysisClient.AnalysisPayload payload = client.analyze(context.periodName(), axes,
                Hashing.sha256(employeePublicId));
        Instant generatedAt = Instant.now();
        String publicId = PublicIdGenerator.next();
        long accountId = jdbc.sql("SELECT id FROM accounts WHERE public_id=:publicId")
                .param("publicId", accountPublicId).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO ai_analysis_results(public_id,employee_id,evaluation_target_id,provider,model,
                  request_fingerprint,response_json,generated_at,generated_by_account_id)
                VALUES (:publicId,:employeeId,:targetId,'OPENAI',:model,:fingerprint,
                  CAST(:response AS JSONB),:generatedAt,:accountId)
                """).param("publicId", publicId).param("employeeId", context.employeeId())
                .param("targetId", context.targetId()).param("model", client.model())
                .param("fingerprint", requestFingerprint).param("response", toJson(payload))
                .param("generatedAt", Timestamp.from(generatedAt)).param("accountId", accountId).update();
        audit.record(accountId, "AI_ANALYSIS_CREATED", "EVALUATION_TARGET", context.targetPublicId(),
                "SUCCESS", "SELF", traceId == null ? PublicIdGenerator.next() : traceId);
        return new AnalysisResponse(publicId, context.periodName(), payload.summary(), payload.strengths(),
                payload.growthAreas(), payload.recommendedActions(), client.model(), generatedAt);
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

    private String fingerprint(String targetPublicId, List<OpenAiAnalysisClient.AxisInput> axes) {
        return Hashing.sha256(targetPublicId + ":" + toJson(axes));
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
            List<OpenAiAnalysisClient.Insight> strengths, List<OpenAiAnalysisClient.Insight> growthAreas,
            List<OpenAiAnalysisClient.RecommendedAction> recommendedActions, String model, Instant generatedAt) {
    }
}
