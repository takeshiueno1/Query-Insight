package com.query.insight.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileStatusCalculatorTests {
    @Test
    void assignsGradesAtEachBoundaryUsingTheUnroundedScore() {
        assertThat(ProfileStatusCalculator.grade(new BigDecimal("90"))).isEqualTo("S");
        assertThat(ProfileStatusCalculator.grade(new BigDecimal("89.999"))).isEqualTo("A");
        assertThat(ProfileStatusCalculator.grade(new BigDecimal("80"))).isEqualTo("A");
        assertThat(ProfileStatusCalculator.grade(new BigDecimal("70"))).isEqualTo("B");
        assertThat(ProfileStatusCalculator.grade(new BigDecimal("60"))).isEqualTo("C");
        assertThat(ProfileStatusCalculator.grade(new BigDecimal("50"))).isEqualTo("D");
        assertThat(ProfileStatusCalculator.grade(new BigDecimal("49.999"))).isEqualTo("F");
    }

    @Test
    void calculatesWeightedApprovedProfileScoresAndCapsCareerAndCertifications() {
        var result = ProfileStatusCalculator.calculate(new ProfileStatusCalculator.Input(
                List.of(5, 3), List.of(4), 180, 7));

        assertThat(result.skillScore()).isEqualByComparingTo("80.00");
        assertThat(result.knowledgeScore()).isEqualByComparingTo("80.00");
        assertThat(result.careerScore()).isEqualByComparingTo("100.00");
        assertThat(result.certificationScore()).isEqualByComparingTo("100.00");
        assertThat(result.totalScore()).isEqualByComparingTo("88.00");
        assertThat(result.grade()).isEqualTo("A");
    }

    @Test
    void treatsEmptySkillAndKnowledgeListsAsZero() {
        var result = ProfileStatusCalculator.calculate(new ProfileStatusCalculator.Input(
                List.of(), List.of(), 0, 0));

        assertThat(result.skillScore()).isEqualByComparingTo("0.00");
        assertThat(result.knowledgeScore()).isEqualByComparingTo("0.00");
        assertThat(result.totalScore()).isEqualByComparingTo("0.00");
        assertThat(result.grade()).isEqualTo("F");
    }

    @Test
    void rejectsInvalidLevelsAndNegativeCounts() {
        assertThatIllegalArgumentException().isThrownBy(() -> ProfileStatusCalculator.calculate(
                new ProfileStatusCalculator.Input(List.of(0), List.of(), 0, 0)));
        assertThatIllegalArgumentException().isThrownBy(() -> ProfileStatusCalculator.calculate(
                new ProfileStatusCalculator.Input(List.of(6), List.of(), 0, 0)));
        assertThatIllegalArgumentException().isThrownBy(() -> ProfileStatusCalculator.calculate(
                new ProfileStatusCalculator.Input(List.of(), List.of(), -1, 0)));
        assertThatIllegalArgumentException().isThrownBy(() -> ProfileStatusCalculator.calculate(
                new ProfileStatusCalculator.Input(List.of(), List.of(), 0, -1)));
    }

    @Test
    void rejectsNullInputExplicitly() {
        assertThatNullPointerException().isThrownBy(() -> ProfileStatusCalculator.calculate(null));
    }
}
