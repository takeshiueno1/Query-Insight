# 上長評価・経営者承認 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 社員の自己評価提出から上長評価、経営者の最終承認、社員への確定結果公開までを、差戻し・再オープン・監査・通知・経営集計を含めてローカルで通し実行できるようにする。

**Architecture:** 既存のSpring JDBC縦断構成を維持し、`evaluation_targets`を現在状態、`manager_evaluations`を変更不能な上長評価版、`evaluation_workflow_events`を追記型の業務履歴として分離する。バックエンドで明示的な状態遷移、割当上長スコープ、`EXECUTIVE`ロール、楽観ロックを検証し、React Query画面から役割別APIを利用する。

**Tech Stack:** Java 21、Spring Boot 4.1.0、Spring JDBC、Spring Security、Flyway、PostgreSQL 18.4、React 19.2、TypeScript 6、TanStack Query 5、Vite 8、Vitest、Docker Compose

## Global Constraints

- 現在のダークネイビー／ブラック、黄色の主要アクション、約200pxのサイドバー、64pxのトップバー、カードと表を中心としたデザインを維持する。
- 人事承認、一括承認、メール通知、多段階承認、AI人材選別、汎用ワークフロー基盤は実装しない。
- 上長は割当対象だけ、`EXECUTIVE`は全社評価の参照・最終承認だけを扱い、システム管理権限を持たせない。
- 上長評価は最終承認後だけ社員へ公開する。
- 期限超過後も操作を許可し、警告と`late=true`を記録する。
- 更新APIはversionを必須とし、競合・二重操作・不正遷移を409で拒否する。
- 新しい外部依存ライブラリは追加しない。
- 既存の未コミット修正を破棄せず、関連する修正として検証する。

---

## File Structure

- `backend/src/main/resources/db/migration/V3__evaluation_approval.sql`: 評価版、明細、履歴、現在状態のDB拡張。
- `backend/src/main/java/com/query/insight/evaluation/EvaluationWorkflow.java`: 状態・操作と許可遷移の純粋な判定。
- `backend/src/main/java/com/query/insight/evaluation/EvaluationScoring.java`: 重み付き点数とグレードの純粋計算。
- `backend/src/main/java/com/query/insight/evaluation/EvaluationWorkflowService.java`: トランザクション、認可スコープ、版作成、履歴、通知、監査。
- `backend/src/main/java/com/query/insight/evaluation/ManagerEvaluationController.java`: 上長向けAPI。
- `backend/src/main/java/com/query/insight/evaluation/ExecutiveEvaluationController.java`: 経営者向けAPI。
- `backend/src/main/java/com/query/insight/evaluation/EvaluationController.java`: 社員向け確定結果APIを追加。
- `backend/src/main/java/com/query/insight/notification/NotificationService.java`: 重複防止付き通知作成を共通化。
- `frontend/src/pages/ManagerEvaluationsPage.tsx`: 上長の担当評価一覧。
- `frontend/src/pages/ManagerEvaluationDetailPage.tsx`: 自己・上長比較、下書き、差戻し、提出。
- `frontend/src/pages/ExecutiveDashboardPage.tsx`: 承認件数、期限超過、評価分布、承認待ち一覧。
- `frontend/src/pages/ExecutiveEvaluationDetailPage.tsx`: 個人詳細、最終承認、差戻し、再オープン。
- `frontend/src/pages/EvaluationPage.tsx`: 承認中と確定結果の社員表示。
- `frontend/src/types.ts`, `frontend/src/App.tsx`, `frontend/src/components/AppLayout.tsx`, `frontend/src/styles.css`: 型、ルート、ナビゲーション、既存デザイン準拠の表示。

---

### Task 1: 先行修正とローカルtestユーザーを安定化

**Files:**
- Modify: `backend/src/main/java/com/query/insight/audit/AuditController.java`
- Modify: `backend/src/main/java/com/query/insight/auth/AuthController.java`
- Modify: `backend/src/main/java/com/query/insight/common/GlobalExceptionHandler.java`
- Modify: `backend/src/main/java/com/query/insight/config/LocalRealisticDataInitializer.java`
- Test: `backend/src/test/java/com/query/insight/audit/AuditControllerIntegrationTests.java`
- Test: `backend/src/test/java/com/query/insight/common/GlobalExceptionHandlerTests.java`
- Test: `backend/src/test/java/com/query/insight/auth/AuthServiceIntegrationTests.java`
- Modify: `frontend/src/pages/AuditPage.tsx`
- Modify: `frontend/src/pages/EvaluationPage.tsx`
- Test: `frontend/src/pages/AuditPage.test.tsx`
- Test: `frontend/src/pages/EvaluationPage.test.tsx`

**Interfaces:**
- Produces: ローカル専用`test`/`test`ログイン、監査ログ無条件取得200、認可拒否403、提出済み自己評価の参照専用UI。

- [ ] **Step 1: 既存の追加テストだけを実行し、失敗内容を固定する**

```powershell
cd backend
.\mvnw.cmd -Dtest=AuditControllerIntegrationTests,GlobalExceptionHandlerTests,AuthServiceIntegrationTests test
cd ..\frontend
npm test -- --run src/pages/AuditPage.test.tsx src/pages/EvaluationPage.test.tsx
```

- [ ] **Step 2: 監査画面テストのPromise拒否をQueryClient状態で待機する形へ修正する**

```ts
await vi.waitFor(() => expect(client.getQueryState(['audit'])?.status).toBe('error'))
expect(screen.getByRole('alert')).toHaveTextContent('TEST-TRACE-ID')
```

- [ ] **Step 3: バックエンドとフロントエンドの対象テストを再実行する**

Expected: 対象テストがすべてPASSし、`test`/`test`が`EMPLOYEE`として認証される。

- [ ] **Step 4: 先行修正をコミットする**

```powershell
git add AGENTS.md README.md backend frontend docs/06-traceability.md docs/08-realistic-sample-data.md
git commit -m "動作確認用ユーザーと既知の実行時不具合を修正"
```

### Task 2: 評価承認DBとEXECUTIVEロール

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__evaluation_approval.sql`
- Modify: `backend/src/main/java/com/query/insight/config/LocalDataInitializer.java`
- Modify: `backend/src/main/java/com/query/insight/config/LocalRealisticDataInitializer.java`
- Modify: `backend/src/main/java/com/query/insight/config/BootstrapAdminInitializer.java`
- Test: `backend/src/test/java/com/query/insight/evaluation/EvaluationApprovalSchemaIntegrationTests.java`

**Interfaces:**
- Produces: `manager_evaluations`, `manager_evaluation_details`, `evaluation_workflow_events`、`evaluation_targets.current_manager_evaluation_id/final_score/final_grade/finalized_at`、`EXECUTIVE`ロール。

- [ ] **Step 1: Flyway適用後の制約とEXECUTIVEロールを確認する失敗テストを書く**

```java
assertThat(count("manager_evaluations")).isZero();
assertThat(roleExists("EXECUTIVE")).isTrue();
assertThat(uniqueConstraintRejectsDuplicateRevision()).isTrue();
```

- [ ] **Step 2: テストを実行してV3未存在で失敗することを確認する**

```powershell
.\mvnw.cmd -Dtest=EvaluationApprovalSchemaIntegrationTests test
```

- [ ] **Step 3: V3マイグレーションを追加する**

```sql
CREATE TABLE manager_evaluations (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  target_id BIGINT NOT NULL,
  revision_no INTEGER NOT NULL,
  status VARCHAR(20) NOT NULL,
  summary VARCHAR(3000),
  weighted_score DECIMAL(4,2),
  grade VARCHAR(10),
  submitted_at TIMESTAMP(6),
  finalized_at TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_manager_evaluation_revision UNIQUE(target_id, revision_no),
  CONSTRAINT fk_manager_evaluation_target FOREIGN KEY(target_id) REFERENCES evaluation_targets(id)
);
```

`manager_evaluation_details`は`manager_evaluation_id + axis_code`を一意にし、levelへ1〜5のCHECKを付ける。`evaluation_workflow_events`はactor_account_id、action、from_status、to_status、reason、comment、late、occurred_at、trace_idを持つ。既存`RETURNED`は`SELF_RETURNED`へ変換する。
下書き中はsummaryをNULLまたは空で保持できるが、経営者への提出時にサービス層で必須検証する。

- [ ] **Step 4: 全initializerへEXECUTIVEロール定義を追加し、ローカル経営者アカウントへALLスコープで付与する**

- [ ] **Step 5: スキーマテストを再実行してPASSを確認する**

- [ ] **Step 6: DB変更をコミットする**

```powershell
git add backend/src/main/resources/db/migration backend/src/main/java/com/query/insight/config backend/src/test/java/com/query/insight/evaluation/EvaluationApprovalSchemaIntegrationTests.java
git commit -m "評価承認ワークフローのDB基盤を追加"
```

### Task 3: 状態遷移と評価計算の純粋ロジック

**Files:**
- Create: `backend/src/main/java/com/query/insight/evaluation/EvaluationWorkflow.java`
- Create: `backend/src/main/java/com/query/insight/evaluation/EvaluationScoring.java`
- Test: `backend/src/test/java/com/query/insight/evaluation/EvaluationWorkflowTests.java`
- Test: `backend/src/test/java/com/query/insight/evaluation/EvaluationScoringTests.java`

**Interfaces:**
- Produces: `EvaluationWorkflow.requireTransition(Status from, Action action)`、`EvaluationScoring.calculate(List<WeightedLevel>, String gradeBoundariesJson)`。

- [ ] **Step 1: 全許可遷移と拒否遷移のパラメータ化テストを書く**

```java
assertThat(EvaluationWorkflow.requireTransition(SELF_SUBMITTED, MANAGER_SAVE)).isEqualTo(MANAGER_IN_PROGRESS);
assertThatThrownBy(() -> EvaluationWorkflow.requireTransition(FINALIZED, MANAGER_SUBMIT))
    .isInstanceOf(ApiException.class);
```

- [ ] **Step 2: 加重平均、四捨五入、境界一致の失敗テストを書く**

```java
var result = EvaluationScoring.calculate(List.of(new WeightedLevel(4, 0.6), new WeightedLevel(3, 0.4)), boundaries);
assertThat(result.score()).isEqualByComparingTo("3.60");
assertThat(result.grade()).isEqualTo("B");
```

- [ ] **Step 3: 対象テストを実行して失敗を確認する**

- [ ] **Step 4: switch式による明示状態遷移とBigDecimal計算を実装する**

- [ ] **Step 5: 対象テストを再実行してPASSを確認する**

- [ ] **Step 6: 純粋ロジックをコミットする**

### Task 4: 通知共通化とワークフローサービス

**Files:**
- Create: `backend/src/main/java/com/query/insight/notification/NotificationService.java`
- Create: `backend/src/main/java/com/query/insight/evaluation/EvaluationWorkflowService.java`
- Modify: `backend/src/main/java/com/query/insight/notification/NotificationController.java`
- Test: `backend/src/test/java/com/query/insight/evaluation/EvaluationWorkflowServiceIntegrationTests.java`

**Interfaces:**
- Consumes: Task 2のテーブル、Task 3の状態遷移と評価計算。
- Produces: `managerList`, `managerDetail`, `saveManagerDraft`, `returnToEmployee`, `submitToExecutive`, `executiveDashboard`, `approve`, `returnToManager`, `reopen`。

- [ ] **Step 1: 社員提出から上長提出、経営者承認までの失敗する結合テストを書く**

```java
var draft = service.saveManagerDraft(manager, targetId, version, details, "総評");
var review = service.submitToExecutive(manager, targetId, draft.version(), traceId);
var finalized = service.approve(executive, targetId, review.version(), null, traceId);
assertThat(finalized.status()).isEqualTo("FINALIZED");
```

- [ ] **Step 2: 非割当上長404、一般社員の承認403、差分理由不足400、競合409のテストを書く**

- [ ] **Step 3: 差戻しと再オープンで旧版が不変、新版が作られるテストを書く**

- [ ] **Step 4: 通知、業務履歴、監査ログが1件ずつ同一トランザクションで作られるテストを書く**

- [ ] **Step 5: テストを実行して未実装で失敗することを確認する**

- [ ] **Step 6: JdbcClientの明示SQLと`@Transactional`で最小実装する**

通知一意キーは`EVAL:{targetPublicId}:{action}:{targetVersion}:{recipientAccountId}`とし、`ON CONFLICT(dedupe_key) DO NOTHING`で重複を防ぐ。監査ログには理由・総評本文を渡さない。

- [ ] **Step 7: 結合テストを再実行してPASSを確認する**

- [ ] **Step 8: ワークフローサービスをコミットする**

### Task 5: 上長・経営者・社員向けAPI

**Files:**
- Create: `backend/src/main/java/com/query/insight/evaluation/ManagerEvaluationController.java`
- Create: `backend/src/main/java/com/query/insight/evaluation/ExecutiveEvaluationController.java`
- Modify: `backend/src/main/java/com/query/insight/evaluation/EvaluationController.java`
- Test: `backend/src/test/java/com/query/insight/evaluation/EvaluationApprovalControllerIntegrationTests.java`

**Interfaces:**
- Produces: `/api/v1/manager-evaluations`、`/api/v1/executive/evaluations`、`/api/v1/evaluations/me/final-result`。

- [ ] **Step 1: MockMvcでロール別200/400/403/404/409を確認する失敗テストを書く**

```java
mvc.perform(post("/api/v1/executive/evaluations/{id}/approve", targetId)
    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_EXECUTIVE")))
    .contentType(APPLICATION_JSON).content("{\"version\":3,\"comment\":null}"))
    .andExpect(status().isOk());
```

- [ ] **Step 2: DTOへ`@Size`、項目数、点数範囲、理由必須の入力検証を定義する**

- [ ] **Step 3: `@PreAuthorize`とサービスのデータスコープ検証を二重に適用する**

- [ ] **Step 4: 対象テストを再実行してPASSを確認する**

- [ ] **Step 5: APIをコミットする**

### Task 6: ローカルデータと経営判断用の分布

**Files:**
- Modify: `backend/src/main/java/com/query/insight/config/LocalRealisticDataInitializer.java`
- Test: `backend/src/test/java/com/query/insight/auth/AuthServiceIntegrationTests.java`
- Test: `backend/src/test/java/com/query/insight/evaluation/ExecutiveDashboardIntegrationTests.java`

**Interfaces:**
- Produces: ローカル専用経営者アカウント、承認待ち・期限超過・確定済みが混在する再現可能データ。

- [ ] **Step 1: EXECUTIVEログインと全社集計値を確認する失敗テストを書く**

- [ ] **Step 2: 既存51名を壊さず、複数状態の評価版・履歴を冪等に生成する**

- [ ] **Step 3: 集計テストと全認証統合テストを実行する**

- [ ] **Step 4: ローカルデータをコミットする**

### Task 7: 上長評価フロントエンド

**Files:**
- Create: `frontend/src/pages/ManagerEvaluationsPage.tsx`
- Create: `frontend/src/pages/ManagerEvaluationDetailPage.tsx`
- Test: `frontend/src/pages/ManagerEvaluationsPage.test.tsx`
- Test: `frontend/src/pages/ManagerEvaluationDetailPage.test.tsx`
- Modify: `frontend/src/types.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/components/AppLayout.tsx`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Consumes: Task 5の上長API。
- Produces: `/evaluations/manager`、`/evaluations/manager/:publicId`。

- [ ] **Step 1: 状態絞り込み、期限警告、詳細遷移の失敗UIテストを書く**

- [ ] **Step 2: 自己・上長点数比較、差分理由必須、総評必須、提出後disabledの失敗UIテストを書く**

- [ ] **Step 3: テストを実行して画面未実装で失敗することを確認する**

- [ ] **Step 4: React Queryで一覧・詳細・更新を実装し、成功時に対象queryをinvalidateする**

- [ ] **Step 5: 既存CSS変数とカード・表・ボタンを再利用し、700px以下では1列表示にする**

- [ ] **Step 6: UIテスト、Lint、型チェックを実行する**

- [ ] **Step 7: 上長画面をコミットする**

### Task 8: 経営ダッシュボードと最終承認フロントエンド

**Files:**
- Create: `frontend/src/pages/ExecutiveDashboardPage.tsx`
- Create: `frontend/src/pages/ExecutiveEvaluationDetailPage.tsx`
- Test: `frontend/src/pages/ExecutiveDashboardPage.test.tsx`
- Test: `frontend/src/pages/ExecutiveEvaluationDetailPage.test.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/components/AppLayout.tsx`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Consumes: Task 5の経営者API。
- Produces: `/executive/evaluations`、`/executive/evaluations/:publicId`。

- [ ] **Step 1: 件数、部署別分布、フィルター、承認待ち一覧の失敗UIテストを書く**

- [ ] **Step 2: 個人差分、総評、履歴、承認・差戻し・再オープンの失敗UIテストを書く**

- [ ] **Step 3: テストを実行して失敗を確認する**

- [ ] **Step 4: 個人単位の確認を強制し、一括承認UIを設けず実装する**

- [ ] **Step 5: EXECUTIVE以外へナビゲーションを表示しない**

- [ ] **Step 6: UIテスト、Lint、型チェックを実行する**

- [ ] **Step 7: 経営画面をコミットする**

### Task 9: 社員の確定結果公開と通知導線

**Files:**
- Modify: `frontend/src/pages/EvaluationPage.tsx`
- Test: `frontend/src/pages/EvaluationPage.test.tsx`
- Modify: `frontend/src/pages/NotificationsPage.tsx`
- Test: `frontend/src/pages/NotificationsPage.test.tsx`

**Interfaces:**
- Consumes: Task 5の`final-result`とTask 4の通知リンク。
- Produces: 承認中は状態のみ、FINALIZED後は確定点・グレード・項目差分・総評を表示する社員画面。

- [ ] **Step 1: 承認前に上長内容がDOMへ存在しない失敗テストを書く**

- [ ] **Step 2: 最終承認後だけ確定結果を表示する失敗テストを書く**

- [ ] **Step 3: 通知リンクが役割別詳細画面へ遷移するテストを書く**

- [ ] **Step 4: 最小UIを実装し対象テストをPASSさせる**

- [ ] **Step 5: 社員公開と通知導線をコミットする**

### Task 10: ドキュメントと全品質ゲート

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `docs/02-requirements.md`
- Modify: `docs/03-use-cases.md`
- Modify: `docs/06-traceability.md`
- Modify: `docs/99-open-questions.md`
- Modify: `docs/superpowers/specs/2026-08-02-executive-evaluation-approval-design.md`

**Interfaces:**
- Produces: 実装済み状態、ローカルアカウント、API/UI対応、起動・確認手順。

- [ ] **Step 1: 実装状態と実在ファイル・ルートを文書へ反映する**

- [ ] **Step 2: バックエンド全テストとpackageを実行する**

```powershell
cd backend
.\mvnw.cmd test
.\mvnw.cmd package
```

- [ ] **Step 3: フロントエンド全品質ゲートを実行する**

```powershell
cd frontend
npm ci
npm run lint
npm test
npm run build
npm audit --omit=dev --audit-level=high
```

React Routerの修正版がレジストリに未公開で監査だけが失敗する場合は、使用していないunstable RSC由来であること、利用可能な安定版、失敗出力を記録し、監査ゲートを無効化しない。

- [ ] **Step 4: Dockerを再ビルドしてhealthを待つ**

```powershell
docker compose up -d --build backend frontend
docker compose ps
```

- [ ] **Step 5: 実HTTPで主要経路を確認する**

確認対象は`test`/`test`ログイン、自己評価、上長ログイン、上長下書き・差戻し・提出、経営者ログイン、個人最終承認、社員の確定結果、期限超過警告、割当外403/404、監査ログ200である。

- [ ] **Step 6: ブラウザで上長・経営者・社員の主要UIを確認する**

- [ ] **Step 7: `git diff --check`、秘密情報、生成物、依頼外差分を確認する**

- [ ] **Step 8: 最終ドキュメントと検証結果をコミットする**

```powershell
git add AGENTS.md README.md docs backend frontend
git commit -m "上長評価と経営者承認を完成"
```
