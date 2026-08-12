package com.query.insight.talent;

import static com.query.insight.talent.TalentSubmission.Action.APPROVE;
import static com.query.insight.talent.TalentSubmission.Action.RETURN;
import static com.query.insight.talent.TalentSubmission.Action.SAVE;
import static com.query.insight.talent.TalentSubmission.Action.SUBMIT;
import static com.query.insight.talent.TalentSubmission.Status.APPROVED;
import static com.query.insight.talent.TalentSubmission.Status.DRAFT;
import static com.query.insight.talent.TalentSubmission.Status.RETURNED;
import static com.query.insight.talent.TalentSubmission.Status.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.query.insight.common.ApiException;
import org.junit.jupiter.api.Test;

class TalentSubmissionTests {
    @Test
    void allowsOnlyEmployeeSubmissionAndManagerDecisionTransitions() {
        assertThat(TalentSubmission.requireTransition(DRAFT, SAVE)).isEqualTo(DRAFT);
        assertThat(TalentSubmission.requireTransition(RETURNED, SAVE)).isEqualTo(RETURNED);
        assertThat(TalentSubmission.requireTransition(DRAFT, SUBMIT)).isEqualTo(SUBMITTED);
        assertThat(TalentSubmission.requireTransition(RETURNED, SUBMIT)).isEqualTo(SUBMITTED);
        assertThat(TalentSubmission.requireTransition(SUBMITTED, APPROVE)).isEqualTo(APPROVED);
        assertThat(TalentSubmission.requireTransition(SUBMITTED, RETURN)).isEqualTo(RETURNED);
    }

    @Test
    void rejectsRepeatedOrOutOfOrderTransitionsWithConflict() {
        assertThatThrownBy(() -> TalentSubmission.requireTransition(APPROVED, APPROVE))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(409);
                    assertThat(exception.code()).isEqualTo("TALENT_STATE_CONFLICT");
                });
        assertThatThrownBy(() -> TalentSubmission.requireTransition(DRAFT, APPROVE))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TalentSubmission.requireTransition(SUBMITTED, SAVE))
                .isInstanceOf(ApiException.class);
    }
}
