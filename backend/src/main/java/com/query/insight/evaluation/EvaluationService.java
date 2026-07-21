package com.query.insight.evaluation;

import com.query.insight.common.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationService {
    private static final Set<String> AXES = Set.of("TECHNICAL", "DESIGN", "BUSINESS", "COMMUNICATION", "DELIVERY", "IMPROVEMENT");
    private final JdbcClient jdbc;

    public EvaluationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public SelfEvaluation get(String employeePublicId) {
        Target target = target(employeePublicId);
        List<Detail> details = jdbc.sql("""
                SELECT c.axis_code,c.display_name,c.description,s.level,s.evidence
                FROM evaluation_criteria c
                JOIN evaluation_periods p ON p.criteria_version_id=c.criteria_version_id
                JOIN evaluation_targets t ON t.period_id=p.id
                LEFT JOIN self_evaluation_details s ON s.target_id=t.id AND s.axis_code=c.axis_code
                WHERE t.id=:targetId ORDER BY c.sort_order
                """).param("targetId", target.id())
                .query((rs, row) -> new Detail(rs.getString("axis_code"), rs.getString("display_name"),
                        rs.getString("description"), (Integer) rs.getObject("level"), rs.getString("evidence")))
                .list();
        return new SelfEvaluation(target.publicId(), target.periodName(), target.status(), target.version(), details);
    }

    @Transactional
    public SelfEvaluation save(String employeePublicId, SaveRequest request) {
        Target target = target(employeePublicId);
        if (!("SELF_IN_PROGRESS".equals(target.status()) || "RETURNED".equals(target.status()))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EVALUATION_STATE_INVALID", "現在の状態では自己評価を編集できません");
        }
        if (request.version() != target.version()) {
            throw conflict();
        }
        validate(request.details(), false);
        for (SaveDetail detail : request.details()) {
            int updated = jdbc.sql("""
                    UPDATE self_evaluation_details SET level=:level,evidence=:evidence
                    WHERE target_id=:targetId AND axis_code=:axisCode
                    """).param("level", detail.level()).param("evidence", detail.evidence().strip())
                    .param("targetId", target.id()).param("axisCode", detail.axisCode()).update();
            if (updated == 0) {
                jdbc.sql("""
                        INSERT INTO self_evaluation_details(target_id,axis_code,level,evidence)
                        VALUES (:targetId,:axisCode,:level,:evidence)
                        """).param("targetId", target.id()).param("axisCode", detail.axisCode())
                        .param("level", detail.level()).param("evidence", detail.evidence().strip()).update();
            }
        }
        int updated = jdbc.sql("UPDATE evaluation_targets SET version=version+1 WHERE id=:id AND version=:version")
                .param("id", target.id()).param("version", request.version()).update();
        if (updated == 0) throw conflict();
        return get(employeePublicId);
    }

    @Transactional
    public SelfEvaluation submit(String employeePublicId, SaveRequest request) {
        SelfEvaluation saved = save(employeePublicId, request);
        validate(request.details(), true);
        double score = request.details().stream().mapToInt(SaveDetail::level).average().orElseThrow();
        int updated = jdbc.sql("""
                UPDATE evaluation_targets SET status='SELF_SUBMITTED',provisional_score=:score,
                  submitted_at=:submittedAt,version=version+1
                WHERE public_id=:publicId AND version=:version AND status IN ('SELF_IN_PROGRESS','RETURNED')
                """).param("score", score).param("submittedAt", Timestamp.from(Instant.now()))
                .param("publicId", saved.publicId())
                .param("version", saved.version()).update();
        if (updated == 0) throw conflict();
        return get(employeePublicId);
    }

    private Target target(String employeePublicId) {
        return jdbc.sql("""
                SELECT t.id,t.public_id,t.status,t.version,p.name period_name
                FROM evaluation_targets t JOIN employees e ON e.id=t.employee_id
                JOIN evaluation_periods p ON p.id=t.period_id
                WHERE e.public_id=:employeePublicId AND p.status='OPEN'
                ORDER BY p.start_date DESC LIMIT 1
                """).param("employeePublicId", employeePublicId)
                .query((rs, row) -> new Target(rs.getLong("id"), rs.getString("public_id"),
                        rs.getString("status"), rs.getLong("version"), rs.getString("period_name")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVALUATION_NOT_FOUND", "現在受付中の自己評価はありません"));
    }

    private static void validate(List<SaveDetail> details, boolean requireEvidence) {
        if (details.size() != AXES.size() || !details.stream().map(SaveDetail::axisCode).collect(java.util.stream.Collectors.toSet()).equals(AXES)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EVALUATION_AXES_INVALID", "6つの評価軸をすべて指定してください");
        }
        for (SaveDetail detail : details) {
            if (detail.level() < 1 || detail.level() > 5) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "EVALUATION_LEVEL_INVALID", "評価レベルは1～5で指定してください");
            }
            if (detail.evidence() != null && detail.evidence().length() > 1500) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "EVIDENCE_TOO_LONG", "評価根拠は1500文字以内で入力してください");
            }
            if (requireEvidence && (detail.evidence() == null || detail.evidence().isBlank())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_REQUIRED", "提出には各評価軸の根拠が必要です");
            }
        }
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT", "他の利用者が更新しました。再読み込みしてください");
    }

    record Target(long id, String publicId, String status, long version, String periodName) {
    }
    public record SelfEvaluation(String publicId, String periodName, String status, long version, List<Detail> details) {
    }
    public record Detail(String axisCode, String displayName, String description, Integer level, String evidence) {
    }
    public record SaveRequest(long version, List<SaveDetail> details) {
    }
    public record SaveDetail(String axisCode, int level, String evidence) {
    }
}
