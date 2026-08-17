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
| 添付 | `POST/GET /api/v1/talent-submissions/{publicId}/attachments`, `GET/DELETE /api/v1/talent-attachments/{publicId}` | owner/scope、形式・サイズ・件数、PENDING/CLEAN/INFECTED/ERROR、再検査を実装。本人は編集中申請の既存添付と検査状態を確認・削除でき、CLEANのみ内容取得可能 |
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
| local通知のrecipient・権限scope・遷移先 | `LocalNotificationInitializerIntegrationTests` | `NotificationsPage.test.tsx` |
| マスタ申請2項目・旧互換 | `MasterRequestIntegrationTests` | `MasterRequestsPage.test.tsx` |
| 必須query parameterの構造化400 | `GlobalExceptionHandlerTests`, `TalentSubmissionControllerIntegrationTests` | - |
| 到達可能画面の日本語見出し・習熟状況ラベル | - | `AppUserVisibleLabels.test.tsx`, `TalentCategoryPages.test.tsx`, `TalentSubmissionPages.test.tsx`, `ManagerTalentApprovalPages.test.tsx` |
| Flyway V1〜V9・V8/V9 | `EvaluationApprovalSchemaIntegrationTests`, `ProfileStatusSchemaIntegrationTests`, `TalentSubmissionSchemaIntegrationTests` | - |

## 利用者向け旧表記の修正状況と互換コード

2026-08-14の製品修正round 1で、現行`App.tsx`から到達できる画面とlocal sampleを再確認した。

- `frontend/src/pages/AiAnalysisPage.tsx`、`AuditPage.tsx`、社員系画面、`NotificationsPage.tsx`、`FeaturePage.tsx`利用routeの利用者向け画面IDを、用途を表す日本語見出しへ置換した。`AppUserVisibleLabels.test.tsx`は実際のmount routeで`SCR-*`非表示を検証する。
- `frontend/src/pages/TalentSubmissionFormPage.tsx`のスキル・得意分野入力は「習熟状況」とし、`学習中・基礎・自立・高度・指導`を内部値1〜5へ対応付ける。申請詳細・承認詳細・プロフィールも同じ日本語ラベルを表示し、上長評価のS〜Fとは別の尺度として扱う。プロフィールでは自然語を可視テキストにし、5本のbarは`aria-hidden`の装飾とする。未知値は内部数値を表示せず「未設定」とする。
- `frontend/src/pages/EvaluationPage.tsx` は旧本人入力文言を含むが、`App.tsx`からはmountされず、`/evaluations/self`は本人向け確定結果へ転送される。
- `frontend/src/pages/TalentProfilePage.tsx` は旧統合プロフィール表記を含むが、現行の一般メニューは`/skills`、`/careers`、`/certifications`を使用する。
- `backend/src/main/java/com/query/insight/config/LocalDataInitializer.java`の旧本人評価通知seedは削除した。`LocalRealisticDataInitializer.java`は一般本人の確定結果、直属上長の最終承認差戻し、全社役職者の最終承認対象を、各recipientのrole/scopeに合うroute付きでseedし、旧2件のdedupe keyを局所削除する。
- `backend/src/main/java/com/query/insight/config/LocalRealisticDataInitializer.java` は旧本人評価のlevel/evidenceを互換データとして生成するが、現行の上長・経営向け公開APIはscore/finalScore/selfLevel/selfEvidenceを返さない。

未mountの`EvaluationPage.tsx`と`TalentProfilePage.tsx`は互換コードとして残す。現行routeから到達不能であり、削除の影響範囲を本修正だけでは確定できないため削除していない。

## 実動作と残る確認gate

- 2026-08-14の実ブラウザ追加確認で発見した在籍状態、タレント分類、監査ログの内部コード表示を日本語化した。Frontend 14 files・134件、lint、buildが成功した。対象テストは35件が成功し、配布物の実ブラウザでも`ACTIVE`、`ENGINEERING`、`AUTH_LOGIN`等が表示されないことを確認した。
- 2026-08-14の最終レビュー修正では、最終承認時の自己ステータスsnapshot再計算、評価APIの内部数値・旧本人値非公開、AI匿名化のUnicode大小文字対応、編集中申請の既存添付一覧・削除、重複・同時承認の409化、利用者別申請・添付cache分離を実装した。focused Backend 52件・申請関連Frontend 50件、全Backend 197件・全Frontend 137件、lint、build、依存監査が成功した。PostgreSQL実DBの並行ロック挙動はDocker環境障害のため未確認で、H2では一方承認・他方409・副作用各1件を確認した。
- 2026-08-14の製品修正round 2では、Frontend 14 files・130件、lint、buildが成功した。関連4 files・63件は同一commandを3回連続実行して成功した。
- 2026-08-14の製品修正round 1では、Backend 194件、Frontend 14 files・124件、Frontend lint・buildが成功した。追加したfocused検証はBackend 7件、Frontend 52件が成功した。
- 2026-08-14のTask 11静的検証は、Backend 190件、Frontend 13 files・108件、lint、build、`npm audit --audit-level=moderate`（脆弱性0件）、Compose config、diff check、差分秘密情報scanが成功した。
- Docker Desktop daemonはWSLの`HCS_E_CONNECTION_TIMEOUT`と`docker-desktop-data`異常終了によりAPI 500のまま復旧せず、Compose/PostgreSQL/ClamAV/Ollamaは起動できなかった。volume・image・既存データの削除やWSL全体停止は行っていない。したがってPostgreSQLでのFlyway V1〜V9、V8/V9、添付`SKIP LOCKED`、上長評価並行競合、ClamAV、Ollama成功modeは未確認である。
- worktreeのH2代替runtimeではFlyway V1〜V9を適用し、health、3権限のlogin、本人dashboard/profile/通知/4種類申請、上長一覧・詳細、経営一覧・確定詳細、確定本人結果をHTTP確認した。AI無効時の保存結果は`PROTOTYPE`だった。これはPostgreSQL/Compose実動作の代替証明ではない。
- `GET /api/v1/talent-submissions/me`の必須`type`欠落は、`MissingServletRequestParameterException`だけを捕捉して`VALIDATION_ERROR`、field `type`、code `required`の構造化400を返すよう修正した。予期しない例外の500処理は維持する。`TalentSubmissionControllerIntegrationTests`で実endpoint、`GlobalExceptionHandlerTests`で共通required query処理を検証する。
- H2代替runtimeの実ブラウザでは、desktop/390px、水平overflowなし、glass表示、一般・直属上長・全社役職者・管理者の主要画面を確認した。上長評価1件を6軸Bで保存・提出し、全社役職者が最終承認、本人へランク・コメントだけを公開、通知と監査履歴を生成する通し操作まで成功した。AIは`PROTOTYPE`表示で実行できた。基本キーボードfocusはブラウザ自動操作側でTab移動を再現できず、手動確認を残す。
- 本MVPは詳細設計書の全76 APIを完了した本番リリース版ではない。上表の`画面骨格`・`未実装`と、[未決事項](99-open-questions.md)に残る本番運用条件を別途完了する必要がある。
