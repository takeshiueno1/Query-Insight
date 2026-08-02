package com.query.insight.evaluation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class EvaluationApprovalControllerIntegrationTests {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void exposesRoleProtectedManagerExecutiveAndEmployeeResultApis() throws Exception {
        mvc.perform(get("/api/v1/manager-evaluations").with(jwt()
                .jwt(jwt -> jwt.claim("accountPublicId", accountPublicId("QI0002"))
                        .claim("employeePublicId", employeePublicId("QI0002")))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/executive/evaluations").with(jwt()
                .jwt(jwt -> jwt.claim("accountPublicId", accountPublicId("QI0039"))
                        .claim("employeePublicId", employeePublicId("QI0039")))
                .authorities(new SimpleGrantedAuthority("ROLE_EXECUTIVE"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/evaluations/me/final-result").with(jwt()
                .jwt(jwt -> jwt.claim("accountPublicId", accountPublicId("QITEST"))
                        .claim("employeePublicId", employeePublicId("QITEST")))
                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/manager-evaluations").with(jwt()
                .jwt(jwt -> jwt.claim("accountPublicId", accountPublicId("QITEST"))
                        .claim("employeePublicId", employeePublicId("QITEST")))
                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateEndpointsRejectMissingVersion() throws Exception {
        String targetPublicId = jdbc.sql("SELECT t.public_id FROM evaluation_targets t JOIN employees e "
                        + "ON e.id=t.employee_id WHERE e.employee_no='QI0003'")
                .query(String.class).single();
        mvc.perform(post("/api/v1/manager-evaluations/{id}/submit", targetPublicId).with(jwt()
                .jwt(jwt -> jwt.claim("accountPublicId", accountPublicId("QI0002"))
                        .claim("employeePublicId", employeePublicId("QI0002")))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER")))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/executive/evaluations/{id}/approve", targetPublicId).with(jwt()
                .jwt(jwt -> jwt.claim("accountPublicId", accountPublicId("QI0039"))
                        .claim("employeePublicId", employeePublicId("QI0039")))
                .authorities(new SimpleGrantedAuthority("ROLE_EXECUTIVE")))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
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
