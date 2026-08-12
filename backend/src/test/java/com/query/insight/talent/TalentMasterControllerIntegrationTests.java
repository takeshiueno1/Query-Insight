package com.query.insight.talent;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class TalentMasterControllerIntegrationTests {
    @Autowired
    private MockMvc mvc;

    @Test
    void returnsActiveMasterChoicesAndRejectsUnknownType() throws Exception {
        mvc.perform(get("/api/v1/talent-masters/SKILL").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].publicId").isNotEmpty())
                .andExpect(jsonPath("$[0].code").isNotEmpty())
                .andExpect(jsonPath("$[0].name").isNotEmpty());

        mvc.perform(get("/api/v1/talent-masters/UNKNOWN").with(jwt()))
                .andExpect(status().isBadRequest());
    }
}
