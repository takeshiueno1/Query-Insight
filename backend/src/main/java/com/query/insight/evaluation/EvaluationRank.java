package com.query.insight.evaluation;

import com.query.insight.common.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;

public enum EvaluationRank {
    S(5, "100"),
    A(4, "85"),
    B(3, "75"),
    C(2, "65"),
    D(1, "55"),
    F(0, "0");

    private final int level;
    private final String score;

    EvaluationRank(int level, String score) {
        this.level = level;
        this.score = score;
    }

    public int level() {
        return level;
    }

    public String score() {
        return score;
    }

    public static EvaluationRank fromCode(String code) {
        if (code != null) {
            for (EvaluationRank rank : values()) {
                if (rank.name().equals(code)) return rank;
            }
        }
        throw invalid();
    }

    public static EvaluationRank fromLevel(int level) {
        for (EvaluationRank rank : values()) {
            if (rank.level == level) return rank;
        }
        throw invalid();
    }

    public static EvaluationRank overall(List<EvaluationRank> ranks) {
        if (ranks == null || ranks.size() != 6 || ranks.stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid();
        }
        int total = ranks.stream().mapToInt(rank -> Integer.parseInt(rank.score)).sum();
        if (total >= 90 * ranks.size()) return S;
        if (total >= 80 * ranks.size()) return A;
        if (total >= 70 * ranks.size()) return B;
        if (total >= 60 * ranks.size()) return C;
        if (total >= 50 * ranks.size()) return D;
        return F;
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "EVALUATION_RANK_INVALID",
                "評価ランクはS、A、B、C、D、Fのいずれかで指定してください");
    }
}
