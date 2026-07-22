package com.query.insight.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiAnalysisClientTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiAnalysisClient client = new OpenAiAnalysisClient(objectMapper, "test-key", "gpt-test",
            "https://api.openai.example/v1", Duration.ofSeconds(5), HttpClient.newHttpClient());

    @Test
    void requestMinimizesIdentityDataAndRequiresStructuredOutput() throws Exception {
        String body = client.requestBody("2026年度 下期評価",
                List.of(new OpenAiAnalysisClient.AxisInput("TECHNICAL", "技術力", 4, "障害を再発防止した")),
                new OpenAiAnalysisClient.TalentProfileInput(
                        "シニアソフトウェアエンジニア", 6,
                        List.of(new OpenAiAnalysisClient.SkillInput("Java", 4, 5.5, "API開発を主導")),
                        List.of(new OpenAiAnalysisClient.KnowledgeInput("システム設計", 3, "設計レビューを担当")),
                        List.of(new OpenAiAnalysisClient.ExperienceInput("開発リーダー", "B2B SaaS",
                                "基盤刷新", "リードタイムを短縮", "Java、PostgreSQL")),
                        List.of(new OpenAiAnalysisClient.CertificationInput("応用情報技術者", "情報処理推進機構"))),
                "privacy-safe-id");
        JsonNode request = objectMapper.readTree(body);

        assertThat(request.path("model").asText()).isEqualTo("gpt-test");
        assertThat(request.path("store").asBoolean()).isFalse();
        assertThat(request.path("safety_identifier").asText()).isEqualTo("privacy-safe-id");
        assertThat(request.path("text").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(request.path("text").path("format").path("strict").asBoolean()).isTrue();
        assertThat(request.path("input").asText()).contains("TECHNICAL", "Java", "システム設計", "開発リーダー",
                        "シニアソフトウェアエンジニア")
                .doesNotContain("employeePublicId", "email", "employeeNo");
    }

    @Test
    void parsesStructuredResponsePayload() {
        String response = """
                {"output":[{"type":"message","content":[{"type":"output_text","text":"{\\"summary\\":\\"安定した遂行力があります\\",\\"strengths\\":[{\\"title\\":\\"技術力\\",\\"evidence\\":\\"障害対応の実績\\"}],\\"growthAreas\\":[{\\"title\\":\\"設計力\\",\\"evidence\\":\\"設計根拠を増やす\\"}],\\"recommendedActions\\":[{\\"action\\":\\"設計レビューを主導する\\",\\"priority\\":\\"HIGH\\"}]}"}]}]}
                """;

        OpenAiAnalysisClient.AnalysisPayload payload = client.parseResponseBody(response);

        assertThat(payload.summary()).contains("遂行力");
        assertThat(payload.strengths()).extracting(OpenAiAnalysisClient.Insight::title).containsExactly("技術力");
        assertThat(payload.recommendedActions()).extracting(OpenAiAnalysisClient.RecommendedAction::priority)
                .containsExactly("HIGH");
    }
}
