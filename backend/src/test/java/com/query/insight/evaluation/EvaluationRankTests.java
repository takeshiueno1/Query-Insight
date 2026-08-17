package com.query.insight.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.query.insight.common.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class EvaluationRankTests {
    @Test
    void exposesTheContractedLevelAndScoreForEveryRank() {
        assertThat(EvaluationRank.values()).extracting(EvaluationRank::name)
                .containsExactly("S", "A", "B", "C", "D", "F");
        assertThat(EvaluationRank.values()).extracting(EvaluationRank::level)
                .containsExactly(5, 4, 3, 2, 1, 0);
        assertThat(EvaluationRank.values()).extracting(EvaluationRank::score)
                .containsExactly("100", "85", "75", "65", "55", "0");
    }

    @Test
    void convertsEveryCodeAndStoredLevel() {
        assertThat(EvaluationRank.fromCode("S")).isEqualTo(EvaluationRank.S);
        assertThat(EvaluationRank.fromCode("A")).isEqualTo(EvaluationRank.A);
        assertThat(EvaluationRank.fromCode("B")).isEqualTo(EvaluationRank.B);
        assertThat(EvaluationRank.fromCode("C")).isEqualTo(EvaluationRank.C);
        assertThat(EvaluationRank.fromCode("D")).isEqualTo(EvaluationRank.D);
        assertThat(EvaluationRank.fromCode("F")).isEqualTo(EvaluationRank.F);
        assertThat(EvaluationRank.fromLevel(5)).isEqualTo(EvaluationRank.S);
        assertThat(EvaluationRank.fromLevel(4)).isEqualTo(EvaluationRank.A);
        assertThat(EvaluationRank.fromLevel(3)).isEqualTo(EvaluationRank.B);
        assertThat(EvaluationRank.fromLevel(2)).isEqualTo(EvaluationRank.C);
        assertThat(EvaluationRank.fromLevel(1)).isEqualTo(EvaluationRank.D);
        assertThat(EvaluationRank.fromLevel(0)).isEqualTo(EvaluationRank.F);
    }

    @Test
    void rejectsNullUnknownCodesAndOutOfRangeLevelsAsBadRequests() {
        assertBadRequest(() -> EvaluationRank.fromCode(null));
        assertBadRequest(() -> EvaluationRank.fromCode(""));
        assertBadRequest(() -> EvaluationRank.fromCode("E"));
        assertBadRequest(() -> EvaluationRank.fromLevel(-1));
        assertBadRequest(() -> EvaluationRank.fromLevel(6));
    }

    @Test
    void calculatesSixAxisOverallRanksAtEveryBoundary() {
        assertThat(EvaluationRank.overall(List.of(
                EvaluationRank.S, EvaluationRank.S, EvaluationRank.A,
                EvaluationRank.A, EvaluationRank.A, EvaluationRank.A))).isEqualTo(EvaluationRank.S);
        assertThat(EvaluationRank.overall(List.of(
                EvaluationRank.A, EvaluationRank.A, EvaluationRank.A,
                EvaluationRank.B, EvaluationRank.B, EvaluationRank.B))).isEqualTo(EvaluationRank.A);
        assertThat(EvaluationRank.overall(List.of(
                EvaluationRank.B, EvaluationRank.B, EvaluationRank.B,
                EvaluationRank.C, EvaluationRank.C, EvaluationRank.C))).isEqualTo(EvaluationRank.B);
        assertThat(EvaluationRank.overall(List.of(
                EvaluationRank.C, EvaluationRank.C, EvaluationRank.C,
                EvaluationRank.D, EvaluationRank.D, EvaluationRank.D))).isEqualTo(EvaluationRank.C);
        assertThat(EvaluationRank.overall(List.of(
                EvaluationRank.S, EvaluationRank.S, EvaluationRank.S,
                EvaluationRank.F, EvaluationRank.F, EvaluationRank.F))).isEqualTo(EvaluationRank.D);
        assertThat(EvaluationRank.overall(List.of(
                EvaluationRank.S, EvaluationRank.A, EvaluationRank.D,
                EvaluationRank.D, EvaluationRank.F, EvaluationRank.F))).isEqualTo(EvaluationRank.F);
    }

    @Test
    void rejectsAnythingOtherThanSixCompleteAxes() {
        assertBadRequest(() -> EvaluationRank.overall(null));
        assertBadRequest(() -> EvaluationRank.overall(List.of(EvaluationRank.S)));
        assertBadRequest(() -> EvaluationRank.overall(java.util.Arrays.asList(
                EvaluationRank.S, EvaluationRank.A, EvaluationRank.B,
                EvaluationRank.C, EvaluationRank.D, null)));
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
