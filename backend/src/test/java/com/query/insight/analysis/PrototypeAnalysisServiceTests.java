package com.query.insight.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.query.insight.status.ProfileStatusService.ProfileStatusResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrototypeAnalysisServiceTests {
    private static final Instant NOW = Instant.parse("2026-08-14T03:00:00Z");
    private final PrototypeAnalysisService service = new PrototypeAnalysisService(
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void selectsTopStrengthsAndLowestGrowthAreasInDeterministicCategoryOrder() {
        var result = service.analyze(status("75", "75", "30", "0"), talent());

        assertThat(result.strengths()).extracting(AiAnalysisClient.Insight::title)
                .containsExactly("スキル", "得意分野");
        assertThat(result.growthAreas()).extracting(AiAnalysisClient.Insight::title)
                .containsExactly("資格", "業務経歴");
        assertThat(result.growthAreas().getFirst().evidence()).contains("未登録");
        assertThat(result.model()).isEqualTo("ルールベース V1");
        assertThat(result.analysisMode()).isEqualTo("PROTOTYPE");
        assertThat(result.generatedAt()).isEqualTo(NOW);
        assertThat(result.toString()).doesNotContain("専門知識");
    }

    @Test
    void recommendsAllowedPriorityActionsForGrowthAndExistingTalent() {
        var result = service.analyze(status("80", "50", "20", "0"), talent());

        assertThat(result.recommendedActions()).isNotEmpty()
                .allSatisfy(action -> assertThat(action.priority()).isIn("HIGH", "MEDIUM", "LOW"));
        assertThat(result.recommendedActions()).extracting(AiAnalysisClient.RecommendedAction::action)
                .anySatisfy(action -> assertThat(action).contains("資格"))
                .anySatisfy(action -> assertThat(action).containsAnyOf("スキル", "Java"));
    }

    @Test
    void careerOnlyRecommendationUsesExistingRoleAndTechnologyWithoutClaimingSkills() {
        var careerOnly = new AiAnalysisClient.TalentProfileInput(null, 0, List.of(), List.of(),
                List.of(new AiAnalysisClient.ExperienceInput("開発リーダー", "SaaS", "基盤刷新",
                        "リードタイム短縮", "Java、PostgreSQL")), List.of());

        var result = service.analyze(status("0", "0", "60", "0"), careerOnly);

        assertThat(result.recommendedActions()).extracting(AiAnalysisClient.RecommendedAction::action)
                .anySatisfy(action -> assertThat(action).containsAnyOf("開発リーダー", "Java", "PostgreSQL"))
                .noneSatisfy(action -> assertThat(action).contains("承認済みのスキル・得意分野"));
    }

    @Test
    void certificationOnlyRecommendationUsesExistingCertificationWithoutClaimingSkills() {
        var certificationOnly = new AiAnalysisClient.TalentProfileInput(null, 0, List.of(), List.of(), List.of(),
                List.of(new AiAnalysisClient.CertificationInput("応用情報技術者", "IPA")));

        var result = service.analyze(status("0", "0", "0", "70"), certificationOnly);

        assertThat(result.recommendedActions()).extracting(AiAnalysisClient.RecommendedAction::action)
                .anySatisfy(action -> assertThat(action).contains("応用情報技術者"))
                .noneSatisfy(action -> assertThat(action).contains("承認済みのスキル・得意分野"));
    }

    @Test
    void skillOnlyRecommendationUsesExistingTopSkill() {
        var skillOnly = new AiAnalysisClient.TalentProfileInput(null, 0,
                List.of(new AiAnalysisClient.SkillInput("Java", 5, 6, "開発実績")),
                List.of(), List.of(), List.of());

        var result = service.analyze(status("80", "0", "0", "0"), skillOnly);

        assertThat(result.recommendedActions()).extracting(AiAnalysisClient.RecommendedAction::action)
                .anySatisfy(action -> assertThat(action).contains("Java"));
    }

    @Test
    void knowledgeRecommendationUsesUserFacingPreferredFieldWording() {
        var knowledgeOnly = new AiAnalysisClient.TalentProfileInput(null, 0, List.of(),
                List.of(new AiAnalysisClient.KnowledgeInput("設計", 4, "設計実績")), List.of(), List.of());

        var result = service.analyze(status("0", "70", "0", "0"), knowledgeOnly);

        assertThat(result.toString()).contains("得意分野", "承認済み得意分野「設計」")
                .doesNotContain("専門知識");
    }

    @Test
    void allZeroAndNoTalentStillReturnsUsefulJapaneseAdvice() {
        var emptyTalent = new AiAnalysisClient.TalentProfileInput(null, 0,
                List.of(), List.of(), List.of(), List.of());

        var result = service.analyze(status("0", "0", "0", "0"), emptyTalent);

        assertThat(result.summary()).contains("未登録");
        assertThat(result.growthAreas()).isNotEmpty();
        assertThat(result.recommendedActions()).isNotEmpty();
    }

    @Test
    void outputNeverEchoesIdentityValuesEmbeddedInSuppliedFields() {
        String name = "秘密 太郎";
        String email = "secret@example.com";
        String employeeNo = "QI-SECRET-001";
        String department = "機密部門";
        String publicId = "01SECRETIDENTITYPUBLICID00";
        var hostileTalent = new AiAnalysisClient.TalentProfileInput(name, 3,
                List.of(new AiAnalysisClient.SkillInput(email, 4, 2, employeeNo)),
                List.of(new AiAnalysisClient.KnowledgeInput(department, 3, publicId)),
                List.of(new AiAnalysisClient.ExperienceInput(name, department, email, employeeNo, publicId)),
                List.of(new AiAnalysisClient.CertificationInput(employeeNo, email)));

        var result = service.analyze(status("60", "50", "40", "30"), hostileTalent);
        String output = result.toString();

        assertThat(output).doesNotContain(name, email, employeeNo, department, publicId);
    }

    private static ProfileStatusResponse status(String skill, String knowledge, String career, String certification) {
        BigDecimal skillScore = new BigDecimal(skill);
        BigDecimal knowledgeScore = new BigDecimal(knowledge);
        BigDecimal careerScore = new BigDecimal(career);
        BigDecimal certificationScore = new BigDecimal(certification);
        return new ProfileStatusResponse("01STATUSPUBLICID00000000000", skillScore, knowledgeScore,
                careerScore, certificationScore,
                skillScore.add(knowledgeScore).add(careerScore).add(certificationScore), "B",
                List.of(), "V1", NOW, false);
    }

    private static AiAnalysisClient.TalentProfileInput talent() {
        return new AiAnalysisClient.TalentProfileInput("エンジニア", 4,
                List.of(new AiAnalysisClient.SkillInput("Java", 4, 3, "承認済みの利用実績")),
                List.of(new AiAnalysisClient.KnowledgeInput("設計", 3, "承認済みの設計実績")),
                List.of(), List.of());
    }
}
