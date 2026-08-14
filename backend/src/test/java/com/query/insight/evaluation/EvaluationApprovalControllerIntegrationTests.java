package com.query.insight.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class EvaluationApprovalControllerIntegrationTests {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void draftManagerRanksStayPrivateUntilAllScopeFinalizationAndEmployeeResultHasNoNumericEvaluationFields()
            throws Exception {
        String employeeNo = "QI0011";
        String targetPublicId = targetPublicId(employeeNo);
        String managerEmployeeNo = managerEmployeeNo(targetPublicId);
        jdbc.sql("DELETE FROM self_evaluation_details WHERE target_id="
                        + "(SELECT id FROM evaluation_targets WHERE public_id=:publicId)")
                .param("publicId", targetPublicId).update();
        jdbc.sql("UPDATE evaluation_targets SET status='DRAFT',current_manager_evaluation_id=NULL "
                        + "WHERE public_id=:publicId")
                .param("publicId", targetPublicId).update();
        long version = targetVersion(targetPublicId);

        mvc.perform(put("/api/v1/manager-evaluations/{id}", targetPublicId)
                .with(officer(managerEmployeeNo, "SUBORDINATES"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(managerPayload(version, true, "6軸を総合して判断しました")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MANAGER_IN_PROGRESS"))
                .andExpect(jsonPath("$.details[0].managerRank").value("S"));

        mvc.perform(post("/api/v1/manager-evaluations/{id}/submit", targetPublicId)
                .with(officer(managerEmployeeNo, "SUBORDINATES"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("version", targetVersion(targetPublicId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTIVE_REVIEW"))
                .andExpect(jsonPath("$.grade").value("C"));

        mvc.perform(get("/api/v1/evaluations/me/final-result").with(general(employeeNo)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/executive/evaluations/{id}/approve", targetPublicId)
                .with(officer("QI0039", "ALL"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("version", targetVersion(targetPublicId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINALIZED"));

        mvc.perform(get("/api/v1/evaluations/me/final-result").with(general(employeeNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINALIZED"))
                .andExpect(jsonPath("$.finalRank").value("C"))
                .andExpect(jsonPath("$.summary").value("6軸を総合して判断しました"))
                .andExpect(jsonPath("$.finalizedAt").isNotEmpty())
                .andExpect(jsonPath("$.details.length()").value(6))
                .andExpect(jsonPath("$.details[0].managerRank").value("S"))
                .andExpect(jsonPath("$.details[5].managerRank").value("F"))
                .andExpect(jsonPath("$.finalScore").doesNotExist())
                .andExpect(jsonPath("$.selfLevel").doesNotExist())
                .andExpect(jsonPath("$.managerLevel").doesNotExist())
                .andExpect(jsonPath("$.details[0].selfLevel").doesNotExist())
                .andExpect(jsonPath("$.details[0].managerLevel").doesNotExist());
    }

    @Test
    void exposesRoleProtectedManagerExecutiveAndEmployeeResultApis() throws Exception {
        mvc.perform(get("/api/v1/manager-evaluations").with(jwt()
                .jwt(jwt -> jwt.claim("accountPublicId", accountPublicId("QI0002"))
                        .claim("employeePublicId", employeePublicId("QI0002"))
                        .claim("scopes", java.util.List.of("SUBORDINATES")))
                .authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/executive/evaluations").with(jwt()
                .jwt(jwt -> jwt.claim("accountPublicId", accountPublicId("QI0039"))
                        .claim("employeePublicId", employeePublicId("QI0039"))
                        .claim("scopes", java.util.List.of("ALL")))
                .authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/evaluations/me/final-result").with(jwt()
                .jwt(jwt -> jwt.claim("accountPublicId", accountPublicId("QITEST"))
                        .claim("employeePublicId", employeePublicId("QITEST"))
                        .claim("scopes", java.util.List.of("SELF")))
                .authorities(new SimpleGrantedAuthority("ROLE_GENERAL"))))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/manager-evaluations").with(jwt()
                .jwt(jwt -> jwt.claim("accountPublicId", accountPublicId("QITEST"))
                        .claim("employeePublicId", employeePublicId("QITEST"))
                        .claim("scopes", java.util.List.of("SELF")))
                .authorities(new SimpleGrantedAuthority("ROLE_GENERAL"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void managerApiRejectsMissingAxisUnknownRankAndIncompleteSubmissionAsBadRequests() throws Exception {
        String targetPublicId = targetPublicId("QI0012");
        String managerEmployeeNo = managerEmployeeNo(targetPublicId);
        jdbc.sql("UPDATE evaluation_targets SET status='DRAFT',current_manager_evaluation_id=NULL "
                        + "WHERE public_id=:publicId")
                .param("publicId", targetPublicId).update();
        long version = targetVersion(targetPublicId);
        List<Map<String, Object>> missingAxis = rankDetails(true).subList(0, 5);
        mvc.perform(put("/api/v1/manager-evaluations/{id}", targetPublicId)
                .with(officer(managerEmployeeNo, "SUBORDINATES"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "version", version, "details", missingAxis, "summary", "総評"))))
                .andExpect(status().isBadRequest());

        List<Map<String, Object>> unknownRank = new ArrayList<>(rankDetails(true));
        unknownRank.set(0, detail("TECHNICAL", "E", "判断根拠"));
        mvc.perform(put("/api/v1/manager-evaluations/{id}", targetPublicId)
                .with(officer(managerEmployeeNo, "SUBORDINATES"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "version", version, "details", unknownRank, "summary", "総評"))))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/api/v1/manager-evaluations/{id}", targetPublicId)
                .with(officer(managerEmployeeNo, "SUBORDINATES"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(managerPayload(version, false, null)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/manager-evaluations/{id}/submit", targetPublicId)
                .with(officer(managerEmployeeNo, "SUBORDINATES"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("version", targetVersion(targetPublicId)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void managerApiReturnsForbiddenForAValidButNonAssignedSubordinate() throws Exception {
        String targetPublicId = targetPublicId("QI0011");

        mvc.perform(get("/api/v1/manager-evaluations/{id}", targetPublicId)
                .with(officer("QI0002", "SUBORDINATES")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void managerReturnToEmployeeIsRetiredWithoutChangingTheTargetState() throws Exception {
        String targetPublicId = targetPublicId("QI0015");
        String managerEmployeeNo = managerEmployeeNo(targetPublicId);
        jdbc.sql("UPDATE evaluation_targets SET status='SELF_SUBMITTED',current_manager_evaluation_id=NULL "
                        + "WHERE public_id=:publicId")
                .param("publicId", targetPublicId).update();
        long version = targetVersion(targetPublicId);

        mvc.perform(post("/api/v1/manager-evaluations/{id}/return", targetPublicId)
                .with(officer(managerEmployeeNo, "SUBORDINATES"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "version", version, "reason", "本人の再入力を依頼します"))))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SELF_EVALUATION_RETURN_RETIRED"));

        assertThat(jdbc.sql("SELECT status FROM evaluation_targets WHERE public_id=:publicId")
                .param("publicId", targetPublicId).query(String.class).single()).isEqualTo("SELF_SUBMITTED");
    }

    @Test
    void subordinateScopeCannotPerformFinalApproval() throws Exception {
        String targetPublicId = targetPublicId("QI0004");

        mvc.perform(post("/api/v1/executive/evaluations/{id}/approve", targetPublicId)
                .with(officer("QI0002", "SUBORDINATES"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("version", targetVersion(targetPublicId)))))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokedDatabaseGrantCannotBeBypassedByStaleJwtScopeClaim() throws Exception {
        String managerEmployeeNo = "QI0002";
        long grantId = jdbc.sql("SELECT g.id FROM permission_grants g JOIN accounts a ON a.id=g.account_id "
                        + "JOIN employees e ON e.id=a.employee_id WHERE e.employee_no=:employeeNo "
                        + "AND g.scope_type='SUBORDINATES' AND g.revoked_at IS NULL")
                .param("employeeNo", managerEmployeeNo).query(Long.class).single();
        try {
            jdbc.sql("UPDATE permission_grants SET scope_type='SELF' WHERE id=:id")
                    .param("id", grantId).update();

            mvc.perform(get("/api/v1/manager-evaluations")
                    .with(officer(managerEmployeeNo, "SUBORDINATES")))
                    .andExpect(status().isForbidden());
        } finally {
            jdbc.sql("UPDATE permission_grants SET scope_type='SUBORDINATES' WHERE id=:id")
                    .param("id", grantId).update();
        }
    }

    @Test
    void updateEndpointsRejectMissingVersion() throws Exception {
        String targetPublicId = jdbc.sql("SELECT t.public_id FROM evaluation_targets t JOIN employees e "
                        + "ON e.id=t.employee_id WHERE e.employee_no='QI0003'")
                .query(String.class).single();
        mvc.perform(post("/api/v1/manager-evaluations/{id}/submit", targetPublicId).with(jwt()
                .jwt(jwt -> jwt.claim("accountPublicId", accountPublicId("QI0002"))
                        .claim("employeePublicId", employeePublicId("QI0002"))
                        .claim("scopes", java.util.List.of("SUBORDINATES")))
                .authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/executive/evaluations/{id}/approve", targetPublicId).with(jwt()
                .jwt(jwt -> jwt.claim("accountPublicId", accountPublicId("QI0039"))
                        .claim("employeePublicId", employeePublicId("QI0039"))
                        .claim("scopes", java.util.List.of("ALL")))
                .authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void subordinateOfficerCannotUseExecutiveApi() throws Exception {
        mvc.perform(get("/api/v1/executive/evaluations").with(jwt()
                .jwt(jwt -> jwt.claim("accountPublicId", accountPublicId("QI0002"))
                        .claim("employeePublicId", employeePublicId("QI0002"))
                        .claim("scopes", java.util.List.of("SUBORDINATES")))
                .authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
                .andExpect(status().isForbidden());
    }

    private RequestPostProcessor officer(String employeeNo, String scope) {
        return jwt().jwt(jwt -> jwt.claim("accountPublicId", accountPublicId(employeeNo))
                        .claim("employeePublicId", employeePublicId(employeeNo))
                        .claim("scopes", List.of(scope)))
                .authorities(new SimpleGrantedAuthority("ROLE_OFFICER"));
    }

    private RequestPostProcessor general(String employeeNo) {
        return jwt().jwt(jwt -> jwt.claim("accountPublicId", accountPublicId(employeeNo))
                        .claim("employeePublicId", employeePublicId(employeeNo))
                        .claim("scopes", List.of("SELF")))
                .authorities(new SimpleGrantedAuthority("ROLE_GENERAL"));
    }

    private String managerPayload(long version, boolean withComments, String summary) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", version);
        payload.put("details", rankDetails(withComments));
        payload.put("summary", summary);
        return objectMapper.writeValueAsString(payload);
    }

    private static List<Map<String, Object>> rankDetails(boolean withComments) {
        return List.of(
                detail("TECHNICAL", "S", withComments ? "技術力の判断根拠" : null),
                detail("DESIGN", "A", withComments ? "設計力の判断根拠" : null),
                detail("BUSINESS", "B", withComments ? "業務理解の判断根拠" : null),
                detail("COMMUNICATION", "C", withComments ? "説明力の判断根拠" : null),
                detail("DELIVERY", "D", withComments ? "推進力の判断根拠" : null),
                detail("IMPROVEMENT", "F", withComments ? "改善力の判断根拠" : null));
    }

    private static Map<String, Object> detail(String axisCode, String rank, String comment) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("axisCode", axisCode);
        detail.put("rank", rank);
        detail.put("comment", comment);
        return detail;
    }

    private String targetPublicId(String employeeNo) {
        return jdbc.sql("SELECT t.public_id FROM evaluation_targets t JOIN employees e "
                        + "ON e.id=t.employee_id WHERE e.employee_no=:employeeNo")
                .param("employeeNo", employeeNo).query(String.class).single();
    }

    private long targetVersion(String targetPublicId) {
        return jdbc.sql("SELECT version FROM evaluation_targets WHERE public_id=:publicId")
                .param("publicId", targetPublicId).query(Long.class).single();
    }

    private String managerEmployeeNo(String targetPublicId) {
        return jdbc.sql("""
                SELECT e.employee_no FROM evaluation_targets t
                JOIN employees e ON e.id=t.evaluator_employee_id WHERE t.public_id=:publicId
                """).param("publicId", targetPublicId).query(String.class).single();
    }

    private String employeePublicId(String employeeNo) {
        return jdbc.sql("SELECT public_id FROM employees WHERE employee_no=:employeeNo")
                .param("employeeNo", employeeNo).query(String.class).single();
    }

    private String accountPublicId(String employeeNo) {
        return jdbc.sql("SELECT a.public_id FROM accounts a JOIN employees e ON e.id=a.employee_id "
                        + "WHERE e.employee_no=:employeeNo")
                .param("employeeNo", employeeNo).query(String.class).single();
    }
}
