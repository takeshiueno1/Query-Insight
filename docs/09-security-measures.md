# セキュリティ対策

## 1. 方針

追加費用や外部セキュリティサービスを前提にせず、アプリケーション、Docker、CIで実施できる多層防御を採用する。無料公開版は検証用途であり、実在社員の個人情報・評価情報を保存する正式本番環境とはしない。

## 2. 実装済み対策

| 対象リスク | 対策 | 実装方法 |
| --- | --- | --- |
| パスワード漏えい | Argon2idで不可逆ハッシュ化 | 19MiB、2 iterations、parallelism 1。平文をDB・ログへ保存しない |
| ログイン総当たり | アカウントロックと送信元単位の回数制限 | 5回失敗で15分ロック。ログインは既定10回/分、パスワード再設定は3回/10分 |
| アカウント存在確認 | 認証失敗メッセージと処理時間を近付ける | 未登録IDでもダミーArgon2検証を行い、同じ401応答を返す |
| Token窃取・再利用 | 短寿命JWTとRefresh Tokenローテーション | Access Token 15分、Refresh Token 8時間。Refresh TokenはSHA-256ハッシュだけをDB保存 |
| Refresh Token競合・再利用 | 行ロックとToken系列の無効化 | `SELECT ... FOR UPDATE`で同時更新を直列化し、使用済みTokenの再利用時は同じ系列を無効化する |
| CSRF・ログインCSRF | Cookie属性と送信元検証 | HttpOnly、SameSite=Strict、本番Secure。Login/Refresh/Logout/Password ResetでOriginとFetch Metadataを検証 |
| 不正アクセス | RBACとデータスコープ | Controllerの認証だけでなくService/APIで本人・上長・人事・管理者の参照範囲を再検証 |
| XSS・クリックジャッキング | Reactエスケープとブラウザ防御ヘッダー | CSP、`frame-ancestors 'none'`、X-Frame-Options DENY、nosniff、Referrer-Policy、Permissions-Policy、COOP、CORP |
| SQLインジェクション | パラメータ化SQL | Spring `JdbcClient`の名前付きパラメータを使用し、外部入力をSQL文字列へ連結しない |
| 大量リクエスト | API回数制限 | Refreshは30回/分。上限超過は429と`Retry-After`を返す。保持キー数も10,000件に制限 |
| AIによるCPU枯渇 | 回数・同時実行制限 | 1アカウント3回/10分、全体同時実行1件。Ollamaポートは`127.0.0.1`だけに公開 |
| AIへの命令注入 | 入力を非信頼データとして扱うSystem Prompt | 評価根拠内の命令に従わず、JSON Schemaで出力形式を限定・再検証する |
| 個人情報の外部送信 | ローカルOllama | 氏名等をAI入力から除外し、外部AI APIを使用しない |
| エラーからの情報漏えい | Problem Detailsと例外詳細の非公開 | Stack Trace、Binding Error、内部例外メッセージをHTTP応答へ含めず、相関IDだけを返す |
| 管理情報の露出 | Actuator最小公開 | readiness/livenessを含むhealthだけを公開し、info・metrics等はHTTP公開しない |
| コンテナ侵害 | 最小公開・非root・権限制限 | Backendと無料統合イメージは非root。主要コンテナはread-only、no-new-privileges、不要Capability削除。DBは非公開、Ollamaはloopback限定 |
| 依存関係・CI改ざん | ロックファイル、監査、Action SHA固定 | `npm ci`、本番依存の`npm audit`、GitHub Actionsをcommit SHAで固定、Dependabotを週次設定 |
| 操作否認・改ざん調査 | 監査ログと相関ID | 認証・更新・評価・AI操作をtraceId付きで記録し、監査レコードをハッシュ連鎖する |
| 危険な添付ファイル | 実体検証とClamAV検査 | PDF/JPEG/PNGのmagic bytes、5MB・3件上限を検証し、CLEANになるまで取得不可。感染ファイル本体は消去する |
| タレント情報の過剰公開 | 用途別の参照認可 | 本人、現在の直属上長、有効なEXECUTIVE/ALLだけを許可し、SYSTEM_ADMIN・AUDITORには他者の内容を公開しない |
| 未承認情報の利用 | 正式テーブルとの分離 | 申請payloadと正式プロフィールを分離し、プロフィール・検索・AI入力は承認済み正式テーブルだけを参照する |

## 3. 既定のレート制限

| 操作 | 既定値 | 主な設定 |
| --- | --- | --- |
| ログイン | 10回/1分/送信元 | `LOGIN_RATE_LIMIT_MAX`、`LOGIN_RATE_LIMIT_WINDOW` |
| Token更新 | 30回/1分/送信元 | `REFRESH_RATE_LIMIT_MAX`、`REFRESH_RATE_LIMIT_WINDOW` |
| パスワード再設定要求 | 3回/10分/送信元 | `PASSWORD_RESET_RATE_LIMIT_MAX`、`PASSWORD_RESET_RATE_LIMIT_WINDOW` |
| AI分析 | 3回/10分/アカウント | `AI_RATE_LIMIT_MAX`、`AI_RATE_LIMIT_WINDOW` |
| AI同時実行 | システム全体で1件 | `AI_MAX_CONCURRENT` |

回数制限は単一インスタンス内のメモリで管理する。現在のローカル版とRender無料版の単一インスタンス構成には適合するが、複数インスタンス化する場合はRedisなどの共有ストアへ移行する。

## 4. 運用時の確認

```powershell
# 自動テストとビルド
cd backend
.\mvnw.cmd test
cd ..\frontend
npm ci
npm audit --omit=dev --audit-level=high
npm run lint
npm test
npm run build

# 公開ポートとコンテナ状態
cd ..
docker compose ps
```

本番では`local`プロファイルを使用せず、JWT秘密鍵・公開鍵をSecretとして設定する。TLSは公開基盤で終端し、`AUTH_SECURE_COOKIE=true`と正確な`AUTH_ALLOWED_ORIGINS`を必須とする。秘密鍵、DBパスワード、接続文字列をGitHubやログへ保存しない。

## 5. 無料対策だけでは解決しない事項

- MFA/SSO、集中ログ監視、WAF、EDR、第三者脆弱性診断は未導入。
- DBバックアップ、暗号鍵ローテーション、インシデント対応手順、監査ログ保持期間は正式決定が必要。
- メモリ型レート制限は再起動でリセットされ、複数インスタンス間では共有されない。
- ローカルHTTPはTLS化していない。外部公開時は必ずHTTPSを使用する。
- Render・Neon無料枠は可用性保証や正式な個人情報運用を前提にしない。無料公開版は架空データ限定とする。
