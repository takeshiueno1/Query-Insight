package com.query.insight.talent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.query.insight.common.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TalentPayloadsTests {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 12);
    private static final String MASTER_ID = "01J00000000000000000000001";

    @Test
    void acceptsValidPayloadForEveryTalentType() {
        List<TalentPayloads.Payload> payloads = List.of(
                new TalentPayloads.SkillPayload(MASTER_ID, 5, new BigDecimal("60.0"), TODAY, "成果物"),
                new TalentPayloads.KnowledgePayload(MASTER_ID, 1, "担当経験"),
                new TalentPayloads.CareerPayload("基幹刷新", "製造", "開発者", TODAY.minusYears(1), TODAY,
                        "概要", "成果", "Java, PostgreSQL"),
                new TalentPayloads.CertificationPayload(MASTER_ID, TODAY, TODAY.plusYears(1), "REF-001"));

        assertThatCode(() -> payloads.forEach(payload -> payload.validate(TODAY)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSkillAndKnowledgeLevelsOutsideOneToFive() {
        assertInvalid(new TalentPayloads.SkillPayload(MASTER_ID, 0, BigDecimal.ONE, TODAY, "根拠"));
        assertInvalid(new TalentPayloads.SkillPayload(MASTER_ID, 6, BigDecimal.ONE, TODAY, "根拠"));
        assertInvalid(new TalentPayloads.KnowledgePayload(MASTER_ID, 0, "根拠"));
        assertInvalid(new TalentPayloads.KnowledgePayload(MASTER_ID, 6, "根拠"));
    }

    @Test
    void rejectsInvalidExperienceAndDates() {
        assertInvalid(new TalentPayloads.SkillPayload(MASTER_ID, 3, new BigDecimal("60.1"), TODAY, "根拠"));
        assertInvalid(new TalentPayloads.SkillPayload(MASTER_ID, 3, new BigDecimal("1.25"), TODAY, "根拠"));
        assertInvalid(new TalentPayloads.SkillPayload(MASTER_ID, 3, BigDecimal.ONE, TODAY.plusDays(1), "根拠"));
        assertInvalid(new TalentPayloads.CareerPayload("案件", "業界", "役割", TODAY, TODAY.minusDays(1),
                "概要", "成果", "技術"));
        assertInvalid(new TalentPayloads.CertificationPayload(MASTER_ID, TODAY.plusDays(1), null, null));
        assertInvalid(new TalentPayloads.CertificationPayload(MASTER_ID, TODAY, TODAY.minusDays(1), null));
    }

    @Test
    void rejectsBlankMalformedAndOversizedFields() {
        assertInvalid(new TalentPayloads.SkillPayload("invalid", 3, BigDecimal.ONE, TODAY, "根拠"));
        assertInvalid(new TalentPayloads.KnowledgePayload(MASTER_ID, 3, " "));
        assertInvalid(new TalentPayloads.CareerPayload(" ", "業界", "役割", TODAY, null,
                "概要", "成果", "技術"));
        assertInvalid(new TalentPayloads.CertificationPayload(MASTER_ID, TODAY, null, "x".repeat(101)));
    }

    private void assertInvalid(TalentPayloads.Payload payload) {
        assertThatThrownBy(() -> payload.validate(TODAY))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(400);
                    assertThat(exception.code()).isEqualTo("TALENT_PAYLOAD_INVALID");
                });
    }
}
