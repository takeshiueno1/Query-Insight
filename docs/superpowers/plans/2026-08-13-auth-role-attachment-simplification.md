# 認証・権限・任意添付の簡素化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** アカウントロックを廃止し、利用者権限を一般・役職者・管理者の3種類へ安全に統合し、根拠資料なしのタレント申請を正式な正常系として保証する。

**Architecture:** Flyway V6で新しい3ロールと既存権限の非破壊的移行を行い、旧ロールは履歴保持のため無効化する。APIは新ロールに加えて現在の直属部下関係または有効なスコープを検証し、フロントエンドはJWTの3ロールとスコープに応じた導線を表示する。アカウントロック用DB列は互換性のため残すが、認証処理から参照・更新を除外する。

**Tech Stack:** Java 21、Spring Boot 4.1、Spring Security、Spring JDBC、Flyway、PostgreSQL 18.4、React 19、TypeScript 6、Vitest、Docker Compose

## Global Constraints

- ログインの既存レート制限、失敗監査、短寿命JWT、Refresh Tokenローテーション、再利用検知を維持する。
- 公開ロールは `GENERAL`、`OFFICER`、`ADMIN` の3種類だけとする。
- `OFFICER/SUBORDINATES` と `OFFICER/ALL` をAPI側で区別し、ロールだけで全社操作を許可しない。
- `ADMIN`だけでは他社員のタレント内容・添付を閲覧できない。
- 根拠資料は任意だが、添付時の形式・サイズ・件数・ClamAV検査・閲覧認可を緩和しない。
- `EXECUTIVE_REVIEW`等の状態名、監査アクション名、既存URLは互換性のため変更しない。
- ユーザー所有の `.codex/`、`.superpowers/`、同期生成された未コミット`AGENTS.md`を本実装コミットへ含めない。

---

### Task 1: 3ロールへの非破壊的DB移行

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__simplify_roles_and_disable_account_lock.sql`
- Create: `backend/src/test/java/com/query/insight/security/SimplifiedRoleMigrationIntegrationTests.java`

**Interfaces:**
- Produces active role codes `GENERAL`, `OFFICER`, `ADMIN`。
- Produces grants `GENERAL/SELF`, `OFFICER/SUBORDINATES`, `OFFICER/ALL`, `ADMIN/ALL`。
- Leaves legacy role rows present but `INACTIVE`。

- [ ] **Step 1: Write the failing migration test**

Test that after Flyway migration the active role list is exactly the three new codes, no active grant references an inactive role, QI0002 has `OFFICER/SUBORDINATES`, QI0039 has `OFFICER/ALL`, QI0001 has `ADMIN/ALL`, and every account has exactly one effective primary role according to the precedence `ADMIN > OFFICER > GENERAL`.

- [ ] **Step 2: Run the test and verify RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=SimplifiedRoleMigrationIntegrationTests" test`

Expected: FAIL because migration V6 and the new roles do not exist.

- [ ] **Step 3: Implement V6 migration**

Insert the three roles idempotently. Copy effective legacy grants using `NOT EXISTS`, applying `ADMIN > OFFICER > GENERAL` so an account receives one new role while preserving the required scope. Revoke still-active legacy grants, mark legacy roles inactive, and reset `accounts.failed_count=0, locked_until=NULL`. Do not delete role or grant history.

- [ ] **Step 4: Run the migration test and backend schema regression**

Run: `cd backend; .\mvnw.cmd "-Dtest=SimplifiedRoleMigrationIntegrationTests,TalentSubmissionSchemaIntegrationTests,BootstrapAdminInitializerTests" test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/resources/db/migration/V6__simplify_roles_and_disable_account_lock.sql backend/src/test/java/com/query/insight/security/SimplifiedRoleMigrationIntegrationTests.java
git commit -m "一般・役職者・管理者の3権限へ移行"
```

### Task 2: アカウントロック廃止と認証プリンシパルのスコープ提供

**Files:**
- Modify: `backend/src/main/java/com/query/insight/auth/AuthService.java`
- Modify: `backend/src/main/java/com/query/insight/auth/AuthRepository.java`
- Modify: `backend/src/main/java/com/query/insight/security/AccountPrincipal.java`
- Modify: `backend/src/main/java/com/query/insight/security/JwtService.java`
- Modify: `backend/src/test/java/com/query/insight/auth/AuthServiceIntegrationTests.java`

**Interfaces:**
- `AccountRecord` no longer exposes `failedCount` or `lockedUntil`。
- `AccountPrincipal` exposes `Set<String> roles` and `Set<String> scopes`。
- JWT includes `roles` and `scopes` claims。

- [ ] **Step 1: Write failing authentication tests**

Add tests that six consecutive invalid passwords all return 401, leave `failed_count=0` and `locked_until=NULL`, then the correct password succeeds. Assert local test user returns only `GENERAL/SELF`; manager returns `OFFICER/SUBORDINATES`; executive returns `OFFICER/ALL`; admin returns `ADMIN/ALL`.

- [ ] **Step 2: Run and verify RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=AuthServiceIntegrationTests" test`

Expected: FAIL because failed login still increments/locks and principal has no scopes.

- [ ] **Step 3: Implement minimal authentication changes**

Remove lock checks and `loginFailed` calls. Keep status validation, dummy hash, 401 envelope and audit calls. Simplify successful login to update only `last_login_at`. Add `activeScopes(accountId, now)` from active grants and issue the `scopes` JWT claim.

- [ ] **Step 4: Run focused authentication and rate-limit tests**

Run: `cd backend; .\mvnw.cmd "-Dtest=AuthServiceIntegrationTests,RequestRateLimitFilterTests" test`

Expected: PASS, including unchanged rate-limit behavior.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/query/insight/auth backend/src/main/java/com/query/insight/security backend/src/test/java/com/query/insight/auth
git commit -m "ログインのアカウントロックを廃止"
```

### Task 3: API認可を3ロールとスコープへ統合

**Files:**
- Modify: `backend/src/main/java/com/query/insight/evaluation/ManagerEvaluationController.java`
- Modify: `backend/src/main/java/com/query/insight/evaluation/ExecutiveEvaluationController.java`
- Modify: `backend/src/main/java/com/query/insight/evaluation/EvaluationWorkflowService.java`
- Modify: `backend/src/main/java/com/query/insight/talent/ManagerTalentSubmissionController.java`
- Modify: `backend/src/main/java/com/query/insight/talent/TalentSubmissionService.java`
- Modify: `backend/src/main/java/com/query/insight/talent/TalentProfileService.java`
- Modify: `backend/src/main/java/com/query/insight/talent/attachment/TalentAttachmentService.java`
- Modify: `backend/src/main/java/com/query/insight/master/MasterRequestService.java`
- Modify: `backend/src/main/java/com/query/insight/employee/EmployeeController.java`
- Modify: `backend/src/main/java/com/query/insight/audit/AuditController.java`
- Modify: `backend/src/test/java/com/query/insight/evaluation/EvaluationApprovalControllerIntegrationTests.java`
- Modify: `backend/src/test/java/com/query/insight/evaluation/EvaluationWorkflowServiceIntegrationTests.java`
- Modify: `backend/src/test/java/com/query/insight/evaluation/ExecutiveDashboardIntegrationTests.java`
- Modify: `backend/src/test/java/com/query/insight/talent/ManagerTalentSubmissionIntegrationTests.java`
- Modify: `backend/src/test/java/com/query/insight/talent/TalentProfileApprovalVisibilityIntegrationTests.java`
- Modify: `backend/src/test/java/com/query/insight/talent/attachment/TalentAttachmentControllerIntegrationTests.java`
- Modify: `backend/src/test/java/com/query/insight/talent/attachment/TalentAttachmentServiceIntegrationTests.java`
- Modify: `backend/src/test/java/com/query/insight/master/MasterRequestIntegrationTests.java`
- Modify: `backend/src/test/java/com/query/insight/audit/AuditControllerIntegrationTests.java`

**Interfaces:**
- Manager APIs require role `OFFICER`; service layer continues checking current assignment/direct-report relationship.
- Executive APIs require role `OFFICER`; service layer requires active `OFFICER/ALL` grant.
- Admin APIs require `ADMIN`.
- Talent content remains readable only by self, current manager (`OFFICER/SUBORDINATES`), or `OFFICER/ALL`.

- [ ] **Step 1: Change security test JWTs first and verify RED**

Replace legacy test roles with new roles/scopes in manager, executive, master, employee, audit and talent integration tests. Add explicit tests that `OFFICER/SUBORDINATES` receives 403 from executive endpoints, `OFFICER/ALL` succeeds, and `ADMIN/ALL` cannot read another employee's talent profile or attachment solely by being admin.

- [ ] **Step 2: Run affected authorization tests and verify RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=EvaluationApprovalControllerIntegrationTests,EvaluationWorkflowServiceIntegrationTests,ExecutiveDashboardIntegrationTests,ManagerTalentSubmissionIntegrationTests,TalentProfileApprovalVisibilityIntegrationTests,TalentAttachmentControllerIntegrationTests,TalentAttachmentServiceIntegrationTests,MasterRequestIntegrationTests,AuditControllerIntegrationTests" test`

Expected: FAIL because controllers and SQL still require legacy roles.

- [ ] **Step 3: Implement new role/scope checks**

Update `@PreAuthorize` expressions to `OFFICER` or `ADMIN`. Replace SQL role predicates `MANAGER`/`EXECUTIVE` with `OFFICER` plus exact scope. Preserve current-direct-report and `ALL` checks. Do not grant admin talent-content access.

- [ ] **Step 4: Run affected authorization tests**

Expected: all focused tests PASS; wrong-scope tests return 403 or 404 according to the established contract.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java backend/src/test/java
git commit -m "3権限とデータスコープでAPI認可を統一"
```

### Task 4: Initializerとフロントエンドを3ロールへ統合

**Files:**
- Modify: `backend/src/main/java/com/query/insight/config/LocalRealisticDataInitializer.java`
- Modify: `backend/src/main/java/com/query/insight/config/BootstrapAdminInitializer.java`
- Modify: `frontend/src/features/auth/auth-context.ts`
- Modify: `frontend/src/components/AppLayout.tsx`
- Modify: `frontend/src/pages/MasterRequestsPage.tsx`
- Modify: `frontend/src/pages/ManagerEvaluationDetailPage.tsx`
- Modify: `frontend/src/pages/ManagerTalentApprovalsPage.tsx`
- Modify: `frontend/src/pages/ExecutiveDashboardPage.tsx`
- Create: `frontend/src/components/AppLayout.test.tsx`
- Modify: `frontend/src/pages/ApprovalPages.test.tsx`

**Interfaces:**
- Frontend authenticated user has `roles: Array<'GENERAL'|'OFFICER'|'ADMIN'>` and `scopes: string[]`。
- Officer subordinate navigation appears for `OFFICER + SUBORDINATES`。
- Executive navigation appears for `OFFICER + ALL`。
- Audit navigation appears for `ADMIN`。

- [ ] **Step 1: Write or update failing UI tests**

Test three users: `GENERAL/SELF` sees only personal menus; `OFFICER/SUBORDINATES` sees manager/talent approvals but not final approval or audit; `OFFICER/ALL` sees final approval but not audit; `ADMIN/ALL` sees audit and master review but no talent-content menu solely due to admin.

- [ ] **Step 2: Run frontend tests and verify RED**

Run: `cd frontend; npm test -- --run --maxWorkers=1 --no-file-parallelism`

Expected: FAIL because UI still expects legacy roles and has no scope-aware navigation.

- [ ] **Step 3: Implement initializer and UI changes**

Seed only new roles in fresh local/bootstrap initialization. Give normal users `GENERAL/SELF`, managers `OFFICER/SUBORDINATES`, the executive sample `OFFICER/ALL`, and bootstrap admin `ADMIN/ALL`. Update auth types, role labels, navigation filters, reviewer detection and visible copy.

- [ ] **Step 4: Run frontend gates and initializer regressions**

Run: `cd frontend; npm run lint; npm test -- --run --maxWorkers=1 --no-file-parallelism; npm run build`

Run: `cd ../backend; .\mvnw.cmd "-Dtest=AuthServiceIntegrationTests,BootstrapAdminInitializerTests" test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/query/insight/config frontend/src
git commit -m "3権限に合わせて初期データと画面を更新"
```

### Task 5: 任意添付の回帰保証と文書同期

**Files:**
- Modify: `backend/src/test/java/com/query/insight/talent/TalentWorkflowEndToEndIntegrationTests.java`
- Modify: `frontend/src/pages/TalentSubmissionFormPage.tsx`
- Modify: `frontend/src/pages/TalentSubmissionPages.test.tsx`
- Modify: `README.md`
- Modify: `docs/02-requirements.md`
- Modify: `docs/03-use-cases.md`
- Modify: `docs/06-traceability.md`
- Modify: `docs/09-security-measures.md`
- Modify: `docs/99-open-questions.md`
- Modify: `.codex/PROJECT_RULES.md`

**Interfaces:**
- Submission with zero attachments remains valid for all four talent types.
- UI label is `根拠資料（任意）`。
- Attachment constraints are applied only when a file is selected.

- [ ] **Step 1: Add failing copy and four-type no-attachment tests**

Backend integration test creates, submits and approves SKILL, KNOWLEDGE, CAREER and CERTIFICATION with zero attachment rows. Frontend test requires the optional label and verifies submit does not call the attachment endpoint when no file is selected.

- [ ] **Step 2: Run focused tests and verify RED where behavior/copy is missing**

Run backend `TalentWorkflowEndToEndIntegrationTests` and frontend `TalentSubmissionPages.test.tsx`.

- [ ] **Step 3: Implement minimal UI copy and document updates**

Keep backend submission logic unchanged if the tests prove zero attachments already work. Update only the UI copy and approved requirements. Replace account-lock and legacy-role requirements with the new model, while retaining internal state names and legacy design history documents unchanged.

- [ ] **Step 4: Run complete verification**

Run:

```powershell
Set-Location backend
.\mvnw.cmd package
Set-Location ..\frontend
npm ci
npm run lint
npm test -- --run --maxWorkers=1 --no-file-parallelism
npm run build
Set-Location ..
git diff --check
docker compose config --quiet
```

If Docker Engine is healthy, rebuild locally and verify `test/test`, an `OFFICER/SUBORDINATES` account, an `OFFICER/ALL` account and an `ADMIN/ALL` account through HTTP/browser. If the existing Docker read-only filesystem fault recurs, record it separately as an environment failure.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/test frontend/src README.md docs .codex/PROJECT_RULES.md
git commit -m "任意添付と3権限の仕様を文書と統合テストへ反映"
```

## Completion Gate

- ログイン失敗回数によるロックが発生せず、レート制限と監査が残る。
- 有効ロールとJWTロールが `GENERAL`、`OFFICER`、`ADMIN` のみに限定される。
- 上長と社長の操作範囲が `SUBORDINATES` と `ALL` で分離される。
- `ADMIN` と `OFFICER/ALL` の権限が混同されない。
- 4種類のタレント申請を添付0件で提出・承認できる。
- 添付した場合の安全制約と閲覧制御が回帰していない。
- Backend package、Frontend lint/test/build、Compose構文が成功する。
- 実装状態と `docs/06-traceability.md` が一致する。
