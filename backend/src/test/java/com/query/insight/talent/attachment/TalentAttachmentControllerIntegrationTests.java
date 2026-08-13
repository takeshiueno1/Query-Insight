package com.query.insight.talent.attachment;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.query.insight.talent.TalentPayloads;
import com.query.insight.talent.TalentSubmission.Type;
import com.query.insight.talent.TalentSubmissionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(TalentAttachmentServiceIntegrationTests.ScanConfiguration.class)
class TalentAttachmentControllerIntegrationTests {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TalentSubmissionRepository submissions;

    @Test
    void ownerUploadsAndDownloadsCleanPdfWithSafeHeaders() throws Exception {
        var draft = createDraft();
        byte[] pdf = "%PDF-1.4\n%%EOF".getBytes();
        var file = new MockMultipartFile("file", "evidence.pdf", "application/pdf", pdf);

        String response = mvc.perform(multipart("/api/v1/talent-submissions/{id}/attachments", draft.publicId())
                        .file(file).param("version", "0").with(employeeJwt("QITEST", "GENERAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanStatus").value("CLEAN"))
                .andExpect(jsonPath("$.submissionVersion").value(1))
                .andReturn().getResponse().getContentAsString();
        String attachmentId = new tools.jackson.databind.ObjectMapper().readTree(response).path("publicId").asText();

        mvc.perform(get("/api/v1/talent-attachments/{id}", attachmentId)
                        .with(employeeJwt("QITEST", "GENERAL")))
                .andExpect(status().isOk())
                .andExpect(content().bytes(pdf))
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"evidence.pdf\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void rejectsUnauthenticatedUploadAndAdministratorDownload() throws Exception {
        var draft = createDraft();
        byte[] pdf = "%PDF-1.4\n%%EOF".getBytes();
        var file = new MockMultipartFile("file", "evidence.pdf", "application/pdf", pdf);
        mvc.perform(multipart("/api/v1/talent-submissions/{id}/attachments", draft.publicId())
                        .file(file).param("version", "0"))
                .andExpect(status().isUnauthorized());

        String attachmentId = mvc.perform(multipart("/api/v1/talent-submissions/{id}/attachments", draft.publicId())
                        .file(file).param("version", "0").with(employeeJwt("QITEST", "GENERAL")))
                .andReturn().getResponse().getContentAsString();
        attachmentId = new tools.jackson.databind.ObjectMapper().readTree(attachmentId).path("publicId").asText();
        mvc.perform(get("/api/v1/talent-attachments/{id}", attachmentId)
                        .with(employeeJwt("QI0001", "ADMIN")))
                .andExpect(status().isNotFound());
    }

    private TalentSubmissionRepository.Row createDraft() {
        long employeeId = jdbc.sql("SELECT id FROM employees WHERE employee_no='QITEST'")
                .query(Long.class).single();
        return submissions.createDraft(employeeId, Type.SKILL,
                new TalentPayloads.SkillPayload("01J00000000000000000000001", 3, BigDecimal.ONE,
                        LocalDate.of(2026, 8, 1), "担当実績"), null,
                Instant.parse("2026-08-12T00:00:00Z"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor employeeJwt(
            String employeeNo, String role) {
        String employeePublicId = jdbc.sql("SELECT public_id FROM employees WHERE employee_no=:employeeNo")
                .param("employeeNo", employeeNo).query(String.class).single();
        String accountPublicId = jdbc.sql("SELECT a.public_id FROM accounts a JOIN employees e ON e.id=a.employee_id "
                        + "WHERE e.employee_no=:employeeNo")
                .param("employeeNo", employeeNo).query(String.class).single();
        return jwt().jwt(token -> token.claim("accountPublicId", accountPublicId)
                        .claim("employeePublicId", employeePublicId).claim("roles", List.of(role))
                        .claim("scopes", List.of("ADMIN".equals(role) ? "ALL" : "SELF")))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
