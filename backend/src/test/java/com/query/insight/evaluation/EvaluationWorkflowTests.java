package com.query.insight.evaluation;

import static com.query.insight.evaluation.EvaluationWorkflow.Action.EXECUTIVE_APPROVE;
import static com.query.insight.evaluation.EvaluationWorkflow.Action.EXECUTIVE_REOPEN;
import static com.query.insight.evaluation.EvaluationWorkflow.Action.EXECUTIVE_RETURN;
import static com.query.insight.evaluation.EvaluationWorkflow.Action.MANAGER_RETURN_EMPLOYEE;
import static com.query.insight.evaluation.EvaluationWorkflow.Action.MANAGER_SAVE;
import static com.query.insight.evaluation.EvaluationWorkflow.Action.MANAGER_SUBMIT;
import static com.query.insight.evaluation.EvaluationWorkflow.Action.SELF_SUBMIT;
import static com.query.insight.evaluation.EvaluationWorkflow.Status.EXECUTIVE_REVIEW;
import static com.query.insight.evaluation.EvaluationWorkflow.Status.FINALIZED;
import static com.query.insight.evaluation.EvaluationWorkflow.Status.DRAFT;
import static com.query.insight.evaluation.EvaluationWorkflow.Status.MANAGER_IN_PROGRESS;
import static com.query.insight.evaluation.EvaluationWorkflow.Status.MANAGER_RETURNED;
import static com.query.insight.evaluation.EvaluationWorkflow.Status.SELF_IN_PROGRESS;
import static com.query.insight.evaluation.EvaluationWorkflow.Status.SELF_RETURNED;
import static com.query.insight.evaluation.EvaluationWorkflow.Status.SELF_SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.query.insight.common.ApiException;
import org.junit.jupiter.api.Test;

class EvaluationWorkflowTests {
    @Test
    void allowsOnlyApprovedWorkflowTransitions() {
        assertThat(EvaluationWorkflow.requireTransition(SELF_IN_PROGRESS, SELF_SUBMIT)).isEqualTo(SELF_SUBMITTED);
        assertThat(EvaluationWorkflow.requireTransition(SELF_RETURNED, SELF_SUBMIT)).isEqualTo(SELF_SUBMITTED);
        assertThat(EvaluationWorkflow.requireTransition(DRAFT, MANAGER_SAVE)).isEqualTo(MANAGER_IN_PROGRESS);
        assertThat(EvaluationWorkflow.requireTransition(SELF_RETURNED, MANAGER_SAVE)).isEqualTo(MANAGER_IN_PROGRESS);
        assertThat(EvaluationWorkflow.requireTransition(SELF_SUBMITTED, MANAGER_SAVE)).isEqualTo(MANAGER_IN_PROGRESS);
        assertThat(EvaluationWorkflow.requireTransition(MANAGER_IN_PROGRESS, MANAGER_SAVE)).isEqualTo(MANAGER_IN_PROGRESS);
        assertThat(EvaluationWorkflow.requireTransition(MANAGER_RETURNED, MANAGER_SAVE)).isEqualTo(MANAGER_RETURNED);
        assertThat(EvaluationWorkflow.requireTransition(MANAGER_IN_PROGRESS, MANAGER_SUBMIT)).isEqualTo(EXECUTIVE_REVIEW);
        assertThat(EvaluationWorkflow.requireTransition(MANAGER_RETURNED, MANAGER_SUBMIT)).isEqualTo(EXECUTIVE_REVIEW);
        assertThat(EvaluationWorkflow.requireTransition(EXECUTIVE_REVIEW, EXECUTIVE_RETURN)).isEqualTo(MANAGER_RETURNED);
        assertThat(EvaluationWorkflow.requireTransition(EXECUTIVE_REVIEW, EXECUTIVE_APPROVE)).isEqualTo(FINALIZED);
        assertThat(EvaluationWorkflow.requireTransition(FINALIZED, EXECUTIVE_REOPEN)).isEqualTo(MANAGER_RETURNED);
    }

    @Test
    void rejectsSkippedOrRepeatedTransitions() {
        assertThatThrownBy(() -> EvaluationWorkflow.requireTransition(SELF_SUBMITTED, EXECUTIVE_APPROVE))
                .isInstanceOf(ApiException.class).hasMessageContaining("状態");
        assertThatThrownBy(() -> EvaluationWorkflow.requireTransition(FINALIZED, EXECUTIVE_APPROVE))
                .isInstanceOf(ApiException.class).hasMessageContaining("状態");
        assertThatThrownBy(() -> EvaluationWorkflow.requireTransition(EXECUTIVE_REVIEW, MANAGER_SAVE))
                .isInstanceOf(ApiException.class).hasMessageContaining("状態");
        assertThatThrownBy(() -> EvaluationWorkflow.requireTransition(SELF_SUBMITTED, MANAGER_RETURN_EMPLOYEE))
                .isInstanceOf(ApiException.class).hasMessageContaining("状態");
        assertThatThrownBy(() -> EvaluationWorkflow.requireTransition(MANAGER_IN_PROGRESS, MANAGER_RETURN_EMPLOYEE))
                .isInstanceOf(ApiException.class).hasMessageContaining("状態");
    }
}
