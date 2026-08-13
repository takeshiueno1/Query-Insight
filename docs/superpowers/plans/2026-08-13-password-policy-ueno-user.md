# Password Policy and Ueno Test User Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** パスワードを半角英数字混在の8〜128文字へ統一し、ローカル確認ユーザー `ueno / 5050Rock` でログインできるようにする。

**Architecture:** バックエンドにはコンパイル時定数の正規表現を持つ `PasswordPolicy` を追加し、ログインDTOと初期管理者設定で共有する。フロントエンドも同じ条件をZodで送信前検証する。既存の `QITEST` 社員をローカル確認ユーザーとして再利用し、社員・評価データ件数を変えずにログイン情報だけを `ueno / 5050Rock` へ置き換える。

**Tech Stack:** Java 21、Spring Boot Validation、JUnit 5、AssertJ、React 19、TypeScript 6、Zod、React Hook Form、Vitest、Testing Library

## Global Constraints

- パスワードは8〜128文字とする。
- 使用可能文字は半角英字と半角数字だけとする。
- 英字と数字をそれぞれ1文字以上必須とする。
- `ueno` は `local` プロファイルだけで作成し、`GENERAL / SELF` とする。
- 平文パスワードをDB、ログ、外部サービスへ保存・送信しない。
- JWT、Refresh Token、レート制限、Argon2id設定、現在の画面デザインを変更しない。

---

### Task 1: バックエンドのパスワード入力条件を統一する

**Files:**
- Create: `backend/src/main/java/com/query/insight/auth/PasswordPolicy.java`
- Modify: `backend/src/main/java/com/query/insight/auth/AuthController.java`
- Modify: `backend/src/main/java/com/query/insight/config/BootstrapAdminInitializer.java`
- Create: `backend/src/test/java/com/query/insight/auth/PasswordPolicyTests.java`
- Create: `backend/src/test/java/com/query/insight/auth/AuthControllerPasswordValidationTests.java`

**Interfaces:**
- Produces: `PasswordPolicy.REGEX`。ログインDTOとBootstrap設定が同じ正規表現を参照する。
- Preserves: 無効な資格情報の401応答、Argon2id照合、Refresh Token処理。

- [ ] **Step 1: パスワード境界値の失敗テストを書く**

`PasswordPolicyTests` で `5050Rock` を有効とし、`505Rock`、`RockOnly`、`50505050`、`5050#Rock`、`５０５０Rock` を無効とするテストを書く。まだ存在しない `PasswordPolicy.REGEX` を参照するためコンパイルに失敗する状態を作る。

- [ ] **Step 2: REDを確認する**

Run: `cd backend; .\mvnw.cmd -Dtest=PasswordPolicyTests test`

Expected: `PasswordPolicy` が存在しないためテストコンパイルが失敗する。

- [ ] **Step 3: 最小の共通ポリシーを実装する**

`PasswordPolicy` に次のコンパイル時定数だけを置く。

```java
public final class PasswordPolicy {
    public static final String REGEX = "^(?=.*[A-Za-z])(?=.*[0-9])[A-Za-z0-9]{8,128}$";
    private PasswordPolicy() {}
}
```

`AuthController.LoginRequest.password` に `@Pattern(regexp = PasswordPolicy.REGEX)` を追加し、`BootstrapAdminInitializer.validateConfiguration()` も `password.matches(PasswordPolicy.REGEX)` で検証する。例外メッセージは「8〜128文字の半角英数字で、英字と数字を含む」ことを示す。

- [ ] **Step 4: API入力検証テストを書く**

`AuthControllerPasswordValidationTests` でMockMvcから `/api/v1/auth/login` へ送信し、`5050Rock` は認証サービスまで到達、7文字・記号・英字のみ・数字のみ・全角を含む入力は400になることを確認する。

- [ ] **Step 5: 対象テストを成功させる**

Run: `cd backend; .\mvnw.cmd -Dtest=PasswordPolicyTests,AuthControllerPasswordValidationTests,BootstrapAdminInitializerTests test`

Expected: 全テスト成功。

- [ ] **Step 6: バックエンド変更をコミットする**

```powershell
git add -- backend/src/main/java/com/query/insight/auth/PasswordPolicy.java backend/src/main/java/com/query/insight/auth/AuthController.java backend/src/main/java/com/query/insight/config/BootstrapAdminInitializer.java backend/src/test/java/com/query/insight/auth/PasswordPolicyTests.java backend/src/test/java/com/query/insight/auth/AuthControllerPasswordValidationTests.java
git commit -m "パスワードを半角英数字8文字以上へ変更"
```

### Task 2: ローカル確認ユーザーを `ueno / 5050Rock` へ変更する

**Files:**
- Modify: `backend/src/main/java/com/query/insight/config/LocalRealisticDataInitializer.java`
- Modify: `backend/src/test/java/com/query/insight/auth/AuthServiceIntegrationTests.java`

**Interfaces:**
- Consumes: `QITEST` の既存社員・評価・タレントデータ。
- Produces: ログインID `ueno`、Argon2idで保存される生パスワード `5050Rock`、権限 `GENERAL / SELF`。

- [ ] **Step 1: 新資格情報の失敗テストを書く**

`AuthServiceIntegrationTests.localTestUserCanLoginWithRequestedCredentials` を `ueno / 5050Rock` に変更し、表示名と `GENERAL / SELF` を検証する。ロック非採用テストも対象ログインIDと成功パスワードを同じ値へ変更する。

- [ ] **Step 2: REDを確認する**

Run: `cd backend; .\mvnw.cmd -Dtest=AuthServiceIntegrationTests test`

Expected: 現行seedには `ueno` がないため認証失敗。

- [ ] **Step 3: QITESTのローカル資格情報を置き換える**

`LocalRealisticDataInitializer.ensureAccountAndRoles` で `QITEST` の場合だけログインIDを `ueno`、エンコード対象を `5050Rock` とする。既存の社員数51名、評価明細、権限付与ロジックは変更しない。

- [ ] **Step 4: 対象統合テストを成功させる**

Run: `cd backend; .\mvnw.cmd -Dtest=AuthServiceIntegrationTests test`

Expected: `ueno / 5050Rock` のログイン、`GENERAL / SELF`、既存件数・冪等性テストがすべて成功。

- [ ] **Step 5: ローカルユーザー変更をコミットする**

```powershell
git add -- backend/src/main/java/com/query/insight/config/LocalRealisticDataInitializer.java backend/src/test/java/com/query/insight/auth/AuthServiceIntegrationTests.java
git commit -m "ueno確認ユーザーをローカルデータへ追加"
```

### Task 3: ログイン画面の入力検証を8文字へ変更する

**Files:**
- Modify: `frontend/src/pages/LoginPage.tsx`
- Create: `frontend/src/pages/LoginPage.test.tsx`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `useAuth().login(loginId, password)`。
- Produces: 有効な入力だけを送信し、無効入力には「半角英数字8文字以上」の日本語エラーを表示するログイン画面。

- [ ] **Step 1: フロントエンドの失敗テストを書く**

`LoginPage.test.tsx` で認証コンテキストをモックし、`ueno / 5050Rock` は `login` を呼ぶこと、`505Rock`、`RockOnly`、`50505050`、`5050#Rock` はエラー表示となり `login` を呼ばないことを確認する。

- [ ] **Step 2: REDを確認する**

Run: `cd frontend; npm test -- LoginPage.test.tsx --maxWorkers=1`

Expected: 現行の15文字制限により `5050Rock` が拒否され、成功ケースが失敗する。

- [ ] **Step 3: Zodスキーマを変更する**

`LoginPage.tsx` のpasswordを次の条件へ変更する。

```ts
password: z.string()
  .min(8, 'パスワードは半角英数字8文字以上です')
  .max(128)
  .regex(/^(?=.*[A-Za-z])(?=.*[0-9])[A-Za-z0-9]+$/, 'パスワードは半角英数字で、英字と数字を含めてください')
```

`App.tsx` のパスワード変更画面骨格の説明も8〜128文字・半角英数字混在へ揃える。

- [ ] **Step 4: 対象フロントエンドテストを成功させる**

Run: `cd frontend; npm test -- LoginPage.test.tsx --maxWorkers=1`

Expected: 全テスト成功。

- [ ] **Step 5: フロントエンド変更をコミットする**

```powershell
git add -- frontend/src/pages/LoginPage.tsx frontend/src/pages/LoginPage.test.tsx frontend/src/App.tsx
git commit -m "ログイン画面のパスワード条件を8文字へ変更"
```

### Task 4: 現行仕様書を同期し全体検証する

**Files:**
- Modify: `README.md`
- Modify: `docs/03-use-cases.md`
- Modify: `docs/04-non-functional-requirements.md`
- Modify: `docs/06-traceability.md`
- Modify: `docs/07-free-deployment.md`

**Interfaces:**
- Consumes: Task 1〜3で確定した入力条件とローカル資格情報。
- Produces: コードと一致する運用・動作確認資料。

- [ ] **Step 1: 文書を同期する**

現行仕様の15文字記述を8〜128文字・半角英数字混在へ変更する。READMEの確認用資格情報を `ueno / 5050Rock` に変更し、ローカル専用で本番には作成されないことを明記する。トレーサビリティのローカル人数は既存の51名を維持する。

- [ ] **Step 2: 文書と差分を検査する**

Run: `rg -n "15〜128|15文字以上|test / test|ログインID.*test" README.md docs frontend/src backend/src/main --glob '!docs/superpowers/**'`

Expected: 現行仕様・画面・実装から旧条件と旧資格情報がなくなる。

Run: `git diff --check`

Expected: 終了コード0。

- [ ] **Step 3: 全体検証を実行する**

```powershell
Set-Location backend
.\mvnw.cmd package
Set-Location ..\frontend
npm ci
npm run lint
npm test -- --maxWorkers=1
npm run build
Set-Location ..
docker compose config --quiet
```

Expected: Backend全テスト、Frontend全テスト、Lint、型検査、本番ビルド、Compose定義検証がすべて成功。

- [ ] **Step 4: ローカル実動作を確認する**

Dockerが利用できない場合は、既存のH2ローカル起動手順でバックエンドとビルド済みフロントエンドを起動する。`POST /api/v1/auth/login` に `ueno / 5050Rock` を送り200、`GENERAL / SELF` を確認する。ブラウザから同じ資格情報でログインし、ダッシュボードへ遷移することを確認する。

- [ ] **Step 5: 文書変更をコミットする**

```powershell
git add -- README.md docs/03-use-cases.md docs/04-non-functional-requirements.md docs/06-traceability.md docs/07-free-deployment.md
git commit -m "パスワード要件と確認ユーザーの資料を同期"
```
