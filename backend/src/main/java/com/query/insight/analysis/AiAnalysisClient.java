package com.query.insight.analysis;

import java.util.List;

public interface AiAnalysisClient {
    AnalysisPayload analyze(String periodName, List<AxisInput> axes, TalentProfileInput talentProfile);

    String model();

    String provider();

    record AxisInput(String axisCode, String displayName, int level, String evidence) {
    }

    record TalentProfileInput(String currentRole, int tenureYears, List<SkillInput> skills,
            List<KnowledgeInput> knowledge, List<ExperienceInput> experiences,
            List<CertificationInput> certifications) {
    }

    record SkillInput(String name, int level, double yearsExperience, String evidence) {
    }

    record KnowledgeInput(String name, int level, String evidence) {
    }

    record ExperienceInput(String role, String industry, String summary, String achievements,
            String technologies) {
    }

    record CertificationInput(String name, String issuer) {
    }

    record Insight(String title, String evidence) {
    }

    record RecommendedAction(String action, String priority) {
    }

    record AnalysisPayload(String summary, List<Insight> strengths, List<Insight> growthAreas,
            List<RecommendedAction> recommendedActions) {
    }
}
