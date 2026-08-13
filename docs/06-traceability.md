# 要件・画面・実装トレーサビリティ

## 画面対応

| 画面ID | 画面 | Route | 実装状態 |
| --- | --- | --- | --- |
| SCR-001 | ログイン | `/login` | API接続済み |
| SCR-002 | 個人ダッシュボード | `/` | API接続済み |
| SCR-003 | 社員検索 | `/employees` | API接続済み |
| SCR-004 | 社員詳細 | `/employees/:publicId` | 基本情報・タレントプロフィールAPI接続済み |
| SCR-005 | 社員登録・編集 | `/employees/new`, `/employees/:publicId/edit` | API接続済み |
| SCR-006 | 業務経歴申請 | `/careers/edit`, `/talent/new/CAREER` | 承認済み一覧・申請API接続済み |
| SCR-007 | スキル・専門知識申請 | `/skills/edit`, `/talent/new/SKILL`, `/talent/new/KNOWLEDGE` | 承認済み一覧・申請API接続済み |
| SCR-008 | 資格申請 | `/certifications/edit`, `/talent/new/CERTIFICATION` | 承認済み一覧・申請API接続済み |
| SCR-009 | 自己評価 | `/evaluations/self` | 下書き・提出、最終承認後の確定結果API接続済み |
| SCR-010 | 上長評価 | `/evaluations/manager`、`/evaluations/manager/:publicId` | 一覧・比較入力・差戻し・提出API接続済み |
| EXECUTIVE | 経営ダッシュボード・最終承認 | `/executive/evaluations`、`/executive/evaluations/:publicId` | 全社集計・個人承認・差戻し・再オープンAPI接続済み |
| SCR-012 | AI分析 | `/analysis` | ローカルOllama接続済み |
| SCR-013 | 通知 | `/notifications` | 一覧・個別/一括既読・リンク遷移API接続済み |
| SCR-014 | マスタ管理 | `/masters` | 画面骨格 |
| SCR-015 | 監査ログ | `/audit` | API接続済み |
| SCR-016 | パスワード変更 | `/password/change` | 画面骨格 |
| SCR-017 | パスワード再設定 | `/password/reset` | 画面骨格 |
| SCR-018 | タレント承認ワーク | `/approvals/talent`, `/approvals/talent/:publicId` | 直属部下一覧・詳細・承認・差戻しAPI接続済み |
| TALENT-HISTORY | タレント申請履歴 | `/talent/:logicalPublicId/history` | 本人の版履歴API接続済み |
| MASTER-REQUEST | マスタ追加申請 | `/master-requests` | 本人申請・履歴API接続済み |
| SCR-019 | 評価履歴比較 | `/evaluations/history` | 画面骨格 |
| SCR-020 | 組織・所属管理 | `/organization` | 画面骨格 |
| SCR-021 | アカウント・権限管理 | `/accounts` | 画面骨格 |
| SCR-022 | 共通エラー | `/error`、未定義Route | 実装済み |
| SCR-023 | アクセス権限エラー | `/forbidden` | 実装済み |

## API実装

| 領域 | 主なエンドポイント | 状態 |
| --- | --- | --- |
| 認証 | `POST /api/v1/auth/login`, `refresh`, `logout`; `GET me` | 実装済み |
| ダッシュボード | `GET /api/v1/dashboard` | 実装済み |
| 社員 | `GET/POST /api/v1/employees`, `GET/PUT /:publicId`, `POST /:publicId/deactivate` | 実装済み |
| 自己評価 | `GET /api/v1/evaluations/self/current`, `PUT draft`, `POST submit` | 実装済み |
| 通知 | `GET /api/v1/notifications`, `PATCH /:publicId/read`, `PATCH /read-all` | 実装済み |
| 監査 | `GET /api/v1/audit-logs` | 実装済み |
| AI | `POST /api/v1/ai-analyses` | 評価・スキル・知識・経歴・資格を匿名化してローカルOllamaへ連携、JSON Schema出力・監査・結果保存を実装 |
| スキル・知識・資格・経歴 | `GET /api/v1/employees/:publicId/talent-profile`, `/api/v1/talent-submissions/**`, `/api/v1/manager/talent-submissions/**` | 申請、添付、直属上長承認、差戻し、再申請、版履歴、正式反映を実装 |
| マスタ追加申請 | `/api/v1/master-requests/**`, `/api/v1/admin/master-requests/**` | 社員申請、`ADMIN/ALL`判断、重複防止、監査、通知を実装 |
| タレント選択肢 | `GET /api/v1/talent-masters/{type}` | ACTIVEなスキル・専門知識・資格を種類別申請フォームへ提供 |
| 上長評価・経営者承認 | `/api/v1/manager-evaluations/**`、`/api/v1/executive/evaluations/**`、`GET /api/v1/evaluations/me/final-result` | 実装済み。`OFFICER/SUBORDINATES`と割当、`OFFICER/ALL`、楽観ロック、版・履歴、監査、通知を適用 |
| 組織・アカウント・マスタ | 詳細設計書の管理API | 未実装 |

## 主要要件の検証方法

| 要件 | 自動検証 | 手動検証 |
| --- | --- | --- |
| JWT/Refreshローテーション | `AuthServiceIntegrationTests` | Cookie属性、期限切れ時の自動更新 |
| パスワード入力条件 | `PasswordPolicyTests`, `AuthControllerPasswordValidationTests`, `LoginPage.test.tsx` | 半角英数字8〜128文字、英字・数字を各1文字以上、`ueno / 5050Rock` でログイン |
| 3権限・ロック廃止 | `SimplifiedRoleMigrationIntegrationTests`, `AuthServiceIntegrationTests` | 一般・役職者・管理者の導線とスコープを確認 |
| 任意添付 | `TalentWorkflowEndToEndIntegrationTests`, `TalentSubmissionPages.test.tsx` | 4種類を添付0件で提出・承認できること |
| ULID公開ID | `PublicIdGeneratorTests` | URLとAPIに数値IDが出ないこと |
| UIの代替表 | `RadarChart.test.tsx` | キーボード、狭幅、コントラスト |
| DBマイグレーション | Spring context / Flyway test | PostgreSQL 18.4で新規起動 |
| ローカル能力データ | `AuthServiceIntegrationTests` | 50名のリアリティデータ＋簡易確認1名、306評価明細・255スキル・153知識・経歴・資格が表示されること |
| AIリクエスト契約 | `OllamaAnalysisClientTests` | `qwen3:4b`取得済み環境で分析結果が表示・保存されること |
| ローカル再現性 | Docker Compose構文・起動確認 | `http://localhost:8088` の主要フロー |

## 完了判定

今回の成果物は「初期実装・設計検証用MVP」であり、詳細設計書の全76 APIを完了した本番リリース版ではない。評価承認フローは完成しているが、残る`画面骨格`・`未実装`の行は、業務未決事項の確定後にAPI、認可、監査、単体・結合・E2Eテストを追加する必要がある。

無料公開版は `Dockerfile.free` でReactとSpring Bootを統合し、`.github/workflows/ci.yml` でBackend、Frontend、統合イメージを検証する。Render・Neon上の実デプロイと外部疎通はアカウント・秘密情報の設定後に確認する。
