# 非機能要件

設計・実装で確認できた要件と、正式決定または実環境検証が必要な値を分けて記載する。

## 性能・容量

- 検索APIはページングし、無制限一覧取得を禁止する。
- DB検索列と一意キーに索引を設定し、一覧でのN+1を避ける。
- AI分析は1アカウント3回/10分、全体同時実行1件とする。現行MVPは同期HTTPで、Ollama timeout既定値は5分である。非同期ジョブ化、進捗照会、取消は未実装である。
- 添付検査はscanner呼出しをDB transaction外で行う。再検査workerは1行ずつ短いtransactionでclaimし、`FOR UPDATE SKIP LOCKED`とleaseで多重実行を抑える。
- API応答SLO、同時利用者数、想定社員数・評価明細数、添付再検査worker数、Ollama推奨CPU・メモリ・GPUは未決である。

## 可用性・信頼性

- 更新処理はトランザクション境界を明示し、評価・申請は楽観ロックで競合を409として検出する。
- 自己ステータスsnapshotは入力fingerprintと算出方式で一意化し、同じ入力の再計算を冪等にする。
- 添付uploadはPENDING行と申請versionを先にcommitし、scan結果を期待状態付きで確定する。ERROR/INFECTEDをCLEANへ誤昇格させず、submitは非CLEAN添付を拒否する。
- 通知は遷移単位のdedupe keyを持ち、上長評価・承認等の再送で同じ通知を増やさない。
- コンテナはhealthcheckを公開し、DB・Ollama・ClamAVの依存が正常になってからbackend、backendがhealthyになってからfrontendを開始する。
- Ollamaが無効、未起動、タイムアウトまたは接続不能の場合は、通常エラーで画面を止めずPROTOTYPEへ切り替える。構造不正・人事判断表現・設定不整合はfallbackせず制御エラーとする。
- 稼働率、RPO、RTO、バックアップ頻度、冗長化方式、災害対策リージョンは未決である。

## セキュリティ・プライバシー

| 項目 | 要件・実装方針 |
| --- | --- |
| パスワード | 英字・数字を各1文字以上含む半角英数字8〜128文字、Argon2id（19MiB、2 iterations、parallelism 1） |
| ロック | アカウントロックは使用せず、認証失敗理由を統一して送信元単位のレート制限を適用する |
| レート制限 | ログイン10回/分、Refresh 30回/分、パスワード再設定3回/10分、AI 3回/10分/アカウント。上限時は429を返す |
| Token | RS256、Access 15分、Refresh 8時間、ローテーションと再利用検知 |
| Cookie | HttpOnly、SameSite=Strict、本番Secure、認証API配下にPath制限 |
| 認可 | `GENERAL`、`OFFICER`、`ADMIN`とSELF/SUBORDINATES/ALL、本人・割当上長をサーバー側で評価する。`ADMIN`だけを理由に他社員のタレント・評価内容を公開しない |
| CSRF | SameSite Cookieに加え、Login/Refresh/Logout/Password ResetのOrigin・Fetch Metadataを検証する |
| 入力 | Bean Validation、列挙値・長さ・範囲、SQLプレースホルダー |
| 出力 | Reactの既定エスケープ、CSP、frame-ancestors拒否。内部JSON、数値ID、本人向け上長評価scoreを公開しない |
| 添付 | PDF/JPEG/PNGのmagic bytes、5MB・3件、CLEANのみ取得可能、INFECTED本体消去、owner/scope検証 |
| 監査 | 認証、更新、提出、承認、AI/PROTOTYPEをtraceId付きで記録する |
| 秘密情報 | `.env`、秘密鍵、Token、確認用資格情報をGit・ログ・追加文書へ出さない |
| AI | ローカルOllamaを使用し、氏名、メール、社員番号、部署、ログインID、公開IDを除去する。構造検証と人事判断表現の拒否を行う |

本番前にプロキシ信頼境界、TLS終端、鍵ローテーション、共有レート制限、脆弱性検査を環境設計と合わせて確定する。実装済み対策は[セキュリティ対策](09-security-measures.md)を参照する。

## 保守性・運用性

- Flywayでスキーマを前進適用し、破壊的変更はexpand/contract方式とする。現行migrationはV1〜V9である。
- V8は評価score列を`DECIMAL(5,2)`へ拡張し、V9は明細がある旧提出・確定版だけをS〜F尺度へ再計算する。純DRAFTと明細なし行は既存値を保持する。
- RFC 9457形式のエラーと相関IDを用い、利用者向け詳細と内部例外を分離する。
- Actuatorはhealthだけを公開し、詳細・info・metricsをHTTP公開しない。
- 構造化ログ、監視メトリクス、アラート閾値、保持期間、個人情報マスキング規則は未決である。

## 互換性・アクセシビリティ

- PCを主対象とし、700px以下ではサイドバーを下部ナビゲーションへ再配置する。主要gridは狭幅で1列にする。
- 正式ロゴ、日本語メニュー・パンくず、暗いnavyと黄色accent、半透明カードを使用し、`backdrop-filter`非対応時は不透明背景へfallbackする。
- フォームラベル、キーボード操作、フォーカス表示、コントラストを確保する。読込中・空状態は`role=status`、失敗は`role=alert`で区別する。
- レーダーチャートには表形式の代替情報を提供する。
- コンポーネントテストは狭幅を意識したDOM/CSS構造とARIA状態を検証するが、390pxの実ブラウザ、水平overflow、glass fallback、基本キーボード操作は別の視覚・操作確認gateである。
- 対象ブラウザ、WCAG適合レベル、表示倍率の受け入れ条件は未決である。

## 検証ゲート

- Backend全テスト、Frontendの直列全テスト・Lint・TypeScript/Vite buildを実行する。
- Frontend依存は`npm audit --audit-level=moderate`で確認し、moderate以上の残存を件数とadvisoryで記録する。強制更新は互換性確認なしに実行しない。
- `docker compose config --quiet`、差分・秘密情報scan、Docker health、PostgreSQL上のFlyway、ClamAV、実HTTPを静的テストと分けて記録する。
- 実ブラウザ確認を行っていない場合、DOM testやHTTP 200だけで視覚・操作確認済みとしない。

## データ保持・法令・契約

監査ログ、評価履歴、退職者データ、添付証跡の保持・削除期間、開示権限、AI/メール/クラウド事業者との契約条件は未決である。決定前に本番データを外部サービスへ送信しない。

無料公開版は可用性保証、永続容量、バックアップ、サポートに制約があるため、架空データまたは利用許可済みの検証データに限定する。実在社員の個人情報・評価情報を扱う正式環境は、契約、保存地域、暗号化、バックアップ、RPO/RTO、監視、インシデント対応を承認した有償基盤で構成する。
