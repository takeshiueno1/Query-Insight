# Query Insight

社員のスキル、経歴、自己評価・上長評価を一元管理し、根拠と履歴に基づく人材育成を支援する社内Webアプリケーションです。提供された要件定義書・画面設計書・詳細設計書を `docs/source/original/` に保管し、解析結果と実装範囲を `docs/` に整理しています。

## 実装済みの初期成果物

- JWT認証、Refresh Tokenローテーション、アカウントロック、RBAC
- ダッシュボード、社員検索・詳細・登録
- 自己評価の下書き保存・提出（楽観ロック）
- 通知一覧、監査ログ一覧
- SCR-001〜SCR-023のルーティングと、設計書に沿ったダークネイビー／イエローのレスポンシブUI
- PostgreSQL向けFlywayスキーマ、50名分の組織・6能力軸・スキル・知識・業務経歴・資格を含むローカル専用サンプルデータ
- OpenAI Responses APIによる能力分析（APIキー設定時のみ有効）

画面骨格のみの機能と残作業は [トレーサビリティ](docs/06-traceability.md) を参照してください。

## 技術構成

| 区分 | 構成 |
| --- | --- |
| Frontend | React 19.2、TypeScript 6、Vite 8、TanStack Query、React Hook Form、Zod |
| Backend | Java 21、Spring Boot 4.1、Spring Security、Spring JDBC、Flyway |
| Database | PostgreSQL 18.4 |
| Local runtime | Docker Compose |
| Free preview | Render Web Service、Neon PostgreSQL |

## ローカル起動

前提: Docker Desktop と Docker Compose。

```powershell
Copy-Item .env.example .env
docker compose up --build
```

ブラウザで <http://localhost:8088> を開きます。`local` プロファイル専用の確認用アカウントは次のとおりです。

| 権限 | ログインID | パスワード |
| --- | --- | --- |
| 管理者 | `admin@query.local` | `QueryInsight#2026` |
| 上長 | `manager@query.local` | `QueryInsight#2026` |
| 社員 | `employee@query.local` | `QueryInsight#2026` |

これらはローカル用の架空データです。50名全員に提出済みの6能力軸、スキル、専門知識、業務経歴、資格を登録しています。`QI0006`～`QI0050` は `qi0006@query.local` のように社員番号を小文字にしたログインIDと同じローカルパスワードで確認できます。詳細は[50名リアリティデータ](docs/08-realistic-sample-data.md)を参照してください。本番環境では `local` プロファイルを使用せず、RSA秘密鍵・公開鍵を `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` で設定してください。

## AI分析の有効化

AI分析は外部送信を伴うため既定では無効です。ローカルの `.env` に次を設定し、コンテナを再作成してください。ChatGPTの契約とは別に、OpenAI APIで利用できるAPIキーと課金設定が必要です。

```dotenv
AI_ENABLED=true
OPENAI_API_KEY=your-api-key
OPENAI_MODEL=gpt-5.6-sol
```

```powershell
docker compose up -d --build --force-recreate backend
```

`AI分析` 画面から実行できます。送信対象は評価期間、能力軸、スキル、専門知識、直近の業務経験、確認済み資格に限定し、氏名・メールアドレス・社員番号は送信しません。APIキーは `.env` にのみ保存し、Gitへコミットしないでください。分析結果は人事判断の自動決定には使用せず、本人が確認する育成助言として扱います。

## 無料公開版

無料公開版はReactをSpring Bootの実行可能JARへ同梱し、内蔵Tomcatから画面とAPIを同一オリジンで配信します。Render無料Web ServiceとNeon無料PostgreSQLを対象とし、GitHub Actionsの検査成功後だけ自動デプロイします。

ローカルで無料公開版と同じ統合イメージを確認する場合は、次を実行します。

```powershell
docker compose -f compose.yml -f compose.free.yml up --build db free-app
```

ブラウザで <http://localhost:8090> を開きます。Render・Neonの作成、秘密情報の登録、初期管理者設定は[無料公開版デプロイ手順](docs/07-free-deployment.md)を参照してください。無料公開版ではAI分析を無効化し、実在社員の個人情報・評価情報を登録しないでください。

停止:

```powershell
docker compose down
```

DBデータも削除する場合のみ、影響を確認して `docker compose down -v` を実行します。

## 開発・テスト

```powershell
# Backend
cd backend
.\mvnw.cmd test

# Frontend
cd ..\frontend
npm ci
npm run lint
npm test
npm run build
```

## ドキュメント

- [ドキュメントガイド](docs/README.md)
- [解析レポート](docs/05-analysis-report.md)
- [要件・画面・実装トレーサビリティ](docs/06-traceability.md)
- [無料公開版デプロイ手順](docs/07-free-deployment.md)
- [50名リアリティデータ](docs/08-realistic-sample-data.md)
- [未決事項](docs/99-open-questions.md)

開発時は [AGENTS.md](AGENTS.md) のルールを優先してください。
