package com.query.insight.talent;

import com.query.insight.common.ApiException;
import org.springframework.http.HttpStatus;

public final class TalentSubmission {
    private TalentSubmission() {
    }

    public enum Type {
        SKILL,
        KNOWLEDGE,
        CAREER,
        CERTIFICATION
    }

    public enum Status {
        DRAFT,
        SUBMITTED,
        RETURNED,
        APPROVED,
        SUPERSEDED
    }

    public enum Action {
        SAVE,
        SUBMIT,
        APPROVE,
        RETURN
    }

    public static Status requireTransition(Status current, Action action) {
        if (current == null || action == null) {
            throw stateConflict();
        }
        return switch (action) {
            case SAVE -> {
                if (current != Status.DRAFT && current != Status.RETURNED) throw stateConflict();
                yield current;
            }
            case SUBMIT -> {
                if (current != Status.DRAFT && current != Status.RETURNED) throw stateConflict();
                yield Status.SUBMITTED;
            }
            case APPROVE -> {
                if (current != Status.SUBMITTED) throw stateConflict();
                yield Status.APPROVED;
            }
            case RETURN -> {
                if (current != Status.SUBMITTED) throw stateConflict();
                yield Status.RETURNED;
            }
        };
    }

    private static ApiException stateConflict() {
        return new ApiException(HttpStatus.CONFLICT, "TALENT_STATE_CONFLICT", "現在の状態では操作できません");
    }
}
