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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
        scanner.scanCalls.set(0);
        scanner.entered.set(null);
        scanner.release.set(null);
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

    @Test
    void replayReturnsPersistedErrorPendingAndInfectedAsNonCleanWithoutDuplicateOrRescan() {
        var draft = createDraft();
        scanner.result.set(FileScanClient.Result.ERROR);

        assertThatThrownBy(() -> service.upload(employeePublicId("QITEST"), draft.publicId(), 0,
                file("retry.pdf", "application/pdf", PDF)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status().value()).isEqualTo(503));
        String persistedPublicId = jdbc.sql("SELECT public_id FROM talent_attachments WHERE submission_id=:id")
                .param("id", draft.id()).query(String.class).single().trim();
        scanner.result.set(FileScanClient.Result.CLEAN);

        var replay = service.upload(employeePublicId("QITEST"), draft.publicId(), 0,
                file("retry.pdf", "application/pdf", PDF));

        assertThat(replay.publicId()).isEqualTo(persistedPublicId);
        assertThat(replay.scanStatus()).isEqualTo("ERROR");
        assertThat(replay.submissionVersion()).isZero();

        jdbc.sql("UPDATE talent_attachments SET scan_status='PENDING' WHERE submission_id=:id")
                .param("id", draft.id()).update();
        assertThat(service.upload(employeePublicId("QITEST"), draft.publicId(), 0,
                file("retry.pdf", "application/pdf", PDF)).scanStatus()).isEqualTo("PENDING");

        jdbc.sql("UPDATE talent_attachments SET scan_status='INFECTED',content=NULL WHERE submission_id=:id")
                .param("id", draft.id()).update();
        assertThat(service.upload(employeePublicId("QITEST"), draft.publicId(), 0,
                file("retry.pdf", "application/pdf", PDF)).scanStatus()).isEqualTo("INFECTED");
        assertThat(scanner.scanCalls).hasValue(1);
        assertThat(attachmentCount(draft.id())).isEqualTo(1);
    }

    @Test
    void cleanUploadReplayAfterVersionRefreshReturnsSameRowAndCurrentVersion() {
        var draft = createDraft();
        var first = service.upload(employeePublicId("QITEST"), draft.publicId(), 0,
                file("lost-response.pdf", "application/pdf", PDF));

        var replay = service.upload(employeePublicId("QITEST"), draft.publicId(), 1,
                file("lost-response.pdf", "application/pdf", PDF));

        assertThat(replay.publicId()).isEqualTo(first.publicId());
        assertThat(replay.scanStatus()).isEqualTo("CLEAN");
        assertThat(replay.submissionVersion()).isEqualTo(1);
        assertThat(scanner.scanCalls).hasValue(1);
        assertThat(attachmentCount(draft.id())).isEqualTo(1);
    }

    @Test
    void duplicateReplayStillRequiresOwnerEditableSubmissionAndCurrentVersion() {
        var draft = createDraft();
        service.upload(employeePublicId("QITEST"), draft.publicId(), 0,
                file("private.pdf", "application/pdf", PDF));

        assertNotFound(() -> service.upload(employeePublicId("QI0003"), draft.publicId(), 1,
                file("private.pdf", "application/pdf", PDF)));
        assertThatThrownBy(() -> service.upload(employeePublicId("QITEST"), draft.publicId(), 0,
                file("private.pdf", "application/pdf", PDF)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status().value()).isEqualTo(409));
        assertThatThrownBy(() -> service.upload(employeePublicId("QITEST"), draft.publicId(), 0,
                file("different.pdf", "application/pdf", pdf(99))))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status().value()).isEqualTo(409));

        var otherSubmission = createDraft();
        var otherUpload = service.upload(employeePublicId("QITEST"), otherSubmission.publicId(), 0,
                file("private.pdf", "application/pdf", PDF));
        assertThat(otherUpload.publicId()).isNotEqualTo(
                jdbc.sql("SELECT public_id FROM talent_attachments WHERE submission_id=:id")
                        .param("id", draft.id()).query(String.class).single().trim());
        assertThat(attachmentCount(draft.id())).isEqualTo(1);
        assertThat(attachmentCount(otherSubmission.id())).isEqualTo(1);
    }

    @Test
    void concurrentSameUploadReturnsOneAttachmentAndDatabaseCurrentVersion() throws Exception {
        var draft = createDraft();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        scanner.entered.set(entered);
        scanner.release.set(release);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<TalentAttachmentService.Upload> first = executor.submit(() -> service.upload(
                    employeePublicId("QITEST"), draft.publicId(), 0,
                    file("concurrent.pdf", "application/pdf", PDF)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<TalentAttachmentService.Upload> second = executor.submit(() -> service.upload(
                    employeePublicId("QITEST"), draft.publicId(), 0,
                    file("concurrent.pdf", "application/pdf", PDF)));
            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();

            List<TalentAttachmentService.Upload> uploads = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(uploads).extracting(TalentAttachmentService.Upload::publicId)
                    .containsOnly(uploads.get(0).publicId());
            assertThat(uploads).extracting(TalentAttachmentService.Upload::submissionVersion)
                    .containsOnly(1L);
            assertThat(scanner.scanCalls).hasValue(1);
            assertThat(attachmentCount(draft.id())).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
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

    private int attachmentCount(long submissionId) {
        return jdbc.sql("SELECT COUNT(*) FROM talent_attachments WHERE submission_id=:id")
                .param("id", submissionId).query(Integer.class).single();
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
        private final AtomicInteger scanCalls = new AtomicInteger();
        private final AtomicReference<CountDownLatch> entered = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> release = new AtomicReference<>();

        @Override
        public Result scan(byte[] content) {
            scanCalls.incrementAndGet();
            CountDownLatch enteredLatch = entered.getAndSet(null);
            if (enteredLatch != null) enteredLatch.countDown();
            CountDownLatch releaseLatch = release.getAndSet(null);
            if (releaseLatch != null) {
                try {
                    if (!releaseLatch.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("scanner release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("scanner interrupted", exception);
                }
            }
            return result.get();
        }

        void returnResult(Result next) {
            result.set(next);
        }
    }
}
