package com.query.insight.evaluation;

import com.query.insight.common.ApiException;
import org.springframework.http.HttpStatus;

public final class EvaluationWorkflow {
    private EvaluationWorkflow() {
    }

    public static Status requireTransition(Status from, Action action) {
        if (action == Action.SELF_SUBMIT && (from == Status.SELF_IN_PROGRESS || from == Status.SELF_RETURNED)) {
            return Status.SELF_SUBMITTED;
        }
        if (action == Action.MANAGER_SAVE && from == Status.SELF_SUBMITTED) return Status.MANAGER_IN_PROGRESS;
        if (action == Action.MANAGER_SAVE && (from == Status.MANAGER_IN_PROGRESS || from == Status.MANAGER_RETURNED)) {
            return from;
        }
        if (action == Action.MANAGER_RETURN_EMPLOYEE
                && (from == Status.SELF_SUBMITTED || from == Status.MANAGER_IN_PROGRESS)) return Status.SELF_RETURNED;
        if (action == Action.MANAGER_SUBMIT
                && (from == Status.MANAGER_IN_PROGRESS || from == Status.MANAGER_RETURNED)) return Status.EXECUTIVE_REVIEW;
        if (action == Action.EXECUTIVE_RETURN && from == Status.EXECUTIVE_REVIEW) return Status.MANAGER_RETURNED;
        if (action == Action.EXECUTIVE_APPROVE && from == Status.EXECUTIVE_REVIEW) return Status.FINALIZED;
        if (action == Action.EXECUTIVE_REOPEN && from == Status.FINALIZED) return Status.MANAGER_RETURNED;
        throw new ApiException(HttpStatus.CONFLICT, "EVALUATION_STATE_INVALID",
                "現在の状態ではこの評価操作を実行できません");
    }

    public enum Status {
        SELF_IN_PROGRESS, SELF_SUBMITTED, SELF_RETURNED, MANAGER_IN_PROGRESS, MANAGER_RETURNED,
        EXECUTIVE_REVIEW, FINALIZED;

        public static Status parse(String value) {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw new ApiException(HttpStatus.CONFLICT, "EVALUATION_STATE_UNKNOWN", "評価状態が不正です");
            }
        }
    }

    public enum Action {
        SELF_SUBMIT, MANAGER_SAVE, MANAGER_RETURN_EMPLOYEE, MANAGER_SUBMIT,
        EXECUTIVE_RETURN, EXECUTIVE_APPROVE, EXECUTIVE_REOPEN
    }
}
