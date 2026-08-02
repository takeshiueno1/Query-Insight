package com.query.insight.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.query.insight.common.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluationScoringTests {
    private static final String BOUNDARIES = "{\"S\":4.50,\"A\":4.00,\"B\":3.00,\"C\":0.00}";

    @Test
    void calculatesWeightedAverageAndRoundsHalfUpToTwoDecimals() {
        var result = EvaluationScoring.calculate(List.of(
                new EvaluationScoring.WeightedLevel(4, new BigDecimal("0.60")),
                new EvaluationScoring.WeightedLevel(3, new BigDecimal("0.40"))), BOUNDARIES);

        assertThat(result.score()).isEqualByComparingTo("3.60");
        assertThat(result.grade()).isEqualTo("B");
    }

    @Test
    void appliesExactGradeBoundary() {
        var result = EvaluationScoring.calculate(List.of(
                new EvaluationScoring.WeightedLevel(4, BigDecimal.ONE)), BOUNDARIES);

        assertThat(result.grade()).isEqualTo("A");
    }

    @Test
    void acceptsJsonTextReturnedByH2JsonbCompatibilityMode() {
        String encoded = "\"{\\\"S\\\":4.50,\\\"A\\\":4.00,\\\"B\\\":3.00,\\\"C\\\":0.00}\"";

        var result = EvaluationScoring.calculate(List.of(
                new EvaluationScoring.WeightedLevel(4, BigDecimal.ONE)), encoded);

        assertThat(result.grade()).isEqualTo("A");
    }

    @Test
    void rejectsEmptyOrZeroWeightInput() {
        assertThatThrownBy(() -> EvaluationScoring.calculate(List.of(), BOUNDARIES))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> EvaluationScoring.calculate(List.of(
                new EvaluationScoring.WeightedLevel(3, BigDecimal.ZERO)), BOUNDARIES))
                .isInstanceOf(ApiException.class);
    }
}
