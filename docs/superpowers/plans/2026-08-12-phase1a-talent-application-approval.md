# 第1段階A タレント申請・承認 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 社員がスキル・専門知識・資格・業務経歴を1件ずつ申請し、必要に応じて根拠ファイルを添付でき、現在の直属上長が承認・差戻しでき、承認済み版だけが正式プロフィールとして利用される状態を作る。

**Architecture:** 既存の正式データテーブルを検索・AI連携の読み取りモデルとして維持し、新しい `talent_submissions` を申請・版・状態遷移の正本とする。承認時だけ同一トランザクションで正式テーブルへ反映し、添付、通知、業務履歴、監査を確定する。第1段階全体は独立性の高いA（本計画）、B（社員・組織・アカウント・マスタライフサイクル管理）、C（Excel初回移行）に分け、本計画ではAだけを実装する。

**Tech Stack:** Java 21、Spring Boot 4.1、Spring JDBC、Flyway、PostgreSQL 18.4、React 19、TypeScript 6、TanStack Query、React Hook Form、Zod、Vitest、Docker Compose、ClamAV

## Global Constraints

- 現在のダークネイビー・ブラック、黄色の主要操作、カード・表中心のデザインを維持する。
- タレント情報はスキル、専門知識、資格、業務経歴の4種類とし、習熟度は1～5とする。
- 社員が申請し、現在の直属上長が1件ずつ承認または理由付きで差戻す。上長による直接修正と一括承認は禁止する。
- 承認済み版を変更する際は旧版を正式データとして残し、新版承認後にだけ正式版を切り替える。
- 添付はPDF・JPEG・PNG、1ファイル5MB、1申請3ファイルまでとする。
- 添付は本人、現在の直属上長、`EXECUTIVE`のうち`ALL`スコープを持つ社長だけが閲覧できる。システム管理者、監査担当者、旧上長には返さない。
- アップロード直後は非公開とし、マルウェア検査成功後だけ閲覧可能にする。検査不能時は公開しない。
- ロールと対象データのスコープはAPI側で別々に検証し、割当外は404、ロール不足は403とする。
- 更新APIはversionを必須とし、競合、不正遷移、二重操作は409とする。
- 状態更新、正式データ反映、通知、業務履歴、監査は同一トランザクションで確定する。
- 新しいフロントエンド依存は追加しない。バックエンド依存追加は本計画で明記したもの以外を禁止する。
- `.superpowers/` はユーザー所有の未追跡ファイルとして変更・削除・コミットしない。

---

## File Structure

### Backend

- `backend/src/main/resources/db/migration/V5__talent_submission_workflow.sql`: 申請、履歴、添付、マスタ追加申請と既存データ移行。
- `backend/src/main/java/com/query/insight/talent/TalentSubmission.java`: 種別・状態・操作の列挙と遷移規則。
- `backend/src/main/java/com/query/insight/talent/TalentSubmissionRepository.java`: 申請、版、正式テーブル反映に限定したSQL。
- `backend/src/main/java/com/query/insight/talent/TalentSubmissionService.java`: 本人申請、上長承認・差戻し、認可、通知・監査のトランザクション境界。
- `backend/src/main/java/com/query/insight/talent/TalentSubmissionController.java`: 本人向け申請API。
- `backend/src/main/java/com/query/insight/talent/ManagerTalentSubmissionController.java`: 現在の直属上長向けAPI。
- `backend/src/main/java/com/query/insight/talent/TalentPayloads.java`: 4種類の入力DTOと種類別バリデーション。
- `backend/src/main/java/com/query/insight/talent/attachment/TalentAttachmentService.java`: 添付保存、件数・形式・サイズ・閲覧認可。
- `backend/src/main/java/com/query/insight/talent/attachment/FileScanClient.java`: マルウェア検査境界。
- `backend/src/main/java/com/query/insight/talent/attachment/ClamAvFileScanClient.java`: ClamAV INSTREAM通信。
- `backend/src/main/java/com/query/insight/talent/attachment/TalentAttachmentScanScheduler.java`: 検査不能ファイルの非公開再試行。
- `backend/src/main/java/com/query/insight/talent/attachment/TalentAttachmentController.java`: multipartアップロードと認可済みダウンロード。
- `backend/src/main/java/com/query/insight/master/MasterRequestService.java`: スキル・資格マスタ追加申請と承認。
- `backend/src/main/java/com/query/insight/master/MasterRequestController.java`: 社員申請APIと社長・管理者審査API。
- `backend/src/main/java/com/query/insight/talent/TalentProfileService.java`: 承認済み正式版だけを返し、本人には申請状況・履歴も返す。
- `backend/src/main/java/com/query/insight/notification/NotificationController.java`: 通知リンクを開く操作と同時に個別既読化できる既存APIを使用。
- `backend/src/main/java/com/query/insight/config/LocalRealisticDataInitializer.java`: 申請待ち・差戻し・承認済みサンプル。
- `backend/src/main/resources/application.yml`: ClamAV接続先、接続・読取タイムアウト、ファイル上限。
- `backend/pom.xml`: multipartテストに既存Spring MVCを使い、新規Javaライブラリは追加しない。
- `compose.yml`: ローカルClamAVサービスとバックエンド依存関係。

### Frontend

- `frontend/src/types.ts`: 申請、版、添付、マスタ申請のAPI型。
- `frontend/src/pages/TalentProfilePage.tsx`: 本人の正式情報・申請状況・新規申請導線。
- `frontend/src/pages/TalentSubmissionFormPage.tsx`: 4種類の申請フォームと添付アップロード。
- `frontend/src/pages/TalentSubmissionHistoryPage.tsx`: 旧版、差戻し理由、状態履歴。
- `frontend/src/pages/ManagerTalentApprovalsPage.tsx`: 上長の承認待ち一覧。
- `frontend/src/pages/ManagerTalentApprovalDetailPage.tsx`: 差分、添付、承認・差戻し。
- `frontend/src/pages/MasterRequestsPage.tsx`: 社員向け追加申請と社長・管理者向け審査一覧。
- `frontend/src/pages/NotificationsPage.tsx`: 通知リンク選択時の自動既読。
- `frontend/src/App.tsx`: 申請・履歴・承認・マスタ申請ルート。
- `frontend/src/components/AppLayout.tsx`: ロール別ナビゲーション。
- `frontend/src/styles.css`: 既存トークンを使うフォーム、差分、添付、390px表示。

---

### Task 1: 申請・版・添付・マスタ申請スキーマ

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__talent_submission_workflow.sql`
- Create: `backend/src/test/java/com/query/insight/talent/TalentSubmissionSchemaIntegrationTests.java`

**Interfaces:**
- Produces: `talent_submissions`, `talent_submission_events`, `talent_attachments`, `master_addition_requests`。
- Produces statuses: `DRAFT`, `SUBMITTED`, `RETURNED`, `APPROVED`, `SUPERSEDED`。
- Produces attachment scan statuses: `PENDING`, `CLEAN`, `INFECTED`, `ERROR`。

- [ ] **Step 1: Write the failing Flyway schema test**

```java
@SpringBootTest
@ActiveProfiles("local")
class TalentSubmissionSchemaIntegrationTests {
    @Autowired JdbcClient jdbc;

    @Test
    void createsWorkflowTablesAndMigratesExistingProfilesAsApproved() {
        assertThat(tableCount("talent_submissions")).isPositive();
        assertThat(tableCount("talent_submission_events")).isZero();
        assertThat(tableCount("talent_attachments")).isZero();
        assertThat(tableCount("master_addition_requests")).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM talent_submissions WHERE status='APPROVED'")
                .query(Integer.class).single()).isPositive();
    }

    private int tableCount(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }
}
```

- [ ] **Step 2: Run the test and verify the migration is missing**

Run: `cd backend; .\mvnw.cmd "-Dtest=TalentSubmissionSchemaIntegrationTests" test`

Expected: FAIL because `talent_submissions` does not exist.

- [ ] **Step 3: Add the migration**

Create tables with these required columns and constraints:

```sql
CREATE TABLE talent_submissions (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  employee_id BIGINT NOT NULL,
  talent_type VARCHAR(20) NOT NULL,
  logical_public_id CHAR(26) NOT NULL,
  revision_no INTEGER NOT NULL,
  status VARCHAR(20) NOT NULL,
  payload_json JSONB NOT NULL,
  base_record_version BIGINT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  submitted_at TIMESTAMP(6) NULL,
  decided_at TIMESTAMP(6) NULL,
  reviewer_account_id BIGINT NULL,
  return_reason VARCHAR(1000) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT uq_talent_submission_revision UNIQUE(employee_id,talent_type,logical_public_id,revision_no),
  CONSTRAINT chk_talent_type CHECK(talent_type IN ('SKILL','KNOWLEDGE','CAREER','CERTIFICATION')),
  CONSTRAINT chk_talent_submission_status CHECK(status IN ('DRAFT','SUBMITTED','RETURNED','APPROVED','SUPERSEDED')),
  CONSTRAINT fk_talent_submission_employee FOREIGN KEY(employee_id) REFERENCES employees(id),
  CONSTRAINT fk_talent_submission_reviewer FOREIGN KEY(reviewer_account_id) REFERENCES accounts(id)
);
```

`talent_submission_events`は実行者、action、from_status、to_status、reason、occurred_at、trace_idを追記専用で持つ。`talent_attachments`は申請ID、無害化表示名、content_type、size_bytes、sha256、scan_status、nullableな`BYTEA content`、scan_attempts、last_scan_error_code、next_scan_atを持ち、1申請内sha256を一意にする。`master_addition_requests`は`SKILL`または`CERTIFICATION`、提案値、状態、version、申請・判断日時、申請者・判断者を持つ。

既存の4正式テーブルを`APPROVED`申請へ移す。`logical_public_id`には既存レコードの`public_id`、`revision_no=1`、`payload_json`には既存列を保存し、同一`logical_public_id`が既に移行済みなら追加しない。V5は既存DBと空DBの両方で一度だけ安全に適用できるSQLにする。

- [ ] **Step 4: Run schema and full backend tests**

Run: `cd backend; .\mvnw.cmd "-Dtest=TalentSubmissionSchemaIntegrationTests" test`

Expected: PASS.

Run: `cd backend; .\mvnw.cmd test`

Expected: all existing tests PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/resources/db/migration/V5__talent_submission_workflow.sql backend/src/test/java/com/query/insight/talent/TalentSubmissionSchemaIntegrationTests.java
git commit -m "タレント申請と版管理のDB基盤を追加"
```

### Task 2: 状態遷移・種類別入力・永続化

**Files:**
- Create: `backend/src/main/java/com/query/insight/talent/TalentSubmission.java`
- Create: `backend/src/main/java/com/query/insight/talent/TalentPayloads.java`
- Create: `backend/src/main/java/com/query/insight/talent/TalentSubmissionRepository.java`
- Create: `backend/src/test/java/com/query/insight/talent/TalentSubmissionTests.java`
- Create: `backend/src/test/java/com/query/insight/talent/TalentPayloadsTests.java`

**Interfaces:**
- Produces: `TalentSubmission.Type`, `Status`, `Action`, `requireTransition(Status, Action)`。
- Produces: `SkillPayload`, `KnowledgePayload`, `CareerPayload`, `CertificationPayload` and `validate()` methods。
- Produces repository methods: `createDraft`, `updateDraft`, `findByPublicId`, `appendEvent`, `markSubmitted`, `markReturned`, `markApproved`, `applyApprovedPayload`。

- [ ] **Step 1: Write failing transition tests**

```java
@Test
void onlyAllowsEmployeeSubmitAndManagerDecisionFlow() {
    assertThat(requireTransition(DRAFT, SUBMIT)).isEqualTo(SUBMITTED);
    assertThat(requireTransition(RETURNED, SUBMIT)).isEqualTo(SUBMITTED);
    assertThat(requireTransition(SUBMITTED, APPROVE)).isEqualTo(APPROVED);
    assertThat(requireTransition(SUBMITTED, RETURN)).isEqualTo(RETURNED);
    assertThatThrownBy(() -> requireTransition(APPROVED, APPROVE))
            .isInstanceOf(ApiException.class);
}
```

- [ ] **Step 2: Run and confirm the missing domain types**

Run: `cd backend; .\mvnw.cmd "-Dtest=TalentSubmissionTests,TalentPayloadsTests" test`

Expected: test compilation FAIL because the new types do not exist.

- [ ] **Step 3: Implement transition and validation contracts**

```java
public final class TalentSubmission {
    public enum Type { SKILL, KNOWLEDGE, CAREER, CERTIFICATION }
    public enum Status { DRAFT, SUBMITTED, RETURNED, APPROVED, SUPERSEDED }
    public enum Action { SAVE, SUBMIT, APPROVE, RETURN }

    public static Status requireTransition(Status current, Action action) {
        return switch (action) {
            case SAVE when current == Status.DRAFT || current == Status.RETURNED -> current;
            case SUBMIT when current == Status.DRAFT || current == Status.RETURNED -> Status.SUBMITTED;
            case APPROVE when current == Status.SUBMITTED -> Status.APPROVED;
            case RETURN when current == Status.SUBMITTED -> Status.RETURNED;
            default -> throw new ApiException(HttpStatus.CONFLICT,
                    "TALENT_STATE_CONFLICT", "現在の状態では操作できません");
        };
    }
}
```

Payload rules:

- `SkillPayload(masterPublicId, level, yearsExperience, lastUsedOn)`: level 1～5、経験0～60年、最終利用日は未来不可。
- `KnowledgePayload(masterPublicId, level)`: level 1～5。
- `CareerPayload(projectName, industry, roleName, startDate, endDate, summary, achievements, technologies)`: 各既存DB上限、終了日≧開始日。
- `CertificationPayload(masterPublicId, acquiredOn, expiresOn, credentialReference)`: 取得日は未来不可、有効期限≧取得日。

RepositoryはJSON生成にSpring管理の`ObjectMapper`を使用し、SQL文字列へ値を連結しない。新規申請の`logical_public_id`はULID、既存正式データの修正は既存publicIdを使用する。同一社員・種類・logical IDのrevisionは直前版を`SELECT id FROM talent_submissions WHERE employee_id=:employeeId AND talent_type=:type AND logical_public_id=:logicalPublicId ORDER BY revision_no DESC LIMIT 1 FOR UPDATE`でロックした同一トランザクション内で`MAX(revision_no)+1`を採番し、なお発生した一意制約競合はトランザクションをロールバックして409へ変換する。

- [ ] **Step 4: Run focused tests**

Run: `cd backend; .\mvnw.cmd "-Dtest=TalentSubmissionTests,TalentPayloadsTests" test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/query/insight/talent/TalentSubmission.java backend/src/main/java/com/query/insight/talent/TalentPayloads.java backend/src/main/java/com/query/insight/talent/TalentSubmissionRepository.java backend/src/test/java/com/query/insight/talent/TalentSubmissionTests.java backend/src/test/java/com/query/insight/talent/TalentPayloadsTests.java
git commit -m "タレント申請の状態遷移と入力検証を実装"
```

### Task 3: 添付保存・マルウェア検査・閲覧認可

**Files:**
- Create: `backend/src/main/java/com/query/insight/talent/attachment/FileScanClient.java`
- Create: `backend/src/main/java/com/query/insight/talent/attachment/ClamAvFileScanClient.java`
- Create: `backend/src/main/java/com/query/insight/talent/attachment/TalentAttachmentService.java`
- Create: `backend/src/main/java/com/query/insight/talent/attachment/TalentAttachmentScanScheduler.java`
- Create: `backend/src/main/java/com/query/insight/talent/attachment/TalentAttachmentController.java`
- Create: `backend/src/test/java/com/query/insight/talent/attachment/TalentAttachmentServiceIntegrationTests.java`
- Create: `backend/src/test/java/com/query/insight/talent/attachment/TalentAttachmentControllerIntegrationTests.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application.yml`
- Modify: `compose.yml`

**Interfaces:**
- `FileScanClient.Result scan(byte[] content)` returns `CLEAN`, `INFECTED`, or `ERROR`.
- `TalentAttachmentService.upload(accountPublicId, employeePublicId, submissionPublicId, submissionVersion, MultipartFile, traceId)` returns attachment metadata and the incremented submission version.
- `TalentAttachmentService.download(accountPublicId, employeePublicId, roles, attachmentPublicId)` returns `Download(name, contentType, byte[])`。
- `TalentAttachmentService.delete(employeePublicId, attachmentPublicId, submissionVersion, traceId)` deletes only an owner's DRAFT/RETURNED attachment.
- `TalentAttachmentService.retryPendingScans()` retries private PENDING/ERROR rows whose `next_scan_at` is due.

- [ ] **Step 1: Write failing boundary and authorization tests**

Create named test methods for these exact cases and assert both HTTP status and persisted state:

- `rejectsFourthAttachment`: after three CLEAN files, the fourth returns 400 / `ATTACHMENT_LIMIT` and the attachment count stays three.
- `rejectsPdfNameContainingJpegBytes`: a `.pdf` whose magic bytes are JPEG returns 400 / `ATTACHMENT_CONTENT_INVALID` and stores no row.
- `keepsScannerErrorPrivateAndRetries`: scanner ERROR returns 503 / `ATTACHMENT_SCAN_UNAVAILABLE`; metadata and bytes remain private, download returns 404, and a due retry can move the row to CLEAN.
- `rejectsInfectedContent`: scanner INFECTED returns 400 / `ATTACHMENT_INFECTED`; metadata remains for audit but content is erased and download returns 404.
- `rejectsRequestLargerThanConfiguredMultipartLimit`: 5MB+1 byte is rejected before persistence and the response uses the project API error envelope.
- `formerManagerCannotDownloadAfterManagerChange`: after changing `manager_employee_id`, the former manager receives 404.
- `systemAdminCannotDownload`: SYSTEM_ADMIN without another allowed role receives 404.
- `employeeCurrentManagerAndExecutiveAllCanDownloadCleanFile`: each allowed actor receives 200, the original filename, media type, and exact bytes.
- `ownerDeletesOnlyEditableAttachmentWithMatchingVersion`: DRAFT/RETURNED plus current version deletes bytes and metadata; SUBMITTED or stale version returns 409.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `cd backend; .\mvnw.cmd "-Dtest=TalentAttachmentServiceIntegrationTests,TalentAttachmentControllerIntegrationTests" test`

Expected: test compilation FAIL.

- [ ] **Step 3: Implement scanner boundary and attachment service**

```java
public interface FileScanClient {
    enum Result { CLEAN, INFECTED, ERROR }
    Result scan(byte[] content);
}
```

`ClamAvFileScanClient`はTCPで`zINSTREAM\0`を送信し、32bit network-order長＋chunk、最後に長さ0を送る。`OK`だけをCLEAN、`FOUND`をINFECTED、タイムアウト・不明応答をERRORにする。ファイル全体は設定上限5MB以内でのみメモリへ読み、ログへファイル名・本文・ハッシュを出さない。

Magic bytes:

- PDF: `%PDF-`
- JPEG: `FF D8 FF`
- PNG: `89 50 4E 47 0D 0A 1A 0A`

保存順序は、件数確認→サイズ・形式検証→非公開PENDING行INSERT→スキャン→結果更新とする。CLEANだけを取得可能にし、ERRORはcontentを保持したまま`next_scan_at`を設定して再試行し、INFECTEDはcontentを消去する。`TalentAttachmentScanScheduler`は1分間隔でdue行を最大10件処理し、多重実行を`FOR UPDATE SKIP LOCKED`で防ぐ。失敗間隔は1分、5分、15分、60分へ延ばし、その後も60分間隔で再試行する。試行回数と最終エラー分類だけを保持する。申請所有者以外のアップロード・削除、SUBMITTED/APPROVEDへの追加・削除、重複sha256を拒否する。ダウンロードは申請employee IDと現在のmanager IDを都度検索し、`EXECUTIVE`は有効な`ALL` grantを再確認する。レスポンスは`Content-Disposition: attachment`、`X-Content-Type-Options: nosniff`、`Cache-Control: no-store`を付ける。

- [ ] **Step 4: Configure ClamAV**

`application.yml`:

```yaml
app:
  files:
    max-size-bytes: 5242880
    max-per-submission: 3
    clamav:
      host: ${CLAMAV_HOST:localhost}
      port: ${CLAMAV_PORT:3310}
      connect-timeout: PT2S
      read-timeout: PT30S
    scan-retry-fixed-delay: PT1M
spring:
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 16MB
```

`compose.yml`に存在確認済みの`clamav/clamav:1.4.3`を固定し、ポートは外部公開せずhealthcheckを設定する。backendの`CLAMAV_HOST=clamav`とhealthy依存を追加する。スケジューラは専用Configurationで有効化し、テスト設定では停止する。テストでは`@TestConfiguration`のFake scannerを`@Primary`で注入し、外部プロセスへ接続しない。multipart上限超過例外は既存のAPIエラー形式へ変換する。

- [ ] **Step 5: Run tests**

Run: `cd backend; .\mvnw.cmd "-Dtest=TalentAttachmentServiceIntegrationTests,TalentAttachmentControllerIntegrationTests" test`

Expected: PASS.

Run: `docker compose config --quiet`

Expected: exit code 0.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/query/insight/talent/attachment backend/src/test/java/com/query/insight/talent/attachment backend/src/main/resources/application.yml backend/src/test/resources/application.yml compose.yml
git commit -m "根拠ファイルの安全な保存と閲覧認可を実装"
```

### Task 4: 社員の下書き・提出・履歴API

**Files:**
- Create: `backend/src/main/java/com/query/insight/talent/TalentSubmissionService.java`
- Create: `backend/src/main/java/com/query/insight/talent/TalentSubmissionController.java`
- Create: `backend/src/test/java/com/query/insight/talent/TalentSubmissionServiceIntegrationTests.java`
- Create: `backend/src/test/java/com/query/insight/talent/TalentSubmissionControllerIntegrationTests.java`
- Modify: `backend/src/main/java/com/query/insight/notification/NotificationService.java`

**Interfaces:**
- `GET /api/v1/talent-submissions/me?type=` returns own drafts, returned, pending, and history.
- `POST /api/v1/talent-submissions/{type}` creates a draft.
- `PUT /api/v1/talent-submissions/{publicId}` updates DRAFT/RETURNED with required version.
- `POST /api/v1/talent-submissions/{publicId}/submit` submits with required version.
- Produces notification type `TALENT_REVIEW_REQUEST` linked to `/approvals/talent/{publicId}`.

- [ ] **Step 1: Write failing integration tests**

Create named integration tests with these assertions:

- `employeeCanSaveAndSubmitOwnSkillWithCleanAttachment`: DRAFT becomes SUBMITTED, version increments, one SUBMIT event and one manager notification exist.
- `employeeCannotEditAnotherEmployeesSubmission`: response is 404 and neither payload nor version changes.
- `submittedVersionCannotBeEdited`: response is 409 / `INVALID_STATE_TRANSITION` and the submitted payload stays unchanged.
- `resubmissionAfterReturnUsesSameLogicalIdAndNextRevision`: logical ID is unchanged, a new revision row is created, and the returned row remains immutable.
- `concurrentVersionIsRejectedWithoutDuplicateNotification`: stale version returns 409 / `VERSION_CONFLICT`; exactly one notification exists for its dedupe key.

- [ ] **Step 2: Run and verify failure**

Run: `cd backend; .\mvnw.cmd "-Dtest=TalentSubmissionServiceIntegrationTests,TalentSubmissionControllerIntegrationTests" test`

Expected: FAIL because endpoints/service do not exist.

- [ ] **Step 3: Implement employee workflow**

Controller request records use Bean Validation and boxed `@NotNull Long version`. The actor employee/account public IDs come only from JWT claims; request bodies cannot select an employee.

```java
@PostMapping("/{publicId}/submit")
SubmissionResponse submit(@AuthenticationPrincipal Jwt jwt,
        @PathVariable @Pattern(regexp = ULID) String publicId,
        @Valid @RequestBody VersionRequest request,
        @RequestHeader("X-Trace-Id") String traceId) {
    return service.submit(jwt.getClaimAsString("employeePublicId"),
            jwt.getClaimAsString("accountPublicId"), publicId, request.version(), traceId);
}
```

提出時にpayloadを再検証し、添付がある場合は全件CLEANであること、現在の直属上長と有効アカウントが存在することを検証する。添付自体は任意とする。対象申請更新、SUBMIT event、上長通知、`TALENT_SUBMIT`監査を同じ`@Transactional`メソッドで行う。

- [ ] **Step 4: Run focused tests**

Run: `cd backend; .\mvnw.cmd "-Dtest=TalentSubmissionServiceIntegrationTests,TalentSubmissionControllerIntegrationTests" test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/query/insight/talent/TalentSubmissionService.java backend/src/main/java/com/query/insight/talent/TalentSubmissionController.java backend/src/main/java/com/query/insight/notification/NotificationService.java backend/src/test/java/com/query/insight/talent/TalentSubmissionServiceIntegrationTests.java backend/src/test/java/com/query/insight/talent/TalentSubmissionControllerIntegrationTests.java
git commit -m "社員のタレント申請と再提出を実装"
```

### Task 5: 上長の1件承認・差戻し・正式データ反映

**Files:**
- Create: `backend/src/main/java/com/query/insight/talent/ManagerTalentSubmissionController.java`
- Create: `backend/src/test/java/com/query/insight/talent/ManagerTalentSubmissionIntegrationTests.java`
- Modify: `backend/src/main/java/com/query/insight/talent/TalentSubmissionService.java`
- Modify: `backend/src/main/java/com/query/insight/talent/TalentSubmissionRepository.java`
- Modify: `backend/src/main/java/com/query/insight/notification/NotificationService.java`

**Interfaces:**
- `GET /api/v1/manager/talent-submissions?status=SUBMITTED` returns current direct reports only.
- `GET /api/v1/manager/talent-submissions/{publicId}` returns payload, approved predecessor, attachments, events.
- `POST /api/v1/manager/talent-submissions/{publicId}/approve` consumes `{version}`.
- `POST /api/v1/manager/talent-submissions/{publicId}/return` consumes `{version, reason}`.

- [ ] **Step 1: Write failing authorization and immutable-version tests**

Create named integration tests with these assertions:

- `currentManagerCanApproveAndOfficialProfileChangesAtomically`: submission becomes APPROVED and the matching formal row contains the submitted value in one commit.
- `managerCannotModifyPayloadWhileApproving`: an unknown payload field is rejected with 400 and no state changes.
- `nonAssignedAndFormerManagerReceive404`: both actors receive 404 and no decision event is added.
- `returnRequiresNonBlankReasonAtMost1000Characters`: blank and 1001-character reasons return 400; a 1000-character reason succeeds.
- `failedOfficialWriteRollsBackDecisionEventNotificationAndAudit`: force a formal-table constraint failure and assert all five tables remain unchanged.
- `approvingNewRevisionSupersedesOldSubmissionButPreservesItsPayload`: prior approved revision becomes SUPERSEDED, its payload is unchanged, and the new revision becomes APPROVED.

- [ ] **Step 2: Run and verify failure**

Run: `cd backend; .\mvnw.cmd "-Dtest=ManagerTalentSubmissionIntegrationTests" test`

Expected: FAIL because manager workflow does not exist.

- [ ] **Step 3: Implement manager scope and decisions**

Manager lookup must include:

```sql
WHERE s.public_id=:submissionPublicId
  AND e.manager_employee_id=(SELECT id FROM employees WHERE public_id=:managerEmployeePublicId)
  AND e.employment_status<>'RETIRED'
```

承認処理は申請typeごとにparameterized SQLで正式テーブルへINSERT/UPDATEする。修正申請では`base_record_version`が現在の正式レコードversionと一致しない場合409。新正式版の反映後、直前APPROVED申請をSUPERSEDED、対象をAPPROVEDにし、APPROVE event、社員通知、`TALENT_APPROVE`監査を確定する。差戻しは正式テーブルを変更せず、RETURNED、reason、event、社員通知、監査だけを確定する。

- [ ] **Step 4: Run focused and profile regression tests**

Run: `cd backend; .\mvnw.cmd "-Dtest=ManagerTalentSubmissionIntegrationTests,AuthServiceIntegrationTests" test`

Expected: PASS and existing realistic profile data remains visible.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/query/insight/talent/ManagerTalentSubmissionController.java backend/src/main/java/com/query/insight/talent/TalentSubmissionService.java backend/src/main/java/com/query/insight/talent/TalentSubmissionRepository.java backend/src/main/java/com/query/insight/notification/NotificationService.java backend/src/test/java/com/query/insight/talent/ManagerTalentSubmissionIntegrationTests.java
git commit -m "上長のタレント承認と差戻しを実装"
```

### Task 6: スキル・資格マスタ追加申請

**Files:**
- Create: `backend/src/main/java/com/query/insight/master/MasterRequestService.java`
- Create: `backend/src/main/java/com/query/insight/master/MasterRequestController.java`
- Create: `backend/src/test/java/com/query/insight/master/MasterRequestIntegrationTests.java`
- Modify: `backend/src/main/java/com/query/insight/notification/NotificationService.java`

**Interfaces:**
- `POST /api/v1/master-requests` employee creates `SKILL` or `CERTIFICATION` request.
- `GET /api/v1/master-requests/me` returns own requests.
- `GET /api/v1/admin/master-requests?status=SUBMITTED` requires SYSTEM_ADMIN or EXECUTIVE/ALL.
- `POST /api/v1/admin/master-requests/{publicId}/approve` consumes version.
- `POST /api/v1/admin/master-requests/{publicId}/return` consumes version and reason.

- [ ] **Step 1: Write failing workflow tests**

Create named integration tests with these assertions:

- `employeeRequestsMissingSkillAndExecutiveApprovesIt`: request becomes APPROVED and exactly one ACTIVE skill master is created.
- `systemAdminCanApproveButManagerCannot`: SYSTEM_ADMIN receives 200; MANAGER-only receives 403.
- `executiveWithoutAllScopeCannotReview`: EXECUTIVE with SELF or DEPARTMENT scope receives 403.
- `duplicateNormalizedCodeOrNameReturns409`: duplicate normalized code and duplicate trimmed/case-folded name each return 409 and create no second master.
- `approvalAndNotificationAreIdempotentByVersionAndDedupeKey`: repeated decision returns 409 and requester receives one outcome notification.

- [ ] **Step 2: Run and verify failure**

Run: `cd backend; .\mvnw.cmd "-Dtest=MasterRequestIntegrationTests" test`

Expected: FAIL because controller/service do not exist.

- [ ] **Step 3: Implement request and decision rules**

Skill request fields are code 1～40 uppercase ASCII/underscore, name 1～100, category 1～40, description 1～500. Certification fields are code 1～50, name 1～150, issuer 1～150. Normalize code with `Locale.ROOT`; compare duplicate names after trim and lowercase. Approval inserts ACTIVE master and marks request APPROVED atomically. Return requires 1～1000 characters. Notify requester on both outcomes and audit without storing proposed description or reason text.

- [ ] **Step 4: Run focused tests**

Run: `cd backend; .\mvnw.cmd "-Dtest=MasterRequestIntegrationTests" test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/query/insight/master backend/src/test/java/com/query/insight/master backend/src/main/java/com/query/insight/notification/NotificationService.java
git commit -m "スキルと資格マスタの追加申請を実装"
```

### Task 7: 承認済みプロフィール・申請履歴・AI境界

**Files:**
- Modify: `backend/src/main/java/com/query/insight/talent/TalentProfileService.java`
- Modify: `backend/src/main/java/com/query/insight/talent/TalentProfileController.java`
- Modify: `backend/src/main/java/com/query/insight/analysis/AiAnalysisService.java`
- Create: `backend/src/test/java/com/query/insight/talent/TalentProfileApprovalVisibilityIntegrationTests.java`
- Modify: `backend/src/test/java/com/query/insight/auth/AuthServiceIntegrationTests.java`

**Interfaces:**
- Existing `GET /api/v1/employees/{publicId}/talent-profile` remains backward compatible and returns approved formal rows only.
- `GET /api/v1/talent-submissions/me/{logicalPublicId}/history` returns own revisions and events.
- Manager detail from Task 5 supplies direct-report history; no general history endpoint exposes another employee.

- [ ] **Step 1: Write failing visibility tests**

Create named integration tests with these assertions:

- `pendingRevisionDoesNotChangeFormalProfileOrAiInput`: both APIs continue returning the prior approved level.
- `approvedRevisionChangesProfileAndAiInput`: after approval, both APIs return the new level and omit the superseded one.
- `employeeCanSeeOwnReturnReasonAndOldVersions`: history is revision-descending and includes the return reason only for the owner.
- `systemAdminAndAuditorCannotReadTalentProfile`: each receives 404 for another employee.
- `executiveAllCanReadApprovedProfileButNotPendingPayload`: formal profile returns 200 with approved data and contains no pending payload field or value.

- [ ] **Step 2: Run and verify current over-broad access fails**

Run: `cd backend; .\mvnw.cmd "-Dtest=TalentProfileApprovalVisibilityIntegrationTests" test`

Expected: FAIL because current EmployeeService scope allows SYSTEM_ADMIN/AUDITOR and no submission history exists.

- [ ] **Step 3: Implement talent-specific access policy**

Do not reuse the broad employee-admin policy for talent content. Add a private/service policy that permits only target employee, current direct manager, or valid EXECUTIVE/ALL. `TalentProfileService` continues querying formal tables, ensuring pending payloads cannot leak. `AiAnalysisService` must keep using the approved profile service/data only; add regression assertion that pending values are absent from serialized AI request.

- [ ] **Step 4: Run focused tests**

Run: `cd backend; .\mvnw.cmd "-Dtest=TalentProfileApprovalVisibilityIntegrationTests,AuthServiceIntegrationTests,OllamaAnalysisClientTests" test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/query/insight/talent backend/src/main/java/com/query/insight/analysis/AiAnalysisService.java backend/src/test/java/com/query/insight/talent/TalentProfileApprovalVisibilityIntegrationTests.java backend/src/test/java/com/query/insight/auth/AuthServiceIntegrationTests.java
git commit -m "承認済みタレント情報だけを公開対象に制限"
```

### Task 8: 本人・上長・マスタ申請UIと通知自動既読

**Files:**
- Create: `frontend/src/pages/TalentSubmissionFormPage.tsx`
- Create: `frontend/src/pages/TalentSubmissionHistoryPage.tsx`
- Create: `frontend/src/pages/ManagerTalentApprovalsPage.tsx`
- Create: `frontend/src/pages/ManagerTalentApprovalDetailPage.tsx`
- Create: `frontend/src/pages/MasterRequestsPage.tsx`
- Create: `frontend/src/pages/TalentSubmissionPages.test.tsx`
- Create: `frontend/src/pages/ManagerTalentApprovalPages.test.tsx`
- Modify: `frontend/src/pages/TalentProfilePage.tsx`
- Modify: `frontend/src/pages/NotificationsPage.tsx`
- Modify: `frontend/src/pages/NotificationsPage.test.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/components/AppLayout.tsx`
- Modify: `frontend/src/types.ts`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Routes: `/talent/new/:type`, `/talent/:logicalPublicId/history`, `/approvals/talent`, `/approvals/talent/:publicId`, `/master-requests`。
- FormData fields: `file` and `version` for each attachment; JSON draft save occurs before attachment upload, and each response version is passed to the next upload or delete.
- Notification link click calls `PATCH /api/v1/notifications/{publicId}/read` before navigation.

- [ ] **Step 1: Write failing component tests**

Create component tests for these exact interactions:

- For each of the four type selectors, render its required labels, save JSON first, upload one PDF with `FormData`, then enable submit.
- Render a RETURNED revision and assert the return reason, prior APPROVED revision, and current edit form are visible together.
- On manager detail, assert payload fields are read-only, approve posts only `{version}`, and return is disabled until a nonblank reason is entered.
- Select four files and a 5MB+1 byte file; assert client errors appear before any upload request.
- Click an unread notification; assert the individual PATCH resolves before the router navigates to its link and the unread badge decrements once.

- [ ] **Step 2: Run and verify missing screens**

Run: `cd frontend; npm test -- --run frontend/src/pages/TalentSubmissionPages.test.tsx frontend/src/pages/ManagerTalentApprovalPages.test.tsx frontend/src/pages/NotificationsPage.test.tsx`

Expected: FAIL because new pages and behavior do not exist.

- [ ] **Step 3: Add API types and forms**

```ts
export type TalentSubmissionStatus = 'DRAFT' | 'SUBMITTED' | 'RETURNED' | 'APPROVED' | 'SUPERSEDED'
export type TalentSubmissionType = 'SKILL' | 'KNOWLEDGE' | 'CAREER' | 'CERTIFICATION'
export type TalentAttachment = { publicId: string; fileName: string; contentType: string; sizeBytes: number; scanStatus: 'PENDING' | 'CLEAN' | 'INFECTED' | 'ERROR' }
export type TalentSubmission = { publicId: string; logicalPublicId: string; type: TalentSubmissionType; revision: number; status: TalentSubmissionStatus; version: number; returnReason: string | null; payload: Record<string, unknown>; attachments: TalentAttachment[] }
```

Use React Hook Form and Zod already installed. Render type-specific inputs, accept only `.pdf,.jpg,.jpeg,.png`, enforce 3 files and 5,242,880 bytes client-side, then rely on API validation as authoritative. 上長詳細は申請値、現在の正式値、添付リンク、履歴、承認、差戻し理由だけを表示し、申請値を変更する入力を置かない。

- [ ] **Step 4: Implement notification auto-read**

Replace the plain link with a click handler that prevents navigation, calls the individual read API, invalidates `notifications` and `dashboard`, then uses `navigate(item.linkPath)`. If marking read fails, show the existing error pattern and do not navigate, so unread state is not silently inconsistent.

- [ ] **Step 5: Run frontend quality gates**

Run: `cd frontend; npm run lint`

Expected: exit code 0.

Run: `cd frontend; npm test -- --run`

Expected: all tests PASS.

Run: `cd frontend; npm run build`

Expected: TypeScript and Vite build PASS.

- [ ] **Step 6: Commit**

```powershell
git add frontend/src
git commit -m "タレント申請と上長承認の画面を実装"
```

### Task 9: ローカル再現データ・文書・統合検証

**Files:**
- Modify: `backend/src/main/java/com/query/insight/config/LocalRealisticDataInitializer.java`
- Create: `backend/src/test/java/com/query/insight/talent/TalentWorkflowEndToEndIntegrationTests.java`
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `docs/02-requirements.md`
- Modify: `docs/03-use-cases.md`
- Modify: `docs/06-traceability.md`
- Modify: `docs/09-security-measures.md`

**Interfaces:**
- QITEST has one DRAFT skill submission.
- QI0003 has one SUBMITTED career request assigned to QI0002.
- Another direct report has one RETURNED certification request.
- Existing approved profiles remain available.

- [ ] **Step 1: Write failing end-to-end integration test**

```java
@Test
void employeeAttachmentSubmissionManagerApprovalAndProfilePublicationCompleteAtomically() {
    var draft = employeeApi.createSkillDraft("JAVA", 4, 0);
    var attachment = employeeApi.upload(draft.publicId(), validPdfBytes());
    assertThat(attachment.scanStatus()).isEqualTo("CLEAN");

    var submitted = employeeApi.submit(draft.publicId(), draft.version() + 1);
    var approved = managerApi.approve(submitted.publicId(), submitted.version());

    assertThat(approved.status()).isEqualTo("APPROVED");
    assertThat(employeeApi.profile().skills())
            .anySatisfy(skill -> assertThat(skill.code()).isEqualTo("JAVA"));
    assertThat(eventCount(draft.logicalPublicId(), "SUBMIT", "APPROVE")).isEqualTo(2);
    assertThat(notificationCountFor(draft.logicalPublicId())).isEqualTo(2);
    assertThat(auditCountFor(draft.logicalPublicId(), "TALENT_SUBMIT", "TALENT_APPROVE"))
            .isEqualTo(2);
}
```

Also test manager reassignment between submission and review: the former manager receives 404 and the new manager can decide.

- [ ] **Step 2: Run and verify sample/flow test fails**

Run: `cd backend; .\mvnw.cmd "-Dtest=TalentWorkflowEndToEndIntegrationTests" test`

Expected: FAIL until sample states and complete API flow exist.

- [ ] **Step 3: Add deterministic local sample states**

Use idempotent `WHERE NOT EXISTS` inserts keyed by fixed dedupe/business keys. Do not add real employee data or real files; generate a minimal valid PDF byte sequence in local-only initializer for the submitted example and mark it CLEAN. Keep `test` / `test` local-only.

- [ ] **Step 4: Update documentation truthfully**

Mark only implemented Phase 1A rows as API-connected. Keep employee/org/account administration and Excel import as Phase 1B/1C, not implemented. Document ClamAV startup, attachment constraints, access scope, PostgreSQL storage tradeoff, and the exact manual flow.

- [ ] **Step 5: Run complete static verification**

Run: `cd backend; .\mvnw.cmd package`

Expected: all backend tests PASS and executable JAR is built.

Run: `cd frontend; npm ci; npm run lint; npm test -- --run; npm run build`

Expected: all frontend gates PASS. If `npm audit --omit=dev --audit-level=high` still reports the known React Router RSC advisory, record the exact current output and do not force a breaking downgrade.

Run: `git diff --check`

Expected: no whitespace errors.

Run: `docker compose config --quiet`

Expected: exit code 0.

- [ ] **Step 6: Rebuild and verify runtime**

Run:

```powershell
docker compose up -d --build db clamav ollama backend frontend
docker compose ps
```

Expected: db, clamav, ollama, backend are healthy; frontend is running at `http://localhost:8088`.

Verify by real HTTP/browser:

1. `test` / `test` creates a skill draft and uploads PDF.
2. `test` submits and cannot edit the submitted revision.
3. `manager@query.local` / `QueryInsight#2026` sees it in the approval list, downloads the file, and approves it.
4. `test` sees the approved skill in the formal profile and the notification becomes read when opened.
5. A non-assigned manager and SYSTEM_ADMIN receive 404 for the attachment and submission detail.
6. At 390px, employee profile, form, history, and notifications have no horizontal overflow; manager/admin PC-only pages show a clear PC requirement on small screens.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/query/insight/config/LocalRealisticDataInitializer.java backend/src/test/java/com/query/insight/talent/TalentWorkflowEndToEndIntegrationTests.java README.md AGENTS.md docs/02-requirements.md docs/03-use-cases.md docs/06-traceability.md docs/09-security-measures.md
git commit -m "タレント申請フローの再現データと運用文書を整備"
```

---

## Phase 1A Completion Gate

- 4種類すべてで下書き・添付・提出・差戻し・再提出・承認が動作する。
- 承認済み旧版と正式データが保持され、未承認値がプロフィール・AIへ漏れない。
- 現在の直属上長だけが審査し、上長変更直後から旧上長を拒否する。
- 本人・現在上長・社長だけがCLEAN添付を取得できる。
- スキル・資格マスタ追加申請を社長または管理者が処理できる。
- 通知リンクを開くと自動既読になる。
- Backend package、Frontend lint/test/build、Compose構文、実HTTP・ブラウザ主要フローが成功する。
- `docs/06-traceability.md`が実際の接続状態と一致する。

Phase 1A完了後、別計画としてPhase 1B「社員・組織・アカウント・マスタライフサイクル管理」、続いてPhase 1C「Excel初回移行」を作成・実行する。
