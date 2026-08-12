package com.query.insight.talent.attachment;

import com.query.insight.common.ApiException;
import com.query.insight.common.PublicIdGenerator;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TalentAttachmentService {
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final int MAX_FILES = 3;
    private static final byte[] PDF = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    private final JdbcClient jdbc;
    private final FileScanClient scanner;
    private final TransactionTemplate transactions;

    public TalentAttachmentService(JdbcClient jdbc, FileScanClient scanner,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.scanner = scanner;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Upload upload(String employeePublicId, String submissionPublicId, long submissionVersion,
            MultipartFile file) {
        UploadOutcome outcome = transactions.execute(status -> uploadInTransaction(
                employeePublicId, submissionPublicId, submissionVersion, file));
        if (outcome == null) throw new IllegalStateException("Attachment transaction returned no result");
        if (outcome.error() != null) throw outcome.error();
        return outcome.upload();
    }

    private UploadOutcome uploadInTransaction(String employeePublicId, String submissionPublicId,
            long submissionVersion, MultipartFile file) {
        Submission submission = editableSubmission(employeePublicId, submissionPublicId, submissionVersion);
        if (attachmentCount(submission.id()) >= MAX_FILES) {
            throw badRequest("ATTACHMENT_LIMIT", "添付は1申請につき3ファイルまでです");
        }
        byte[] content = content(file);
        String contentType = validateContent(file, content);
        String publicId = PublicIdGenerator.next();
        String fileName = safeFileName(file.getOriginalFilename(), contentType);
        String sha256 = sha256(content);
        Instant now = Instant.now();
        try {
            jdbc.sql("""
                    INSERT INTO talent_attachments(public_id,submission_id,file_name,content_type,size_bytes,sha256,
                      scan_status,content,scan_attempts,created_at)
                    VALUES (:publicId,:submissionId,:fileName,:contentType,:sizeBytes,:sha256,
                      'PENDING',:content,0,:now)
                    """)
                    .param("publicId", publicId)
                    .param("submissionId", submission.id())
                    .param("fileName", fileName)
                    .param("contentType", contentType)
                    .param("sizeBytes", content.length)
                    .param("sha256", sha256)
                    .param("content", content)
                    .param("now", Timestamp.from(now))
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_DUPLICATE", "同じファイルが既に添付されています");
        }
        FileScanClient.Result result = scanner.scan(content);
        if (result == FileScanClient.Result.CLEAN) {
            jdbc.sql("""
                    UPDATE talent_attachments SET scan_status='CLEAN',scan_attempts=1,scanned_at=:now
                    WHERE public_id=:publicId
                    """).param("now", Timestamp.from(now)).param("publicId", publicId).update();
        } else if (result == FileScanClient.Result.INFECTED) {
            jdbc.sql("""
                    UPDATE talent_attachments SET scan_status='INFECTED',content=NULL,scan_attempts=1,
                      scanned_at=:now WHERE public_id=:publicId
                    """).param("now", Timestamp.from(now)).param("publicId", publicId).update();
            return new UploadOutcome(null,
                    badRequest("ATTACHMENT_INFECTED", "安全でないファイルを検出しました"));
        } else {
            jdbc.sql("""
                    UPDATE talent_attachments SET scan_status='ERROR',scan_attempts=1,
                      last_scan_error_code='SCANNER_UNAVAILABLE',next_scan_at=:nextScanAt
                    WHERE public_id=:publicId
                    """).param("nextScanAt", Timestamp.from(now.plusSeconds(60))).param("publicId", publicId).update();
            return new UploadOutcome(null,
                    new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ATTACHMENT_SCAN_UNAVAILABLE",
                            "ファイル検査を完了できませんでした。時間をおいて再試行します"));
        }
        long nextVersion = incrementSubmissionVersion(submission, submissionVersion, now);
        return new UploadOutcome(new Upload(publicId, fileName, contentType, content.length, result.name(), nextVersion),
                null);
    }

    public Download download(String accountPublicId, String employeePublicId, Set<String> roles,
            String attachmentPublicId) {
        Attachment attachment = jdbc.sql("""
                SELECT ta.file_name,ta.content_type,ta.content,ta.scan_status,s.employee_id,
                  owner.public_id owner_public_id,owner.manager_employee_id
                FROM talent_attachments ta
                JOIN talent_submissions s ON s.id=ta.submission_id
                JOIN employees owner ON owner.id=s.employee_id
                WHERE ta.public_id=:publicId
                """).param("publicId", attachmentPublicId)
                .query((rs, row) -> new Attachment(rs.getString("file_name"), rs.getString("content_type"),
                        rs.getBytes("content"), rs.getString("scan_status"), rs.getLong("employee_id"),
                        rs.getString("owner_public_id").trim(), (Number) rs.getObject("manager_employee_id")))
                .optional().orElseThrow(TalentAttachmentService::notFound);
        if (!"CLEAN".equals(attachment.scanStatus()) || attachment.content() == null) throw notFound();
        boolean owner = attachment.ownerPublicId().equals(employeePublicId);
        boolean currentManager = roles.contains("MANAGER") && attachment.managerEmployeeId() != null
                && attachment.managerEmployeeId().longValue() == employeeId(employeePublicId);
        boolean executiveAll = roles.contains("EXECUTIVE") && hasExecutiveAllGrant(accountPublicId);
        if (!owner && !currentManager && !executiveAll) throw notFound();
        return new Download(attachment.fileName(), attachment.contentType(), attachment.content());
    }

    @Transactional
    public long delete(String employeePublicId, String attachmentPublicId, long submissionVersion) {
        AttachmentOwner owner = jdbc.sql("""
                SELECT ta.id attachment_id,s.id submission_id,s.status,s.version
                FROM talent_attachments ta JOIN talent_submissions s ON s.id=ta.submission_id
                JOIN employees e ON e.id=s.employee_id
                WHERE ta.public_id=:attachmentPublicId AND e.public_id=:employeePublicId
                """).param("attachmentPublicId", attachmentPublicId)
                .param("employeePublicId", employeePublicId)
                .query((rs, row) -> new AttachmentOwner(rs.getLong("attachment_id"),
                        rs.getLong("submission_id"), rs.getString("status"), rs.getLong("version")))
                .optional().orElseThrow(TalentAttachmentService::notFound);
        if (!Set.of("DRAFT", "RETURNED").contains(owner.status()) || owner.version() != submissionVersion) {
            throw conflict();
        }
        jdbc.sql("DELETE FROM talent_attachments WHERE id=:id").param("id", owner.attachmentId()).update();
        int updated = jdbc.sql("""
                UPDATE talent_submissions SET version=version+1,updated_at=:now
                WHERE id=:id AND version=:version AND status IN ('DRAFT','RETURNED')
                """).param("now", Timestamp.from(Instant.now())).param("id", owner.submissionId())
                .param("version", submissionVersion).update();
        if (updated != 1) throw conflict();
        return submissionVersion + 1;
    }

    @Transactional
    public int retryPendingScans(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        List<RetryAttachment> due = jdbc.sql("""
                SELECT id,content,scan_attempts FROM talent_attachments
                WHERE scan_status IN ('PENDING','ERROR') AND content IS NOT NULL
                  AND (next_scan_at IS NULL OR next_scan_at<=CURRENT_TIMESTAMP)
                ORDER BY next_scan_at,id LIMIT :limit FOR UPDATE SKIP LOCKED
                """).param("limit", limit)
                .query((rs, row) -> new RetryAttachment(rs.getLong("id"), rs.getBytes("content"),
                        rs.getInt("scan_attempts"))).list();
        Instant now = Instant.now();
        for (RetryAttachment attachment : due) {
            FileScanClient.Result result = scanner.scan(attachment.content());
            int attempts = attachment.attempts() + 1;
            if (result == FileScanClient.Result.CLEAN) {
                jdbc.sql("""
                        UPDATE talent_attachments SET scan_status='CLEAN',scan_attempts=:attempts,
                          last_scan_error_code=NULL,next_scan_at=NULL,scanned_at=:now WHERE id=:id
                        """).param("attempts", attempts).param("now", Timestamp.from(now))
                        .param("id", attachment.id()).update();
            } else if (result == FileScanClient.Result.INFECTED) {
                jdbc.sql("""
                        UPDATE talent_attachments SET scan_status='INFECTED',content=NULL,scan_attempts=:attempts,
                          last_scan_error_code=NULL,next_scan_at=NULL,scanned_at=:now WHERE id=:id
                        """).param("attempts", attempts).param("now", Timestamp.from(now))
                        .param("id", attachment.id()).update();
            } else {
                jdbc.sql("""
                        UPDATE talent_attachments SET scan_status='ERROR',scan_attempts=:attempts,
                          last_scan_error_code='SCANNER_UNAVAILABLE',next_scan_at=:next WHERE id=:id
                        """).param("attempts", attempts).param("next", Timestamp.from(now.plusSeconds(backoff(attempts))))
                        .param("id", attachment.id()).update();
            }
        }
        return due.size();
    }

    private Submission editableSubmission(String employeePublicId, String publicId, long version) {
        Submission submission = jdbc.sql("""
                SELECT s.id,s.status,s.version
                FROM talent_submissions s JOIN employees e ON e.id=s.employee_id
                WHERE s.public_id=:publicId AND e.public_id=:employeePublicId
                """).param("publicId", publicId).param("employeePublicId", employeePublicId)
                .query((rs, row) -> new Submission(rs.getLong("id"), rs.getString("status"), rs.getLong("version")))
                .optional().orElseThrow(TalentAttachmentService::notFound);
        if (!Set.of("DRAFT", "RETURNED").contains(submission.status()) || submission.version() != version) {
            throw conflict();
        }
        return submission;
    }

    private int attachmentCount(long submissionId) {
        return jdbc.sql("SELECT COUNT(*) FROM talent_attachments WHERE submission_id=:id")
                .param("id", submissionId).query(Integer.class).single();
    }

    private byte[] content(MultipartFile file) {
        if (file == null || file.isEmpty()) throw badRequest("ATTACHMENT_EMPTY", "ファイルを選択してください");
        if (file.getSize() > MAX_FILE_SIZE) throw badRequest("ATTACHMENT_TOO_LARGE", "ファイルは5MB以内にしてください");
        try {
            byte[] content = file.getBytes();
            if (content.length == 0 || content.length > MAX_FILE_SIZE) {
                throw badRequest("ATTACHMENT_TOO_LARGE", "ファイルは1バイト以上5MB以内にしてください");
            }
            return content;
        } catch (IOException exception) {
            throw badRequest("ATTACHMENT_READ_FAILED", "ファイルを読み取れませんでした");
        }
    }

    private String validateContent(MultipartFile file, byte[] content) {
        String supplied = file.getContentType();
        String detected = startsWith(content, PDF) ? "application/pdf"
                : startsWith(content, JPEG) ? "image/jpeg"
                : startsWith(content, PNG) ? "image/png" : null;
        if (detected == null || !detected.equals(supplied)) {
            throw badRequest("ATTACHMENT_CONTENT_INVALID", "対応形式はPDF、JPEG、PNGのみです");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        boolean extensionMatches = detected.equals("application/pdf") && name.endsWith(".pdf")
                || detected.equals("image/jpeg") && (name.endsWith(".jpg") || name.endsWith(".jpeg"))
                || detected.equals("image/png") && name.endsWith(".png");
        if (!extensionMatches) {
            throw badRequest("ATTACHMENT_CONTENT_INVALID", "拡張子とファイル内容が一致しません");
        }
        return detected;
    }

    private String safeFileName(String original, String contentType) {
        String extension = contentType.equals("application/pdf") ? ".pdf"
                : contentType.equals("image/jpeg") ? ".jpg" : ".png";
        String base = original == null ? "attachment" : original.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[^0-9A-Za-z._-]", "_");
        if (base.isBlank() || base.equals(".") || base.equals("..")) base = "attachment" + extension;
        return base.length() <= 255 ? base : base.substring(0, 255 - extension.length()) + extension;
    }

    private long incrementSubmissionVersion(Submission submission, long version, Instant now) {
        int updated = jdbc.sql("""
                UPDATE talent_submissions SET version=version+1,updated_at=:now
                WHERE id=:id AND version=:version AND status IN ('DRAFT','RETURNED')
                """).param("now", Timestamp.from(now)).param("id", submission.id()).param("version", version).update();
        if (updated != 1) throw conflict();
        return version + 1;
    }

    private long employeeId(String publicId) {
        return jdbc.sql("SELECT id FROM employees WHERE public_id=:publicId")
                .param("publicId", publicId).query(Long.class).optional().orElse(-1L);
    }

    private boolean hasExecutiveAllGrant(String accountPublicId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM permission_grants g
                JOIN accounts a ON a.id=g.account_id JOIN roles r ON r.id=g.role_id
                WHERE a.public_id=:accountPublicId AND a.status='ACTIVE' AND r.code='EXECUTIVE'
                  AND r.status='ACTIVE' AND g.scope_type='ALL' AND g.revoked_at IS NULL
                  AND g.valid_from<=CURRENT_TIMESTAMP AND (g.valid_to IS NULL OR g.valid_to>CURRENT_TIMESTAMP)
                """).param("accountPublicId", accountPublicId).query(Integer.class).single() > 0;
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        return content.length >= signature.length
                && Arrays.equals(Arrays.copyOf(content, signature.length), signature);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT",
                "他の利用者が更新しました。再読み込みしてください");
    }

    private static long backoff(int attempts) {
        return attempts <= 1 ? 60 : attempts == 2 ? 300 : attempts == 3 ? 900 : 3600;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "対象のファイルが見つかりません");
    }

    public record Upload(String publicId, String fileName, String contentType, long sizeBytes,
            String scanStatus, long submissionVersion) {
    }

    private record UploadOutcome(Upload upload, ApiException error) {
    }

    public record Download(String fileName, String contentType, byte[] content) {
    }

    private record Submission(long id, String status, long version) {
    }

    private record Attachment(String fileName, String contentType, byte[] content, String scanStatus,
            long employeeId, String ownerPublicId, Number managerEmployeeId) {
    }

    private record AttachmentOwner(long attachmentId, long submissionId, String status, long version) {
    }

    private record RetryAttachment(long id, byte[] content, int attempts) {
    }
}
