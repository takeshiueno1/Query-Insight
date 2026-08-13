# Query Insight

社員のスキル、経歴、自己評価・上長評価を一元管理し、根拠と履歴に基づく人材育成を支援する社内Webアプリケーションです。提供された要件定義書・画面設計書・詳細設計書を `docs/source/original/` に保管し、解析結果と実装範囲を `docs/` に整理しています。

## 実装済みの初期成果物

- JWT認証、Refresh Tokenローテーション、3権限（一般・役職者・管理者）とデータスコープ認可
- ダッシュボード、社員検索・詳細・登録
- 自己評価の下書き保存・提出（楽観ロック）
- 割当上長による項目別評価、差分理由、総評、社員への差戻し、社長への提出
- `OFFICER/ALL`による全社・部門別分布、個人最終承認、上長への差戻し、理由付き再オープン
- 最終承認後だけ社員へ確定スコア・等級・上長評価を公開
- 通知一覧、監査ログ一覧
- スキル・専門知識・業務経歴・資格の種類別フォーム、下書き、任意の根拠資料、直属上長への申請、差戻し、再申請、版管理
- 直属上長によるタレント申請の1件承認と、承認済み正式プロフィールへの原子的な反映
- スキル・資格マスタの追加申請と、管理者による承認・差戻し
- PDF/JPEG/PNGの実体検証、5MB・3件制限、ClamAV検査、閲覧スコープ制御
- SCR-001〜SCR-023のルーティングと、設計書に沿ったダークネイビー／イエローのレスポンシブUI
- PostgreSQL向けFlywayスキーマ、50名分の組織・6能力軸・スキル・知識・業務経歴・資格を含むローカル専用サンプルデータ
- OllamaとQwen3によるローカル能力分析（API利用料・外部送信なし）
- 総当たり・AI過負荷のレート制限、Token再利用検知、CSP、Origin検証、依存関係監査

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

前提: Docker Desktop と Docker Compose。初回だけOllamaモデル約2.5GBとClamAV定義をダウンロードします。

```powershell
Copy-Item .env.example .env
docker compose up -d db ollama
docker compose exec ollama ollama pull qwen3:4b
docker compose up --build backend frontend
```

`backend`起動時に依存する`clamav`も自動起動します。定義更新後にhealthcheckが成功するまで、初回は数分かかる場合があります。

ブラウザで <http://localhost:8088> を開きます。`local` プロファイル専用の確認用アカウントは次のとおりです。

| 権限 | ログインID | パスワード |
| --- | --- | --- |
| 管理者 | `admin@query.local` | `QueryInsight2026` |
| 役職者（直属部下） | `manager@query.local` | `QueryInsight2026` |
| 一般 | `employee@query.local` | `QueryInsight2026` |
| 一般（簡易動作確認） | `ueno` | `5050Rock` |
| 役職者（全社・社長） | `qi0039@query.local` | `QueryInsight2026` |

これらはローカル用の架空データです。50名のリアリティデータに加え、簡易動作確認専用の1名を登録しています。承認待ち・上長差戻し・確定済みの評価例も含みます。`QI0006`～`QI0050` は `qi0006@query.local` のように社員番号を小文字にしたログインIDと同じローカルパスワードで確認できます。`ueno` はローカル専用アカウントであり、本番環境には作成されません。詳細は[50名リアリティデータ](docs/08-realistic-sample-data.md)を参照してください。本番環境では `local` プロファイルを使用せず、RSA秘密鍵・公開鍵を `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` で設定してください。

## ローカルAI分析

AI分析は既定でOllamaを使用します。モデルと社員情報はDocker Desktop上のローカル環境から外部AIサービスへ送信されず、APIキーや従量課金もありません。

```dotenv
AI_ENABLED=true
AI_PROVIDER=ollama
OLLAMA_MODEL=qwen3:4b
```

```powershell
docker compose exec ollama ollama pull qwen3:4b
docker compose up -d --build --force-recreate backend frontend
```

`AI分析` 画面から実行できます。AI入力は評価期間、能力軸、スキル、専門知識、直近の業務経験、確認済み資格に限定し、氏名・メールアドレス・社員番号・部署名・公開IDを含めません。分析結果は人事判断の自動決定には使用せず、本人が確認する育成助言として扱います。CPU実行では初回分析に数分かかる場合があります。

本番でもOllamaは利用できますが、アプリとは別にOllama対応サーバーを常時稼働させ、バックアップ、監視、アクセス制御、必要なCPU・メモリまたはGPUを用意する必要があります。Renderの小規模な無料Web ServiceへAIモデルを同居させる構成は対象外です。

## 無料公開版

無料公開版はReactをSpring Bootの実行可能JARへ同梱し、内蔵Tomcatから画面とAPIを同一オリジンで配信します。Render無料Web ServiceとNeon無料PostgreSQLを対象とし、GitHub Actionsの検査成功後だけ自動デプロイします。

ローカルで無料公開版と同じ統合イメージを確認する場合は、次を実行します。

```powershell
docker compose -f compose.yml -f compose.free.yml up --build db free-app
```

ブラウザで <http://localhost:8090> を開きます。Render・Neonの作成、秘密情報の登録、初期管理者設定は[無料公開版デプロイ手順](docs/07-free-deployment.md)を参照してください。Render無料公開版ではOllamaを収容できないためAI分析を無効化し、実在社員の個人情報・評価情報を登録しないでください。

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
- [セキュリティ対策](docs/09-security-measures.md)
- [未決事項](docs/99-open-questions.md)

開発時は [AGENTS.md](AGENTS.md) のルールを優先してください。
