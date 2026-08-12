package com.query.insight.talent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.query.insight.common.ApiException;
import com.query.insight.talent.TalentSubmission.Status;
import com.query.insight.talent.TalentSubmission.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
class TalentSubmissionRepositoryIntegrationTests {
    @Autowired
    private TalentSubmissionRepository repository;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void createsUpdatesAndSubmitsDraftWithOptimisticLocking() {
        long employeeId = jdbc.sql("SELECT id FROM employees WHERE employee_no='QITEST'")
                .query(Long.class).single();
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        var initial = new TalentPayloads.SkillPayload("01J00000000000000000000001", 3,
                new BigDecimal("1.0"), LocalDate.of(2026, 8, 1), "担当実績");

        var draft = repository.createDraft(employeeId, Type.SKILL, initial, null, now);

        assertThat(draft.publicId()).isEqualTo(draft.logicalPublicId());
        assertThat(draft.revisionNo()).isEqualTo(1);
        assertThat(draft.status()).isEqualTo(Status.DRAFT);
        assertThat(draft.version()).isZero();
        assertThat(draft.payload().path("level").asInt()).isEqualTo(3);

        var changed = new TalentPayloads.SkillPayload("01J00000000000000000000001", 4,
                new BigDecimal("2.5"), LocalDate.of(2026, 8, 2), "更新した担当実績");
        var saved = repository.updateDraft(draft.id(), Status.DRAFT, changed, 0, now.plusSeconds(1));
        var submitted = repository.markSubmitted(draft.id(), Status.DRAFT, 1, now.plusSeconds(2));

        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.payload().path("level").asInt()).isEqualTo(4);
        assertThat(submitted.status()).isEqualTo(Status.SUBMITTED);
        assertThat(submitted.version()).isEqualTo(2);
        assertThat(submitted.submittedAt()).isEqualTo(now.plusSeconds(2));
        assertThat(repository.findByPublicId(draft.publicId())).contains(submitted);
    }

    @Test
    void rejectsStaleDraftUpdateWithoutChangingPayload() {
        long employeeId = jdbc.sql("SELECT id FROM employees WHERE employee_no='QITEST'")
                .query(Long.class).single();
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        var initial = new TalentPayloads.KnowledgePayload("01J00000000000000000000001", 2, "担当実績");
        var draft = repository.createDraft(employeeId, Type.KNOWLEDGE, initial, null, now);
        repository.updateDraft(draft.id(), Status.DRAFT,
                new TalentPayloads.KnowledgePayload("01J00000000000000000000001", 3, "更新1"), 0,
                now.plusSeconds(1));

        assertThatThrownBy(() -> repository.updateDraft(draft.id(), Status.DRAFT,
                new TalentPayloads.KnowledgePayload("01J00000000000000000000001", 5, "競合更新"), 0,
                now.plusSeconds(2)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(409);
                    assertThat(exception.code()).isEqualTo("OPTIMISTIC_LOCK_CONFLICT");
                });
        assertThat(repository.findByPublicId(draft.publicId()).orElseThrow().payload().path("level").asInt())
                .isEqualTo(3);
    }
}
