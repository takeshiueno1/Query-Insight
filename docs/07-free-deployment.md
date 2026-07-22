# 無料公開版デプロイ

## 1. 構成と用途

無料公開版は、Reactの静的ファイルとSpring Boot REST APIを1つの実行可能JARへ同梱し、Spring Boot内蔵Tomcatから同一オリジンで配信する。

```text
GitHub Actions CI -> Render Web Service -> Neon PostgreSQL
                         |
                         +-- Spring Boot / embedded Tomcat
                             +-- React static assets
                             +-- REST API
```

Render無料Web Serviceはアイドル時に停止し、Neon無料DBにも容量・計算時間の制約がある。正式な可用性保証が必要な環境や、実在社員の評価・個人情報を扱う環境には使用しない。無料公開版では架空データまたは利用許可済みの検証データだけを扱う。

## 2. ローカルで統合イメージを確認する

既存のローカルPostgreSQLと、無料公開版と同じ統合Dockerイメージを使用する。

```powershell
Copy-Item .env.example .env
docker compose -f compose.yml -f compose.free.yml up --build db free-app
```

ブラウザで <http://localhost:8090> を開く。`local` プロファイルの架空アカウントはREADME記載のものを使用する。終了時は次を実行する。

```powershell
docker compose -f compose.yml -f compose.free.yml down
```

DBボリュームを削除する場合に限り、影響確認後に `down -v` を使用する。

## 3. Neon PostgreSQLを作成する

1. Neonで無料プロジェクトとPostgreSQLデータベースを作成する。
2. Renderに近いリージョンを選択する。
3. 接続情報を次の3項目へ分けて控える。

| Render環境変数 | 値 |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://<host>/<database>?sslmode=require` |
| `DB_USER` | Neonのユーザー名 |
| `DB_PASSWORD` | Neonのパスワード |

接続文字列やパスワードはGitHub、ソースコード、ログへ保存しない。

## 4. JWT署名鍵を生成する

ローカル専用の一時鍵は無料公開版で使用しない。次の例で2048-bit RSA鍵を生成し、秘密鍵と公開鍵の内容をRenderのSecretへ登録する。

```powershell
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
```

| Render環境変数 | 値 |
| --- | --- |
| `JWT_PRIVATE_KEY` | `jwt-private.pem` のPEM全文 |
| `JWT_PUBLIC_KEY` | `jwt-public.pem` のPEM全文 |

生成した秘密鍵ファイルはGit管理外の安全な場所へ移し、Renderへの登録後もアクセス権を制限する。

## 5. Render Blueprintを作成する

1. RenderでNew Blueprintを選択する。
2. GitHubリポジトリ `takeshiueno1/Query-Insight` を接続する。
3. ルートの `render.yaml` を使用する。
4. Blueprint作成時に`sync: false`の環境変数を入力する。

| 環境変数 | 初回値・説明 |
| --- | --- |
| `AUTH_ALLOWED_ORIGINS` | `https://<Renderのサービス名>.onrender.com` |
| `BOOTSTRAP_ADMIN_ENABLED` | 初回だけ `true`。初期管理者作成後に `false` へ変更 |
| `BOOTSTRAP_ADMIN_LOGIN_ID` | 初期管理者のメール形式ログインID |
| `BOOTSTRAP_ADMIN_PASSWORD` | 15〜128文字のランダムな初期パスワード |
| `BOOTSTRAP_ADMIN_EMPLOYEE_NO` | 30文字以下の一意な社員番号 |
| `BOOTSTRAP_ADMIN_LAST_NAME` | 50文字以下 |
| `BOOTSTRAP_ADMIN_FIRST_NAME` | 50文字以下 |

初期管理者はDBが空の場合だけ作成される。作成後は `BOOTSTRAP_ADMIN_ENABLED=false` とする。パスワード変更画面は現時点で画面骨格のため、正式公開前にAPIを完成させる必要がある。

## 6. CIと自動デプロイ

`.github/workflows/ci.yml` はpushとPull Requestで次を検証する。

- BackendテストとJAR作成
- Frontend Lint、テスト、ビルド
- 無料公開版の統合Dockerイメージ作成

`render.yaml` の `autoDeployTrigger: checksPass` により、Renderへ接続したブランチのGitHub Checksがすべて成功した場合だけ自動デプロイされる。AWS認証情報やRender Deploy Hookは使用しない。

## 7. AI分析

ローカル版はOllamaと`qwen3:4b`を使用し、API利用料と外部AIサービスへのデータ送信を発生させない。Render無料Web ServiceはAIモデルを動かすためのメモリ・計算資源を前提にできないため、無料公開版では `AI_ENABLED=false` に固定する。

本番でAI分析を有効にする場合は、Ollamaを社内または専用サーバーで常時稼働させ、アプリから閉域または認証済み経路で接続する。インターネットへOllama APIのポートを直接公開しない。必要なCPU・メモリまたはGPU、同時実行数、監視、バックアップ、障害時の縮退動作を設計してから有効化する。

## 8. ロールバック

Renderの直近デプロイ履歴から以前の成功デプロイへ戻す。DBマイグレーションはFlywayによる前進適用のため、破壊的変更を含めず、必要な場合は互換性を保つ修正マイグレーションを追加する。DBデータを手動削除してロールバックしない。
