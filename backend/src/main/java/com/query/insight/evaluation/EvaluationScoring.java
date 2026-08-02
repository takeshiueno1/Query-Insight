package com.query.insight.evaluation;

import com.query.insight.common.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class EvaluationScoring {
    private static final ObjectMapper JSON = new ObjectMapper();

    private EvaluationScoring() {
    }

    public static ScoreResult calculate(List<WeightedLevel> values, String gradeBoundariesJson) {
        if (values == null || values.isEmpty()) throw invalid("評価項目がありません");
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (WeightedLevel value : values) {
            if (value.level() < 1 || value.level() > 5 || value.weight() == null
                    || value.weight().compareTo(BigDecimal.ZERO) <= 0) {
                throw invalid("評価点または重みが不正です");
            }
            weighted = weighted.add(BigDecimal.valueOf(value.level()).multiply(value.weight()));
            totalWeight = totalWeight.add(value.weight());
        }
        BigDecimal score = weighted.divide(totalWeight, 2, RoundingMode.HALF_UP);
        return new ScoreResult(score, grade(score, gradeBoundariesJson));
    }

    private static String grade(BigDecimal score, String json) {
        try {
            JsonNode root = JSON.readTree(json);
            if (root.isTextual()) root = JSON.readTree(root.asText());
            List<Boundary> boundaries = new ArrayList<>();
            root.forEachEntry((name, value) -> {
                if (value.isNumber()) {
                    boundaries.add(new Boundary(name, value.decimalValue()));
                }
            });
            boundaries.sort(Comparator.comparing(Boundary::minimum).reversed());
            return boundaries.stream().filter(boundary -> score.compareTo(boundary.minimum()) >= 0)
                    .map(Boundary::grade).findFirst().orElseThrow();
        } catch (Exception exception) {
            throw invalid("グレード境界の設定が不正です");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "EVALUATION_SCORING_INVALID", message);
    }

    public record WeightedLevel(int level, BigDecimal weight) {
    }

    public record ScoreResult(BigDecimal score, String grade) {
    }

    private record Boundary(String grade, BigDecimal minimum) {
    }
}
