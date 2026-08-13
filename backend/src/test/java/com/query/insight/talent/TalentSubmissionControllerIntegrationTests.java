package com.query.insight.talent;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class TalentSubmissionControllerIntegrationTests {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void employeeCreatesUpdatesListsAndSubmitsOwnSkill() throws Exception {
        String masterPublicId = jdbc.sql("SELECT public_id FROM skill_masters ORDER BY id LIMIT 1")
                .query(String.class).single();
        String createBody = """
                {"payload":{"masterPublicId":"%s","level":3,"yearsExperience":2.5,
                "lastUsedOn":"2026-08-01","evidence":"案件で利用"}}
                """.formatted(masterPublicId);

        String created = mvc.perform(post("/api/v1/talent-submissions/SKILL")
                        .with(employeeJwt("QITEST", "GENERAL"))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        String publicId = json.readTree(created).path("publicId").asText();

        mvc.perform(put("/api/v1/talent-submissions/{id}", publicId)
                        .with(employeeJwt("QITEST", "GENERAL"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody.replace("\"level\":3", "\"level\":4")
                                .replace("{\"payload\":", "{\"version\":0,\"payload\":")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.level").value(4))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(get("/api/v1/talent-submissions/me").param("type", "SKILL")
                        .with(employeeJwt("QITEST", "GENERAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.publicId == '%s')]", publicId).exists());

        mvc.perform(post("/api/v1/talent-submissions/{id}/submit", publicId)
                        .with(employeeJwt("QITEST", "GENERAL"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void rejectsMissingVersionAndHidesAnotherEmployeesSubmission() throws Exception {
        String created = mvc.perform(post("/api/v1/talent-submissions/CAREER")
                        .with(employeeJwt("QITEST", "GENERAL"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payload":{"projectName":"API所有者検証","industry":"IT","roleName":"担当",
                                "startDate":"2026-08-01","endDate":null,"summary":"概要",
                                "achievements":"成果","technologies":"Java"}}
                                """))
                .andReturn().getResponse().getContentAsString();
        String publicId = json.readTree(created).path("publicId").asText();

        mvc.perform(post("/api/v1/talent-submissions/{id}/submit", publicId)
                        .with(employeeJwt("QITEST", "GENERAL"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(put("/api/v1/talent-submissions/{id}", publicId)
                        .with(employeeJwt("QI0003", "GENERAL"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"payload":{"projectName":"API所有者検証","industry":"IT",
                                "roleName":"担当","startDate":"2026-08-01","endDate":null,
                                "summary":"概要","achievements":"成果","technologies":"Java"}}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TALENT_SUBMISSION_NOT_FOUND"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor employeeJwt(
            String employeeNo, String role) {
        String employeePublicId = jdbc.sql("SELECT public_id FROM employees WHERE employee_no=:employeeNo")
                .param("employeeNo", employeeNo).query(String.class).single();
        String accountPublicId = jdbc.sql("SELECT a.public_id FROM accounts a JOIN employees e ON e.id=a.employee_id "
                        + "WHERE e.employee_no=:employeeNo")
                .param("employeeNo", employeeNo).query(String.class).single();
        return jwt().jwt(token -> token.claim("accountPublicId", accountPublicId)
                        .claim("employeePublicId", employeePublicId).claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
