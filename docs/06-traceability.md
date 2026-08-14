# 要件・画面・実装トレーサビリティ

この文書は現在のroute、API、主要テスト、実装・実動作確認状態の正とする。画面IDは受領資料との追跡用であり、利用者画面に表示する文字列ではない。`API接続済み`、`画面骨格`、`互換用のみ`、`実HTTP確認済み`、`実ブラウザ未確認`を区別する。

## 画面対応

| 受領資料の画面ID | 現行画面 | Route | 実装状態 |
| --- | --- | --- | --- |
| SCR-001 | ログイン | `/login` | API接続済み。正式ロゴ、入力エラー、パスワード条件を実装 |
| SCR-002 | 個人ダッシュボード | `/` | `GET /api/v1/dashboard/me`、自己ステータス、公開済み上長評価、能力バランス、分析、未読件数を接続済み |
| SCR-003 | 社員検索 | `/employees` | API接続済み |
| SCR-004 | 社員詳細 | `/employees/:publicId` | 基本情報・承認済みタレントプロフィールAPI接続済み |
| SCR-005 | 社員登録・編集 | `/employees/new`, `/employees/:publicId/edit` | 登録・参照API接続済み。編集の完成範囲は要確認 |
| SCR-006 | 業務経歴 | `/careers`, `/talent/new/CAREER`, `/talent/CAREER/:publicId/edit` | 承認済み一覧・申請状態・登録・再開・履歴を接続済み。旧`/careers/edit`は一覧へ転送 |
| SCR-007 | スキル・得意分野 | `/skills`, `/talent/new/SKILL`, `/talent/new/KNOWLEDGE`, 各編集route | 承認済み一覧・申請状態・種類別登録・再開・履歴を接続済み。旧`/skills/edit`は一覧へ転送 |
| SCR-008 | 資格 | `/certifications`, `/talent/new/CERTIFICATION`, `/talent/CERTIFICATION/:publicId/edit` | 承認済み一覧・申請状態・登録・再開・履歴を接続済み。旧`/certifications/edit`は一覧へ転送 |
| SCR-009 | 本人向け上長評価 | `/evaluations/manager-result` | `FINALIZED`だけを表示。旧`/evaluations/self`はこのrouteへ転送し、本人入力は廃止済み |
| SCR-010 | 上長評価入力 | `/evaluations/manager`, `/evaluations/manager/:publicId` | 直属部下一覧、6軸S〜F入力、下書き、提出、再提出、評価基準・注意事項を接続済み |
| EXECUTIVE | 経営ダッシュボード・最終承認 | `/executive/evaluations`, `/executive/evaluations/:publicId` | 全社集計、提出済みrank、個人最終承認、上長差戻し、理由付き再オープンを接続済み |
| SCR-012 | 分析 | ダッシュボード `/`、互換route `/analysis` | ダッシュボードへAI/PROTOTYPEを接続済み。直接routeは残存するが主要メニューには表示しない |
| SCR-013 | 通知 | `/notifications` | 一覧、未読件数、個別/一括既読、リンク遷移を接続済み |
| SCR-014 | マスタ管理 | `/masters` | 画面骨格 |
| SCR-015 | 監査ログ | `/audit` | API接続済み、`ADMIN`メニュー |
| SCR-016 | パスワード変更 | `/password/change` | 画面骨格 |
| SCR-017 | パスワード再設定 | `/password/reset` | 画面骨格 |
| SCR-018 | タレント承認 | `/approvals/talent`, `/approvals/talent/:publicId` | 直属部下一覧・詳細・CLEAN添付・承認・差戻しを接続済み |
| TALENT-HISTORY | タレント申請履歴 | `/talent/:logicalPublicId/history` | 本人の版履歴API接続済み |
| MASTER-REQUEST | マスタ追加申請 | `/master-requests` | 種類・説明の2項目、本人履歴、`ADMIN/ALL`判断を接続済み |
| SCR-019 | 評価履歴比較 | `/evaluations/history` | 画面骨格。上長評価版・snapshot・workflow eventのDB/API保持は実装済み |
| SCR-020 | 組織・所属管理 | `/organization` | 画面骨格 |
| SCR-021 | アカウント・権限管理 | `/accounts` | 画面骨格 |
| SCR-022 | 共通エラー | `/error`、未定義Route | 実装済み |
| SCR-023 | アクセス権限エラー | `/forbidden` | 実装済み |

## API実装

| 領域 | 主なエンドポイント | 状態・公開境界 |
| --- | --- | --- |
| 認証 | `POST /api/v1/auth/login`, `/refresh`, `/logout`; `GET /api/v1/auth/me` | 実装済み。短寿命JWT、Refreshローテーション、再利用検知、送信元rate limit |
| ダッシュボード | `GET /api/v1/dashboard/me` | JWT本人だけ。自己ステータス、公開済み上長評価、未読件数を返す |
| 社員 | `GET/POST /api/v1/employees`, `GET/PUT /:publicId`, `POST /:publicId/deactivate` | 実装済み。roleとscopeを検証 |
| 旧本人評価 | `GET/PUT /api/v1/evaluations/me`, `POST /api/v1/evaluations/me/submit` | 既存データ互換用。現行UIからは利用しない |
| 本人向け確定上長評価 | `GET /api/v1/evaluations/me/final-result` | `FINALIZED`だけrank/comment/summary/finalizedAtを返し、未確定は404。scoreや旧自己入力値は返さない |
| 上長評価 | `GET/PUT /api/v1/manager-evaluations/**`, `POST .../submit` | `OFFICER/SUBORDINATES`、DB grant、割当、target versionを検証。6軸rank入力 |
| 廃止済み本人差戻し | `POST /api/v1/manager-evaluations/{targetPublicId}/return` | 認証・scope・割当・version検査後に410を返し、状態を変更しない |
| 最終承認 | `GET/POST /api/v1/executive/evaluations/**` | `OFFICER/ALL`だけ。最終承認、上長差戻し、再オープン、全社・部署別rank分布 |
| 通知 | `GET /api/v1/notifications`, `/unread-count`; `PATCH /:publicId/read`, `/read-all` | 本人アカウント単位で分離し、既読化後に同じquery prefixを更新 |
| 監査 | `GET /api/v1/audit-logs` | `ADMIN`向け。traceIdを含む |
| 分析 | `POST /api/v1/ai-analyses` | 承認済み情報と公開済み評価だけを匿名化。Ollama成功時AI、利用不能時PROTOTYPEを保存・監査 |
| タレントプロフィール・申請 | `GET /api/v1/employees/{publicId}/talent-profile`, `/api/v1/talent-submissions/**`, `/api/v1/manager/talent-submissions/**` | 申請、直属上長承認、差戻し、再申請、版履歴、正式反映、自己ステータス再計算を実装 |
| 添付 | `POST /api/v1/talent-submissions/{publicId}/attachments`, `GET/DELETE /api/v1/talent-attachments/{publicId}` | owner/scope、形式・サイズ・件数、PENDING/CLEAN/INFECTED/ERROR、再検査、CLEANのみ取得を実装 |
| タレント選択肢 | `GET /api/v1/talent-masters/{type}` | ACTIVEなスキル・得意分野・資格を種類別申請フォームへ提供 |
| マスタ追加申請 | `POST /api/v1/master-requests`, `GET /me`, `/api/v1/admin/master-requests/**` | 新形式は種類・説明だけを保存し、承認してもマスタ非生成。旧形式だけ互換生成を維持 |
| 組織・アカウント・マスタ | 詳細設計書の管理API | 未実装 |

## 主要要件の自動検証

| 要件 | 主なBackend検証 | 主なFrontend検証 |
| --- | --- | --- |
| JWT/Refresh/パスワード | `AuthServiceIntegrationTests`, `PasswordPolicyTests`, `AuthControllerPasswordValidationTests` | `LoginPage.test.tsx` |
| 3権限とscope | `SimplifiedRoleMigrationIntegrationTests`, `EvaluationApprovalControllerIntegrationTests`, `TalentProfileApprovalVisibilityIntegrationTests` | `AppLayout.test.tsx`, `ApprovalPages.test.tsx` |
| 自己ステータス式・境界・冪等性 | `ProfileStatusCalculatorTests`, `ProfileStatusServiceIntegrationTests`, `ProfileStatusSchemaIntegrationTests` | `DashboardPage.test.tsx`, `RadarChart.test.tsx` |
| 上長S〜F評価・競合・公開 | `EvaluationRankTests`, `EvaluationWorkflowTests`, `EvaluationWorkflowServiceIntegrationTests`, `EvaluationApprovalControllerIntegrationTests`, `ExecutiveDashboardIntegrationTests` | `ApprovalPages.test.tsx`, `FinalManagerEvaluationPage.test.tsx` |
| ダッシュボード本人境界 | `DashboardControllerIntegrationTests`, `DashboardNotificationFailureIntegrationTests` | `DashboardPage.test.tsx` |
| タレント申請・承認・正式反映 | `TalentWorkflowEndToEndIntegrationTests`, `TalentSubmissionServiceIntegrationTests`, `ManagerTalentSubmissionIntegrationTests` | `TalentCategoryPages.test.tsx`, `TalentSubmissionPages.test.tsx`, `ManagerTalentApprovalPages.test.tsx` |
| 添付検査と並行状態 | `TalentAttachmentServiceIntegrationTests`, `TalentAttachmentControllerIntegrationTests`, `ClamAvFileScanClientTests` | `TalentSubmissionPages.test.tsx` |
| AI/PROTOTYPEとPII・判断禁止 | `OllamaAnalysisClientTests`, `AiAnalysisFallbackIntegrationTests`, `PrototypeAnalysisServiceTests` | `DashboardPage.test.tsx` |
| 通知count・既読同期 | `NotificationCountIntegrationTests` | `AppLayout.test.tsx`, `NotificationsPage.test.tsx` |
| マスタ申請2項目・旧互換 | `MasterRequestIntegrationTests` | `MasterRequestsPage.test.tsx` |
| Flyway V1〜V9・V8/V9 | `EvaluationApprovalSchemaIntegrationTests`, `ProfileStatusSchemaIntegrationTests`, `TalentSubmissionSchemaIntegrationTests` | - |

## 現在確認されている利用者向け旧表記・互換コード

次は2026-08-14の静的検索で残存を確認した項目であり、解消済みとは扱わない。

- `frontend/src/pages/AiAnalysisPage.tsx` の直接route `/analysis`、`AuditPage.tsx` の `/audit`、社員系画面および`FeaturePage`利用routeには、受領資料の画面IDをeyebrow表示する実装が残る。
- `frontend/src/pages/TalentSubmissionFormPage.tsx` はスキル・得意分野の入力ラベルに「習熟度（1～5）」を表示する。
- `frontend/src/pages/EvaluationPage.tsx` は旧本人入力文言を含むが、`App.tsx`からはmountされず、`/evaluations/self`は本人向け確定結果へ転送される。
- `frontend/src/pages/TalentProfilePage.tsx` は旧統合プロフィール表記を含むが、現行の一般メニューは`/skills`、`/careers`、`/certifications`を使用する。
- `backend/src/main/java/com/query/insight/config/LocalDataInitializer.java` がlocal sampleへ旧本人評価の通知文言を登録するため、一般・直属上長の`/notifications`から到達できる。
- `backend/src/main/java/com/query/insight/config/LocalRealisticDataInitializer.java` は旧本人評価のlevel/evidenceを互換データとして生成する。APIの上長・経営詳細には旧fieldが残るが、現行の`ManagerEvaluationDetailPage`と`ExecutiveEvaluationDetailPage`は描画しない。

これらの製品コード修正はTask 11の文書・runtime担当範囲に含めず、別の修正taskでroute到達性と期待文言を確定してから変更する。

## 実動作と残る確認gate

- 2026-08-14のTask 11静的検証は、Backend 190件、Frontend 13 files・108件、lint、build、`npm audit --audit-level=moderate`（脆弱性0件）、Compose config、diff check、差分秘密情報scanが成功した。
- Docker Desktop daemonはWSLの`HCS_E_CONNECTION_TIMEOUT`と`docker-desktop-data`異常終了によりAPI 500のまま復旧せず、Compose/PostgreSQL/ClamAV/Ollamaは起動できなかった。volume・image・既存データの削除やWSL全体停止は行っていない。したがってPostgreSQLでのFlyway V1〜V9、V8/V9、添付`SKIP LOCKED`、上長評価並行競合、ClamAV、Ollama成功modeは未確認である。
- worktreeのH2代替runtimeではFlyway V1〜V9を適用し、health、3権限のlogin、本人dashboard/profile/通知/4種類申請、上長一覧・詳細、経営一覧・確定詳細、確定本人結果をHTTP確認した。AI無効時の保存結果は`PROTOTYPE`だった。これはPostgreSQL/Compose実動作の代替証明ではない。
- malformed requestの`GET /api/v1/talent-submissions/me`（必須`type`なし）が400ではなく500 `INTERNAL_ERROR`になることを確認した。現行Frontendは常に`type`を付けるため通常導線は通るが、別の製品修正対象である。
- 実ブラウザの視覚・操作確認は未実施である。主担当がdesktop/390px、loading/error、基本キーボードfocus、水平overflow、glass fallback、一般・直属上長・全社役職者の通し操作を確認するまで、ブラウザ確認済みとしない。
- 本MVPは詳細設計書の全76 APIを完了した本番リリース版ではない。上表の`画面骨格`・`未実装`と、[未決事項](99-open-questions.md)に残る本番運用条件を別途完了する必要がある。
