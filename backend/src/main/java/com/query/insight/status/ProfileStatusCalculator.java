package com.query.insight.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

public final class ProfileStatusCalculator {
    public static final String FORMULA_VERSION = "PROFILE_STATUS_V1";

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal FIVE = BigDecimal.valueOf(5);
    private static final BigDecimal ONE_HUNDRED_TWENTY = BigDecimal.valueOf(120);

    private ProfileStatusCalculator() {
    }

    public static Result calculate(Input input) {
        Objects.requireNonNull(input, "input must not be null");
        if (input.careerMonths() < 0 || input.validCertificationCount() < 0) {
            throw new IllegalArgumentException("career months and certification count must not be negative");
        }

        BigDecimal skill = averageLevel(input.skillLevels());
        BigDecimal knowledge = averageLevel(input.knowledgeLevels());
        BigDecimal career = BigDecimal.valueOf(Math.min(input.careerMonths(), 120))
                .multiply(ONE_HUNDRED).divide(ONE_HUNDRED_TWENTY, 8, RoundingMode.HALF_UP);
        BigDecimal certification = BigDecimal.valueOf(Math.min(input.validCertificationCount(), 5) * 20L);
        BigDecimal total = skill.multiply(new BigDecimal("0.40"))
                .add(knowledge.multiply(new BigDecimal("0.20")))
                .add(career.multiply(new BigDecimal("0.25")))
                .add(certification.multiply(new BigDecimal("0.15")));
        return new Result(scale(skill), scale(knowledge), scale(career), scale(certification), scale(total), grade(total));
    }

    public static String grade(BigDecimal score) {
        Objects.requireNonNull(score, "score must not be null");
        if (score.compareTo(new BigDecimal("90")) >= 0) return "S";
        if (score.compareTo(new BigDecimal("80")) >= 0) return "A";
        if (score.compareTo(new BigDecimal("70")) >= 0) return "B";
        if (score.compareTo(new BigDecimal("60")) >= 0) return "C";
        if (score.compareTo(new BigDecimal("50")) >= 0) return "D";
        return "F";
    }

    private static BigDecimal averageLevel(List<Integer> levels) {
        Objects.requireNonNull(levels, "levels must not be null");
        if (levels.isEmpty()) return BigDecimal.ZERO;
        int total = 0;
        for (Integer level : levels) {
            if (level == null || level < 1 || level > 5) {
                throw new IllegalArgumentException("level must be between 1 and 5");
            }
            total += level;
        }
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(levels.size()), 8, RoundingMode.HALF_UP)
                .divide(FIVE, 8, RoundingMode.HALF_UP).multiply(ONE_HUNDRED);
    }

    private static BigDecimal scale(BigDecimal score) {
        return score.setScale(2, RoundingMode.HALF_UP);
    }

    public record Input(List<Integer> skillLevels, List<Integer> knowledgeLevels,
            int careerMonths, int validCertificationCount) {
    }

    public record Result(BigDecimal skillScore, BigDecimal knowledgeScore, BigDecimal careerScore,
            BigDecimal certificationScore, BigDecimal totalScore, String grade) {
    }
}
