package com.query.insight.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.query.insight.common.ApiException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OllamaAnalysisClientTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OllamaAnalysisClient client = new OllamaAnalysisClient(objectMapper, "qwen-test",
            "http://ollama.example:11434", Duration.ofSeconds(5), HttpClient.newHttpClient());

    @Test
    void requestKeepsIdentityDataLocalAndRequiresStructuredOutput() throws Exception {
        String body = client.requestBody("2026年度 下期評価",
                List.of(new AiAnalysisClient.AxisInput("TECHNICAL", "技術力", 4, "障害を再発防止した")),
                new AiAnalysisClient.TalentProfileInput(
                        "シニアソフトウェアエンジニア", 6,
                        List.of(new AiAnalysisClient.SkillInput("Java", 4, 5.5, "API開発を主導")),
                        List.of(new AiAnalysisClient.KnowledgeInput("システム設計", 3, "設計レビューを担当")),
                        List.of(new AiAnalysisClient.ExperienceInput("開発リーダー", "B2B SaaS",
                                "基盤刷新", "リードタイムを短縮", "Java、PostgreSQL")),
                        List.of(new AiAnalysisClient.CertificationInput("応用情報技術者", "情報処理推進機構"))));
        JsonNode request = objectMapper.readTree(body);

        assertThat(request.path("model").asText()).isEqualTo("qwen-test");
        assertThat(request.path("stream").asBoolean()).isFalse();
        assertThat(request.path("think").asBoolean()).isFalse();
        assertThat(request.path("format").path("additionalProperties").asBoolean()).isFalse();
        assertThat(request.path("format").path("properties").path("recommendedActions")
                .path("items").path("properties").path("priority").path("enum")).hasSize(3);
        assertThat(request.path("messages").get(1).path("content").asText())
                .contains("TECHNICAL", "Java", "システム設計", "開発リーダー", "シニアソフトウェアエンジニア")
                .doesNotContain("employeePublicId", "email", "employeeNo");
        assertThat(request.path("messages").get(0).path("content").asText())
                .contains("ランク判定", "昇進", "昇格", "降格", "採用", "解雇", "報酬", "給与", "賞与",
                        "配置判断", "異動", "人事判断");
    }

    @Test
    void parsesStructuredResponsePayload() throws Exception {
        String content = """
                {"summary":"安定した遂行力があります","strengths":[{"title":"技術力","evidence":"障害対応の実績"}],"growthAreas":[{"title":"設計力","evidence":"設計根拠を増やす"}],"recommendedActions":[{"action":"設計レビューを主導する","priority":"HIGH"}]}
                """;
        var responseNode = objectMapper.createObjectNode();
        responseNode.putObject("message").put("role", "assistant").put("content", content);
        String response = objectMapper.writeValueAsString(responseNode);

        AiAnalysisClient.AnalysisPayload payload = client.parseResponseBody(response);

        assertThat(payload.summary()).contains("遂行力");
        assertThat(payload.strengths()).extracting(AiAnalysisClient.Insight::title).containsExactly("技術力");
        assertThat(payload.recommendedActions()).extracting(AiAnalysisClient.RecommendedAction::priority)
                .containsExactly("HIGH");
    }

    @Test
    void rejectsIncompleteStructuredResponse() throws Exception {
        var responseNode = objectMapper.createObjectNode();
        responseNode.putObject("message").put("role", "assistant")
                .put("content", """
                        {"summary":"不完全","strengths":[],"growthAreas":[],"recommendedActions":[]}
                        """);
        String response = objectMapper.writeValueAsString(responseNode);

        assertThatThrownBy(() -> client.parseResponseBody(response))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("応答形式が不正");
    }

    @Test
    void rejectsRankAndPersonnelDecisionsInEveryFreeTextField() throws Exception {
        List<String> forbiddenTerms = List.of("Sランク", "A評価", "Bランク相当", "C評価", "S・評価",
                "ランクを判定", "評価は判定", "人事判断");
        for (String forbiddenTerm : forbiddenTerms) {
            assertForbidden(responseBody(content(forbiddenTerm, "技術力", "障害対応の実績", "設計レビューを主導")));
        }
        assertForbidden(responseBody(content("安定した遂行力", "昇進候補", "障害対応の実績", "設計レビューを主導")));
        assertForbidden(responseBody(content("安定した遂行力", "技術力", "昇格対象", "設計レビューを主導")));
        assertForbidden(responseBody(content("安定した遂行力", "技術力", "障害対応の実績", "配置判断を行う")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"採用を推奨", "解雇判断", "昇進を決定", "昇格候補", "降格対象",
            "異動すべき", "配置を推奨", "報酬判断", "給与を決定", "賞与候補"})
    void rejectsPersonnelDecisionsWithDecisionContext(String decision) throws Exception {
        assertForbidden(responseBody(content(decision, "設計力", "障害対応の実績", "レビューを主導")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"新技術を採用した", "報酬系の設計", "配置技術", "給与計算システム", "賞与計算機能"})
    void allowsBenignBusinessAndTechnicalTerms(String benignText) throws Exception {
        String response = responseBody(content("安定した遂行力", "設計力", benignText, "レビューを主導する"));

        assertThat(client.parseResponseBody(response).summary()).contains("遂行力");
    }

    private void assertForbidden(String responseBody) {
        assertThatThrownBy(() -> client.parseResponseBody(responseBody))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AI_RESPONSE_PROHIBITED"));
    }

    private String responseBody(String content) throws Exception {
        var responseNode = objectMapper.createObjectNode();
        responseNode.putObject("message").put("role", "assistant").put("content", content);
        return objectMapper.writeValueAsString(responseNode);
    }

    private String content(String summary, String title, String evidence, String action) throws Exception {
        var content = objectMapper.createObjectNode();
        content.put("summary", summary);
        content.putArray("strengths").addObject().put("title", title).put("evidence", evidence);
        content.putArray("growthAreas").addObject().put("title", "設計力").put("evidence", "設計根拠を増やす");
        content.putArray("recommendedActions").addObject().put("action", action).put("priority", "HIGH");
        return objectMapper.writeValueAsString(content);
    }
}
