package com.query.insight.talent.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.query.insight.common.ApiException;
import com.query.insight.talent.TalentPayloads;
import com.query.insight.talent.TalentSubmission.Type;
import com.query.insight.talent.TalentSubmissionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class TalentAttachmentServiceIntegrationTests {
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes();

    @Autowired
    private TalentAttachmentService service;
    @Autowired
    private TalentSubmissionRepository submissions;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private FakeFileScanClient scanner;

    @BeforeEach
    void cleanScanner() {
        scanner.result.set(FileScanClient.Result.CLEAN);
    }

    @Test
    void employeeCurrentManagerAndExecutiveAllCanDownloadCleanFileButAdministratorCannot() {
        var draft = createDraft();
        var uploaded = service.upload(employeePublicId("QITEST"), draft.publicId(), 0,
                file("evidence.pdf", "application/pdf", PDF));

        assertThat(uploaded.scanStatus()).isEqualTo("CLEAN");
        assertThat(uploaded.submissionVersion()).isEqualTo(1);
        assertThat(service.download(accountPublicId("QITEST"), employeePublicId("QITEST"),
                Set.of("GENERAL"), uploaded.publicId()).content()).isEqualTo(PDF);
        assertThat(service.download(accountPublicId("QI0002"), employeePublicId("QI0002"),
                Set.of("OFFICER"), uploaded.publicId()).content()).isEqualTo(PDF);
        assertThat(service.download(accountPublicId("QI0039"), employeePublicId("QI0039"),
                Set.of("OFFICER"), uploaded.publicId()).content()).isEqualTo(PDF);
        assertNotFound(() -> service.download(accountPublicId("QI0001"), employeePublicId("QI0001"),
                Set.of("ADMIN"), uploaded.publicId()));
    }

    @Test
    void rejectsFourthAttachmentAndContentTypeSignatureMismatch() {
        var draft = createDraft();
        long version = 0;
        for (int index = 1; index <= 3; index++) {
            version = service.upload(employeePublicId("QITEST"), draft.publicId(), version,
                    file("evidence-" + index + ".pdf", "application/pdf", pdf(index))).submissionVersion();
        }
        long currentVersion = version;

        assertThatThrownBy(() -> service.upload(employeePublicId("QITEST"), draft.publicId(), currentVersion,
                file("fourth.pdf", "application/pdf", PDF)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(400);
                    assertThat(exception.code()).isEqualTo("ATTACHMENT_LIMIT");
                });
        assertThatThrownBy(() -> service.upload(employeePublicId("QITEST"), createDraft().publicId(), 0,
                file("fake.pdf", "application/pdf", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01})))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ATTACHMENT_CONTENT_INVALID"));
    }

    @Test
    void keepsScannerErrorPrivateAndErasesInfectedContent() {
        var errorDraft = createDraft();
        scanner.result.set(FileScanClient.Result.ERROR);
        assertThatThrownBy(() -> service.upload(employeePublicId("QITEST"), errorDraft.publicId(), 0,
                file("error.pdf", "application/pdf", PDF)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status().value()).isEqualTo(503));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM talent_attachments WHERE submission_id=:id AND scan_status='ERROR'")
                .param("id", errorDraft.id()).query(Integer.class).single()).isEqualTo(1);

        var infectedDraft = createDraft();
        scanner.result.set(FileScanClient.Result.INFECTED);
        assertThatThrownBy(() -> service.upload(employeePublicId("QITEST"), infectedDraft.publicId(), 0,
                file("infected.pdf", "application/pdf", PDF)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ATTACHMENT_INFECTED"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM talent_attachments WHERE submission_id=:id "
                        + "AND scan_status='INFECTED' AND content IS NULL")
                .param("id", infectedDraft.id()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void ownerDeletesOnlyEditableAttachmentWithMatchingVersion() {
        var draft = createDraft();
        var uploaded = service.upload(employeePublicId("QITEST"), draft.publicId(), 0,
                file("delete.pdf", "application/pdf", PDF));

        assertNotFound(() -> service.delete(employeePublicId("QI0003"), uploaded.publicId(), 1));
        assertThatThrownBy(() -> service.delete(employeePublicId("QITEST"), uploaded.publicId(), 0))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status().value()).isEqualTo(409));

        long nextVersion = service.delete(employeePublicId("QITEST"), uploaded.publicId(), 1);
        assertThat(nextVersion).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM talent_attachments WHERE public_id=:publicId")
                .param("publicId", uploaded.publicId()).query(Integer.class).single()).isZero();
    }

    @Test
    void retriesPrivateScannerErrorUntilClean() {
        var draft = createDraft();
        scanner.result.set(FileScanClient.Result.ERROR);
        assertThatThrownBy(() -> service.upload(employeePublicId("QITEST"), draft.publicId(), 0,
                file("retry.pdf", "application/pdf", PDF))).isInstanceOf(ApiException.class);
        jdbc.sql("UPDATE talent_attachments SET next_scan_at=CURRENT_TIMESTAMP WHERE submission_id=:id")
                .param("id", draft.id()).update();
        scanner.result.set(FileScanClient.Result.CLEAN);

        assertThat(service.retryPendingScans(10)).isEqualTo(1);
        String attachmentId = jdbc.sql("SELECT public_id FROM talent_attachments WHERE submission_id=:id")
                .param("id", draft.id()).query(String.class).single();
        assertThat(service.download(accountPublicId("QITEST"), employeePublicId("QITEST"),
                Set.of("GENERAL"), attachmentId).content()).isEqualTo(PDF);
    }

    private TalentSubmissionRepository.Row createDraft() {
        long employeeId = jdbc.sql("SELECT id FROM employees WHERE employee_no='QITEST'")
                .query(Long.class).single();
        return submissions.createDraft(employeeId, Type.SKILL,
                new TalentPayloads.SkillPayload("01J00000000000000000000001", 3, BigDecimal.ONE,
                        LocalDate.of(2026, 8, 1), "担当実績"),
                null, Instant.parse("2026-08-12T00:00:00Z"));
    }

    private MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    private byte[] pdf(int discriminator) {
        return ("%PDF-1.4\n%" + discriminator + "\n1 0 obj\n<<>>\nendobj\n%%EOF").getBytes();
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

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.status().value()).isEqualTo(404));
    }

    @TestConfiguration
    static class ScanConfiguration {
        @Bean
        @Primary
        FakeFileScanClient fakeFileScanClient() {
            return new FakeFileScanClient();
        }
    }

    static class FakeFileScanClient implements FileScanClient {
        private final AtomicReference<Result> result = new AtomicReference<>(Result.CLEAN);

        @Override
        public Result scan(byte[] content) {
            return result.get();
        }
    }
}
