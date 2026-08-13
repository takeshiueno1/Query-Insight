# ダッシュボード・タレント情報・上長評価刷新 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 承認済みタレント情報から編集不可の自己ステータスを算出し、S/A/B/C/D/Fの上長評価、AI代替分析、通知、種類別登録導線、QUERY INSIGHTロゴ、控えめなガラス調UIを一体として提供する。

**Architecture:** 既存のタレント承認・評価版・監査・通知を保持し、自己ステータス計算を独立したドメインサービスへ追加する。上長評価はAPI境界でランク列挙値を扱い、既存DBの詳細レベルはS=5、A=4、B=3、C=2、D=1、F=0として保存する。ダッシュボードを自己ステータス、確定上長評価、分析の集約APIとし、React側は権限別メニューと用途別画面へ再構成する。

**Tech Stack:** Java 21、Spring Boot 4.1、Spring Security、Spring JDBC、Flyway、PostgreSQL 18.4、React 19.2、TypeScript 6、TanStack Query、React Hook Form、Zod、Vitest、Vite 8

## Global Constraints

- 権限は `GENERAL`、`OFFICER`、`ADMIN`、データスコープは `SELF`、`SUBORDINATES`、`ALL` をAPIで検証する。
- 自己ステータスは本人編集不可で、承認済みデータだけを使用する。
- 自己ステータスの重みはスキル40％、得意分野20％、業務経歴25％、資格15％とする。
- ランク境界はS 90以上、A 80以上、B 70以上、C 60以上、D 50以上、F 50未満とする。
- 上長評価は6軸をS/A/B/C/D/Fで入力し、全社スコープを持つ役職者の最終承認後だけ本人へ公開する。
- PDF/JPEG/PNGの根拠資料は任意とし、既存の検査・件数・サイズ・スコープ制御を維持する。
- AIは人事判断やランクを決定せず、氏名、メール、社員番号、部署名、公開IDを入力へ含めない。
- Ollama利用不能時はルールベースの結果を「プロトタイプ分析」と明示する。
- 利用者向け画面に画面ID、英語パス、内部状態コード、数値評価を表示しない。
- 外観は暗色・黄色アクセントを保った控えめなガラス調デザインAとする。
- 新しい外部依存関係を追加しない。
- 既存の公開ID、楽観ロック、版履歴、監査ログ、通知を維持し、履歴データを削除しない。

---

## File Structure

### Backend additions

- `backend/src/main/resources/db/migration/V7__profile_status_and_rank_evaluation.sql`: 自己ステータススナップショット、Fを含む6段階、簡略マスタ申請の互換migration。
- `backend/src/main/java/com/query/insight/status/ProfileStatusCalculator.java`: 副作用なしの点数・ランク計算。
- `backend/src/main/java/com/query/insight/status/ProfileStatusService.java`: 承認済みデータ集計、スナップショット保存、本人参照。
- `backend/src/main/java/com/query/insight/evaluation/EvaluationRank.java`: ランクとDBレベル・基準点の一元変換。
- `backend/src/main/java/com/query/insight/analysis/PrototypeAnalysisService.java`: Ollama非依存の強み・不足・推奨行動生成。

### Backend modifications

- `DashboardController.java`: 自己ステータス、確定上長評価、分析を集約する。
- `TalentSubmissionService.java`: 承認後の自己ステータス再計算を呼ぶ。
- `EvaluationWorkflow.java`, `EvaluationWorkflowService.java`: 自己評価に依存しない上長評価、ランク契約、公開スナップショット。
- `AiAnalysisService.java`, `AiAnalysisController.java`: 通常分析からプロトタイプ分析への安全な切替。
- `MasterRequestController.java`, `MasterRequestService.java`: 種類と説明の2項目契約。
- `NotificationController.java`, `NotificationService.java`: 未読件数と上長操作通知の一貫性。

### Frontend additions

- `frontend/src/assets/query-insight-logo.png`: ユーザー提供QUERYロゴを基にした透明背景の正式アセット。
- `frontend/src/components/BrandLogo.tsx`: ログイン・サイドバー共用ロゴ。
- `frontend/src/components/NotificationBadge.tsx`: 0非表示、1〜99、99+の未読数表示。
- `frontend/src/components/AnalysisPanel.tsx`: ダッシュボード内分析UI。
- `frontend/src/components/RankBadge.tsx`: S/A/B/C/D/Fの共通表示。
- `frontend/src/pages/SkillsPage.tsx`: スキルと得意分野の一覧・種類別登録導線。
- `frontend/src/pages/CareersPage.tsx`: 業務経歴専用画面。
- `frontend/src/pages/CertificationsPage.tsx`: 資格専用画面。
- `frontend/src/pages/FinalManagerEvaluationPage.tsx`: 一般ユーザー向け確定上長評価。

### Frontend modifications

- `App.tsx`, `AppLayout.tsx`, `LoginPage.tsx`, `DashboardPage.tsx`, `ManagerEvaluationDetailPage.tsx`, `MasterRequestsPage.tsx`, `TalentSubmissionFormPage.tsx`, `types.ts`, `styles.css`。

---

### Task 1: DB契約を安全に拡張する

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__profile_status_and_rank_evaluation.sql`
- Create: `backend/src/test/java/com/query/insight/status/ProfileStatusSchemaIntegrationTests.java`
- Modify: `backend/src/test/java/com/query/insight/evaluation/EvaluationApprovalSchemaIntegrationTests.java`
- Modify: `backend/src/test/java/com/query/insight/master/MasterRequestIntegrationTests.java`

**Interfaces:**
- Produces: `profile_status_snapshots`、`manager_evaluations.profile_status_snapshot_id`、0〜5の `manager_evaluation_details.level`、`master_addition_requests.request_type/request_description`。
- Consumes: V1〜V6の既存schemaと履歴データ。

- [ ] **Step 1: migrationの期待を表す失敗テストを書く**

```java
@Test
void profileStatusSnapshotPreservesFormulaAndSourceFingerprint() {
    var columns = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_name='profile_status_snapshots'")
            .query(String.class).list();
    assertThat(columns).contains("skill_score", "knowledge_score", "career_score",
            "certification_score", "total_score", "grade", "formula_version", "source_fingerprint");
}

@Test
void managerEvaluationLevelAllowsFAndRejectsOutOfRange() {
    assertThatCode(() -> insertManagerDetail(0)).doesNotThrowAnyException();
    assertThatThrownBy(() -> insertManagerDetail(6)).isInstanceOf(DataIntegrityViolationException.class);
}
```

- [ ] **Step 2: 対象テストを実行し、V7未作成で失敗することを確認する**

Run: `cd backend; .\mvnw.cmd -Dtest=ProfileStatusSchemaIntegrationTests,EvaluationApprovalSchemaIntegrationTests,MasterRequestIntegrationTests test`

Expected: `profile_status_snapshots` または追加列が存在せずFAIL。

- [ ] **Step 3: V7 migrationを実装する**

```sql
CREATE TABLE profile_status_snapshots (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  employee_id BIGINT NOT NULL,
  skill_score DECIMAL(5,2) NOT NULL,
  knowledge_score DECIMAL(5,2) NOT NULL,
  career_score DECIMAL(5,2) NOT NULL,
  certification_score DECIMAL(5,2) NOT NULL,
  total_score DECIMAL(5,2) NOT NULL,
  grade VARCHAR(1) NOT NULL,
  formula_version VARCHAR(30) NOT NULL,
  source_fingerprint CHAR(64) NOT NULL,
  calculated_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT chk_profile_status_grade CHECK (grade IN ('S','A','B','C','D','F')),
  CONSTRAINT chk_profile_status_scores CHECK (
    skill_score BETWEEN 0 AND 100 AND knowledge_score BETWEEN 0 AND 100
    AND career_score BETWEEN 0 AND 100 AND certification_score BETWEEN 0 AND 100
    AND total_score BETWEEN 0 AND 100),
  CONSTRAINT fk_profile_status_employee FOREIGN KEY (employee_id) REFERENCES employees(id),
  CONSTRAINT uq_profile_status_source UNIQUE (employee_id, formula_version, source_fingerprint)
);
CREATE INDEX idx_profile_status_employee_time ON profile_status_snapshots(employee_id, calculated_at DESC, id DESC);

ALTER TABLE manager_evaluations ADD COLUMN profile_status_snapshot_id BIGINT NULL;
ALTER TABLE manager_evaluations ADD CONSTRAINT fk_manager_profile_status
  FOREIGN KEY (profile_status_snapshot_id) REFERENCES profile_status_snapshots(id);

ALTER TABLE manager_evaluation_details DROP CONSTRAINT chk_manager_level;
ALTER TABLE manager_evaluation_details ADD CONSTRAINT chk_manager_level CHECK (level BETWEEN 0 AND 5);

ALTER TABLE master_addition_requests ADD COLUMN request_type VARCHAR(100) NULL;
ALTER TABLE master_addition_requests ADD COLUMN request_description VARCHAR(1000) NULL;
UPDATE master_addition_requests
SET request_type=master_type,
    request_description=COALESCE(CAST(proposed_payload_json AS VARCHAR),'')
WHERE request_type IS NULL;
ALTER TABLE master_addition_requests ALTER COLUMN request_type SET NOT NULL;
ALTER TABLE master_addition_requests ALTER COLUMN request_description SET NOT NULL;
ALTER TABLE master_addition_requests ALTER COLUMN master_type DROP NOT NULL;
ALTER TABLE master_addition_requests ALTER COLUMN proposed_payload_json DROP NOT NULL;
```

- [ ] **Step 4: 空DB migrationとschemaテストを通す**

Run: `cd backend; .\mvnw.cmd -Dtest=ProfileStatusSchemaIntegrationTests,EvaluationApprovalSchemaIntegrationTests,MasterRequestIntegrationTests test`

Expected: PASS。V1〜V7が順番に適用され、既存申請が消えない。

- [ ] **Step 5: Task 1をコミットする**

```powershell
git add backend/src/main/resources/db/migration/V7__profile_status_and_rank_evaluation.sql backend/src/test/java/com/query/insight/status/ProfileStatusSchemaIntegrationTests.java backend/src/test/java/com/query/insight/evaluation/EvaluationApprovalSchemaIntegrationTests.java backend/src/test/java/com/query/insight/master/MasterRequestIntegrationTests.java
git commit -m "自己ステータスとランク評価のDB契約を追加"
```

### Task 2: 自己ステータス計算を純粋関数として実装する

**Files:**
- Create: `backend/src/main/java/com/query/insight/status/ProfileStatusCalculator.java`
- Create: `backend/src/test/java/com/query/insight/status/ProfileStatusCalculatorTests.java`

**Interfaces:**
- Produces: `ProfileStatusCalculator.calculate(Input): Result`、`ProfileStatusCalculator.grade(BigDecimal): String`。
- Consumes: 習熟度一覧、重複排除済み経験月数、有効資格数。

- [ ] **Step 1: 境界値と配点の失敗テストを書く**

```java
@ParameterizedTest
@CsvSource({"90,S", "89.999,A", "80,A", "70,B", "60,C", "50,D", "49.999,F"})
void gradeUsesUnroundedScore(String score, String grade) {
    assertThat(ProfileStatusCalculator.grade(new BigDecimal(score))).isEqualTo(grade);
}

@Test
void calculatesApprovedProfileWeightsAndCaps() {
    var result = ProfileStatusCalculator.calculate(new Input(List.of(5, 3), List.of(4), 180, 7));
    assertThat(result.skillScore()).isEqualByComparingTo("80.00");
    assertThat(result.knowledgeScore()).isEqualByComparingTo("80.00");
    assertThat(result.careerScore()).isEqualByComparingTo("100.00");
    assertThat(result.certificationScore()).isEqualByComparingTo("100.00");
    assertThat(result.totalScore()).isEqualByComparingTo("88.00");
    assertThat(result.grade()).isEqualTo("A");
}
```

- [ ] **Step 2: テストを実行し、クラス未作成で失敗することを確認する**

Run: `cd backend; .\mvnw.cmd -Dtest=ProfileStatusCalculatorTests test`

Expected: コンパイルFAIL。

- [ ] **Step 3: BigDecimalで計算を実装する**

```java
public final class ProfileStatusCalculator {
    public static final String FORMULA_VERSION = "PROFILE_STATUS_V1";
    public static Result calculate(Input input) {
        BigDecimal skill = averageLevel(input.skillLevels());
        BigDecimal knowledge = averageLevel(input.knowledgeLevels());
        BigDecimal career = BigDecimal.valueOf(Math.min(input.careerMonths(), 120))
                .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(120), 8, HALF_UP);
        BigDecimal certification = BigDecimal.valueOf(Math.min(input.validCertificationCount(), 5) * 20L);
        BigDecimal total = skill.multiply(new BigDecimal("0.40"))
                .add(knowledge.multiply(new BigDecimal("0.20")))
                .add(career.multiply(new BigDecimal("0.25")))
                .add(certification.multiply(new BigDecimal("0.15")));
        return new Result(scale(skill), scale(knowledge), scale(career), scale(certification), scale(total), grade(total));
    }
}
```

`averageLevel` は空リストを0、各値1〜5以外を `IllegalArgumentException` とし、`平均 / 5 × 100` を計算する。表示用scaleは小数第2位、ランク判定はscale前のtotalを使用する。

- [ ] **Step 4: 計算テストを通す**

Run: `cd backend; .\mvnw.cmd -Dtest=ProfileStatusCalculatorTests test`

Expected: 全ケースPASS。

- [ ] **Step 5: Task 2をコミットする**

```powershell
git add backend/src/main/java/com/query/insight/status/ProfileStatusCalculator.java backend/src/test/java/com/query/insight/status/ProfileStatusCalculatorTests.java
git commit -m "承認済み情報の自己ステータス計算を追加"
```

### Task 3: 承認済み情報から自己ステータスを保存・参照する

**Files:**
- Create: `backend/src/main/java/com/query/insight/status/ProfileStatusService.java`
- Create: `backend/src/test/java/com/query/insight/status/ProfileStatusServiceIntegrationTests.java`
- Modify: `backend/src/main/java/com/query/insight/talent/TalentSubmissionService.java`
- Modify: `backend/src/test/java/com/query/insight/talent/TalentWorkflowEndToEndIntegrationTests.java`

**Interfaces:**
- Produces: `ProfileStatusService.currentForEmployee(String employeePublicId): ProfileStatusResponse`、`recalculate(long employeeId): Snapshot`。
- Consumes: Task 1 schema、Task 2 calculator、正式反映済み `employee_skills`、`employee_knowledge`、`career_histories`、`employee_certifications`。

```java
public record ProfileStatusResponse(String publicId, BigDecimal skillScore, BigDecimal knowledgeScore,
        BigDecimal careerScore, BigDecimal certificationScore, BigDecimal totalScore, String grade,
        List<String> missingCategories, String formulaVersion, Instant calculatedAt, boolean editable) {}
public record Snapshot(long id, String publicId, long employeeId, ProfileStatusCalculator.Result result,
        String sourceFingerprint, Instant calculatedAt) {}
```

- [ ] **Step 1: 申請中は不反映、承認後だけ反映する失敗テストを書く**

```java
@Test
void approvedTalentChangesProfileStatusButSubmittedTalentDoesNot() {
    var before = status.currentForEmployee(employeePublicId);
    submitSkill(level5Payload);
    assertThat(status.currentForEmployee(employeePublicId)).isEqualTo(before);
    approveSubmittedSkill();
    assertThat(status.currentForEmployee(employeePublicId).skillScore()).isGreaterThan(before.skillScore());
}
```

経験期間はSQLで全行を取得し、Java側で日付区間をマージして重複月を二重加算しないケースも追加する。期限切れ資格を除外し、期限なし資格を含める。

- [ ] **Step 2: 対象テストがサービス未作成で失敗することを確認する**

Run: `cd backend; .\mvnw.cmd -Dtest=ProfileStatusServiceIntegrationTests,TalentWorkflowEndToEndIntegrationTests test`

Expected: コンパイルFAIL。

- [ ] **Step 3: 集計・fingerprint・冪等保存を実装する**

```java
@Transactional
public Snapshot recalculate(long employeeId) {
    Source source = approvedSource(employeeId, LocalDate.now(clock));
    Result result = ProfileStatusCalculator.calculate(source.toInput());
    String fingerprint = Hashing.sha256(objectMapper.writeValueAsString(source));
    return findByFingerprint(employeeId, fingerprint)
            .orElseGet(() -> insertSnapshot(employeeId, result, fingerprint, clock.instant()));
}

public ProfileStatusResponse currentForEmployee(String employeePublicId) {
    long employeeId = requireEmployee(employeePublicId);
    return toResponse(latest(employeeId).orElseGet(() -> recalculate(employeeId)));
}
```

`TalentSubmissionService.approve` が正式テーブルへ反映し終えた後、同一トランザクション内で `profileStatusService.recalculate(employeeId)` を呼ぶ。差戻し・下書き保存・提出では呼ばない。

- [ ] **Step 4: サービスとE2Eテストを通す**

Run: `cd backend; .\mvnw.cmd -Dtest=ProfileStatusServiceIntegrationTests,TalentWorkflowEndToEndIntegrationTests test`

Expected: PASS。並行した同一fingerprintは一意制約を捕捉して既存snapshotを返す。

- [ ] **Step 5: Task 3をコミットする**

```powershell
git add backend/src/main/java/com/query/insight/status backend/src/main/java/com/query/insight/talent/TalentSubmissionService.java backend/src/test/java/com/query/insight/status backend/src/test/java/com/query/insight/talent/TalentWorkflowEndToEndIntegrationTests.java
git commit -m "承認後に自己ステータスを更新"
```

### Task 4: 上長評価をS/A/B/C/D/F契約へ変更する

**Files:**
- Create: `backend/src/main/java/com/query/insight/evaluation/EvaluationRank.java`
- Create: `backend/src/test/java/com/query/insight/evaluation/EvaluationRankTests.java`
- Modify: `backend/src/main/java/com/query/insight/evaluation/EvaluationWorkflow.java`
- Modify: `backend/src/main/java/com/query/insight/evaluation/EvaluationWorkflowService.java`
- Modify: `backend/src/test/java/com/query/insight/evaluation/EvaluationWorkflowTests.java`
- Modify: `backend/src/test/java/com/query/insight/evaluation/EvaluationWorkflowServiceIntegrationTests.java`
- Modify: `backend/src/test/java/com/query/insight/evaluation/EvaluationApprovalControllerIntegrationTests.java`

**Interfaces:**
- Produces: `EvaluationRank.fromCode(String)`、`level()`、`score()`、`overall(List<EvaluationRank>)`。Manager APIのdetailは `rank` を受け、応答も `managerRank`/`finalRank` を返す。
- Consumes: Task 1の0〜5制約、Task 3の最新profile snapshot。

```java
public record ManagerDetailInput(String axisCode, String rank, String comment) {}
public record FinalDetail(String axisCode, String displayName, String managerRank, String comment) {}
public record FinalResult(String status, String finalRank, String summary,
        List<FinalDetail> details, Instant finalizedAt) {}
```

- [ ] **Step 1: ランク変換・認可・非公開の失敗テストを書く**

```java
@ParameterizedTest
@CsvSource({"S,5,100", "A,4,85", "B,3,75", "C,2,65", "D,1,55", "F,0,0"})
void mapsRankToStoredLevelAndScore(String code, int level, int score) {
    var rank = EvaluationRank.fromCode(code);
    assertThat(rank.level()).isEqualTo(level);
    assertThat(rank.score()).isEqualByComparingTo(String.valueOf(score));
}

@Test
void generalUserCannotReadSubmittedManagerDraft() {
    submitManagerEvaluation();
    getFinalResultAsEmployee().andExpect(status().isNotFound());
}
```

自己評価未提出の `DRAFT` targetから役職者が保存開始できること、直属部下以外は403、最終承認後だけ本人へ公開、Fを含む総合ランク、初回並行保存409を追加する。

- [ ] **Step 2: 現行数値・自己評価依存契約で失敗することを確認する**

Run: `cd backend; .\mvnw.cmd -Dtest=EvaluationRankTests,EvaluationWorkflowTests,EvaluationWorkflowServiceIntegrationTests,EvaluationApprovalControllerIntegrationTests test`

Expected: rank未定義またはDRAFT→MANAGER_IN_PROGRESS不可でFAIL。

- [ ] **Step 3: ランク列挙と状態遷移を実装する**

```java
public enum EvaluationRank {
    S(5, "100"), A(4, "85"), B(3, "75"), C(2, "65"), D(1, "55"), F(0, "0");
    public static EvaluationRank overall(List<EvaluationRank> ranks) {
        BigDecimal average = ranks.stream().map(EvaluationRank::score)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(ranks.size()), 8, RoundingMode.HALF_UP);
        return fromProfileBoundary(average);
    }
}
```

`MANAGER_SAVE` は `DRAFT`、`MANAGER_IN_PROGRESS`、`MANAGER_RETURNED` から `MANAGER_IN_PROGRESS` へ遷移可能にする。`ManagerDetailInput` を `String rank, String comment` とし、保存時に `EvaluationRank.level()` をDBへ保存する。自己評価との差分コメント必須判定を削除し、各軸コメントと総評の必須・最大長を検証する。新しいmanager evaluation作成時にTask 3の最新snapshot idを保存する。

- [ ] **Step 4: 最終結果をランクだけで公開する**

`FinalResult` から本人向け `finalScore`、`selfLevel`、`managerLevel` を除き、`finalRank`、`details[].managerRank`、コメント、総評、確定日時を返す。管理・経営集計に必要な内部scoreはサーバー内とDBに保持してよいが、一般ユーザーAPIへ出さない。

- [ ] **Step 5: 評価回帰テストを通す**

Run: `cd backend; .\mvnw.cmd -Dtest=EvaluationRankTests,EvaluationWorkflowTests,EvaluationWorkflowServiceIntegrationTests,EvaluationApprovalControllerIntegrationTests,ExecutiveDashboardIntegrationTests test`

Expected: PASS。競合は409、スコープ違反は403、未確定本人参照は404。

- [ ] **Step 6: Task 4をコミットする**

```powershell
git add backend/src/main/java/com/query/insight/evaluation backend/src/test/java/com/query/insight/evaluation
git commit -m "上長評価を6段階ランクへ変更"
```

### Task 5: 通知件数とプロトタイプ分析を実装する

**Files:**
- Create: `backend/src/main/java/com/query/insight/analysis/PrototypeAnalysisService.java`
- Create: `backend/src/test/java/com/query/insight/analysis/PrototypeAnalysisServiceTests.java`
- Modify: `backend/src/main/java/com/query/insight/analysis/AiAnalysisService.java`
- Modify: `backend/src/main/java/com/query/insight/analysis/AiAnalysisController.java`
- Create: `backend/src/test/java/com/query/insight/analysis/AiAnalysisFallbackIntegrationTests.java`
- Modify: `backend/src/main/java/com/query/insight/notification/NotificationController.java`
- Modify: `backend/src/main/java/com/query/insight/notification/NotificationService.java`
- Create: `backend/src/test/java/com/query/insight/notification/NotificationCountIntegrationTests.java`

**Interfaces:**
- Produces: `PrototypeAnalysisService.analyze(ProfileStatusResponse, TalentProfileInput): AnalysisResponse`、`GET /api/v1/notifications/unread-count -> { unreadCount }`、AnalysisResponseの `analysisMode: AI | PROTOTYPE`。
- Consumes: Task 3自己ステータス、既存匿名化talent input、既存通知テーブル。

- [ ] **Step 1: fallbackと通知件数の失敗テストを書く**

```java
@Test
void ollamaUnavailableReturnsPrototypeAnalysis() {
    when(aiClient.analyze(any())).thenThrow(new ResourceAccessException("offline"));
    var result = service.analyze(accountPublicId);
    assertThat(result.analysisMode()).isEqualTo("PROTOTYPE");
    assertThat(result.model()).isEqualTo("ルールベース V1");
}

@Test
void unreadCountTracksReadState() {
    assertThat(getUnreadCount()).isEqualTo(2);
    markOneRead();
    assertThat(getUnreadCount()).isEqualTo(1);
}
```

- [ ] **Step 2: 現行のAIエラー・件数API未実装で失敗することを確認する**

Run: `cd backend; .\mvnw.cmd -Dtest=PrototypeAnalysisServiceTests,AiAnalysisFallbackIntegrationTests,NotificationCountIntegrationTests test`

Expected: fallback responseまたはendpointがなくFAIL。

- [ ] **Step 3: 決定的なルールベース分析を実装する**

```java
public AnalysisResponse analyze(ProfileStatusResponse status, TalentProfileInput profile) {
    var strengths = topTwoNonZero(status);
    var growthAreas = zeroOrLowestTwo(status);
    var actions = actionsFor(growthAreas, profile);
    return new AnalysisResponse(PublicIdGenerator.next(), "現在", summary(status),
            strengths, growthAreas, actions, "ルールベース V1", "PROTOTYPE", clock.instant());
}
```

Ollamaが無効、接続不能、タイムアウトの場合だけfallbackする。JSON Schema違反など応答内容の破損は隠さず日本語エラーにする。入力ログに個人識別子を出さない。

- [ ] **Step 4: 未読数APIと上長操作通知を実装する**

`NotificationService.unreadCount(accountPublicId)` は本人accountだけを検索する。タレント承認/差戻し、評価最終承認、評価再オープンの既存通知を維持し、dedupe keyを版番号込みにする。

- [ ] **Step 5: 対象テストを通す**

Run: `cd backend; .\mvnw.cmd -Dtest=PrototypeAnalysisServiceTests,AiAnalysisFallbackIntegrationTests,NotificationCountIntegrationTests test`

Expected: PASS。

- [ ] **Step 6: Task 5をコミットする**

```powershell
git add backend/src/main/java/com/query/insight/analysis backend/src/main/java/com/query/insight/notification backend/src/test/java/com/query/insight/analysis backend/src/test/java/com/query/insight/notification
git commit -m "AI代替分析と通知件数を追加"
```

### Task 6: マスタ申請を種類・説明へ簡略化する

**Files:**
- Modify: `backend/src/main/java/com/query/insight/master/MasterRequestController.java`
- Modify: `backend/src/main/java/com/query/insight/master/MasterRequestService.java`
- Modify: `backend/src/test/java/com/query/insight/master/MasterRequestIntegrationTests.java`
- Modify: `frontend/src/pages/MasterRequestsPage.tsx`
- Create: `frontend/src/pages/MasterRequestsPage.test.tsx`
- Modify: `frontend/src/types.ts`

**Interfaces:**
- Produces: `POST /api/v1/master-requests { type: string, description: string }`、`MasterRequest { type, description, status, ... }`。
- Consumes: Task 1の互換列。管理者の承認・差戻し・監査・通知は維持する。

```typescript
export type MasterRequest = {
  publicId: string
  type: string
  description: string
  status: 'SUBMITTED' | 'APPROVED' | 'RETURNED'
  version: number
  returnReason: string | null
  requestedAt: string
}
```

- [ ] **Step 1: 2項目契約の失敗テストを書く**

```java
mockMvc.perform(post("/api/v1/master-requests")
        .content("{\"type\":\"クラウド資格\",\"description\":\"AWS認定の追加を希望します\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("クラウド資格"))
        .andExpect(jsonPath("$.description").value("AWS認定の追加を希望します"));
```

typeは1〜100文字、descriptionは1〜1000文字。空白だけ、上限超過、余分なpayloadに依存しないことを検証する。

- [ ] **Step 2: 現行code/name/category契約で失敗することを確認する**

Run: `cd backend; .\mvnw.cmd -Dtest=MasterRequestIntegrationTests test`

Expected: 400または応答形不一致。

- [ ] **Step 3: 申請を「マスタ候補の提案」に変更する**

```java
public Row create(String accountPublicId, String type, String description, String traceId) {
    String normalizedType = required(type, "種類", 100);
    String normalizedDescription = required(description, "説明", 1000);
    // 新規行はrequest_type/request_descriptionへ保存する。
    // legacyのmaster_type/proposed_payload_jsonはnullとし、既存行だけ読み取り互換を保つ。
}
```

管理者の承認は申請状態をAPPROVEDにするが、不十分な情報からskill/certification masterを自動生成しない。実マスタ作成は既存の管理機能の責務として分離し、`created_master_public_id` は既存行を保持、新規簡略申請ではnullとする。

- [ ] **Step 4: フロントの失敗テストを書く**

```tsx
expect(screen.getByLabelText('種類')).toBeInTheDocument()
expect(screen.getByLabelText('説明')).toBeInTheDocument()
expect(screen.queryByLabelText('コード')).not.toBeInTheDocument()
```

- [ ] **Step 5: 2項目フォームと日本語履歴を実装する**

`MasterRequest` typeを文字列へ変更し、内部JSONを表示する `<pre>` を削除する。管理者には種類、説明、申請者、日時、判断ボタンだけを表示する。

- [ ] **Step 6: backend/frontend対象テストを通す**

Run: `cd backend; .\mvnw.cmd -Dtest=MasterRequestIntegrationTests test`

Run: `cd frontend; npm test -- MasterRequestsPage.test.tsx`

Expected: PASS。

- [ ] **Step 7: Task 6をコミットする**

```powershell
git add backend/src/main/java/com/query/insight/master backend/src/test/java/com/query/insight/master frontend/src/pages/MasterRequestsPage.tsx frontend/src/pages/MasterRequestsPage.test.tsx frontend/src/types.ts
git commit -m "マスタ申請を種類と説明へ簡略化"
```

### Task 7: 正式ロゴと控えめなガラス調の共通UIを実装する

**Files:**
- Create: `frontend/src/assets/query-insight-logo.png`
- Create: `frontend/src/components/BrandLogo.tsx`
- Create: `frontend/src/components/NotificationBadge.tsx`
- Modify: `frontend/src/pages/LoginPage.tsx`
- Modify: `frontend/src/components/AppLayout.tsx`
- Modify: `frontend/src/pages/LoginPage.test.tsx`
- Modify: `frontend/src/components/AppLayout.test.tsx`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Produces: `<BrandLogo compact?: boolean />`、`<NotificationBadge count={number} />`、日本語パンくず辞書。
- Consumes: ユーザー提供ロゴ画像、Task 5の未読件数API。

- [ ] **Step 1: ロゴ編集前に元画像を目視確認し、編集版を生成する**

Built-in image editへ次を指定する。

```text
Use case: background-extraction
Asset type: Query Insight web application wordmark
Input image: user-provided QUERY logo is the edit target
Primary request: preserve the QUERY artwork exactly, remove only the white background, and append INSIGHT on the right
Text (verbatim): "INSIGHT"
Style: bold geometric wordmark matching the weight of QUERY
Color palette: preserve the original dark gray and yellow; make INSIGHT yellow with any necessary dark-gray structural accent
Constraints: transparent background; one horizontal line; no QI badge; no extra slogan; no shadow; no watermark
```

生成結果をworkspaceへ `frontend/src/assets/query-insight-logo.png` として保存する。`QUERY INSIGHT` の綴り、透明角、白縁、形状保持を `view_image` で確認し、不正なら1回だけ対象を限定して再編集する。

- [ ] **Step 2: 不要文言・通知バッジ・日本語パンくずの失敗テストを書く**

```tsx
expect(screen.getByRole('img', { name: 'QUERY INSIGHT' })).toBeInTheDocument()
expect(screen.queryByText(/社員の経験と能力/)).not.toBeInTheDocument()
expect(screen.queryByText(/^QI$/)).not.toBeInTheDocument()
expect(screen.getByText('3', { selector: '.notification-badge' })).toBeInTheDocument()
expect(screen.getByText('スキル')).toBeInTheDocument()
expect(screen.queryByText(/SKILLS\/EDIT/)).not.toBeInTheDocument()
```

- [ ] **Step 3: テストを実行し現行UIで失敗することを確認する**

Run: `cd frontend; npm test -- LoginPage.test.tsx AppLayout.test.tsx`

Expected: QI装飾・英語パンくず・旧説明文が残ってFAIL。

- [ ] **Step 4: 共通コンポーネントと権限別メニューを実装する**

```tsx
export function BrandLogo({ compact = false }: { compact?: boolean }) {
  return <img className={compact ? 'brand-logo compact' : 'brand-logo'}
    src={logo} alt="QUERY INSIGHT" />
}

export function NotificationBadge({ count }: { count: number }) {
  if (count <= 0) return null
  return <span className="notification-badge" aria-label={`未読通知${count}件`}>
    {count > 99 ? '99+' : count}
  </span>
}
```

一般メニューはダッシュボード、スキル、業務経歴、資格、上長評価、通知、マスタ申請とする。英語pathnameの直接表示をやめ、route-to-label辞書で日本語ページ名を返す。

- [ ] **Step 5: デザインAのCSSとfallbackを実装する**

```css
:root { --glass: rgba(17,30,45,.76); --glass-line: rgba(190,211,230,.16); }
body { background:
  radial-gradient(circle at 82% 4%, rgba(255,212,0,.10), transparent 28%),
  radial-gradient(circle at 20% 88%, rgba(45,112,190,.12), transparent 30%), #08111c; }
.card,.metric-card,.login-panel,.sidebar,.topbar {
  background: var(--glass); border-color: var(--glass-line);
  backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px);
}
@supports not (backdrop-filter: blur(1px)) {
  .card,.metric-card,.login-panel,.sidebar,.topbar { background:#111e2d; }
}
```

- [ ] **Step 6: UI対象テストとビルドを通す**

Run: `cd frontend; npm test -- LoginPage.test.tsx AppLayout.test.tsx; npm run lint; npm run build`

Expected: PASS。

- [ ] **Step 7: Task 7をコミットする**

```powershell
git add frontend/src/assets/query-insight-logo.png frontend/src/components/BrandLogo.tsx frontend/src/components/NotificationBadge.tsx frontend/src/pages/LoginPage.tsx frontend/src/components/AppLayout.tsx frontend/src/pages/LoginPage.test.tsx frontend/src/components/AppLayout.test.tsx frontend/src/styles.css
git commit -m "QUERY INSIGHTロゴとガラス調共通UIを追加"
```

### Task 8: タレント情報を種類別画面へ整理する

**Files:**
- Create: `frontend/src/pages/SkillsPage.tsx`
- Create: `frontend/src/pages/CareersPage.tsx`
- Create: `frontend/src/pages/CertificationsPage.tsx`
- Create: `frontend/src/pages/TalentCategoryPages.test.tsx`
- Modify: `frontend/src/components/TalentProfilePanel.tsx`
- Modify: `frontend/src/pages/TalentSubmissionFormPage.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/types.ts`

**Interfaces:**
- Produces: `/skills`、`/careers`、`/certifications`、種類別登録route。`KNOWLEDGE` の表示名は常に「得意分野」。
- Consumes: 既存talent profile・submission・attachment API。

- [ ] **Step 1: 種類別表示と登録導線の失敗テストを書く**

```tsx
renderAt('/skills')
expect(screen.getByRole('heading', { name: 'スキル' })).toBeInTheDocument()
expect(screen.getByRole('heading', { name: '得意分野' })).toBeInTheDocument()
expect(screen.getByRole('link', { name: 'スキルを登録' })).toHaveAttribute('href', '/talent/new/SKILL')
expect(screen.getByRole('link', { name: '得意分野を登録' })).toHaveAttribute('href', '/talent/new/KNOWLEDGE')
```

業務経歴画面に他区分が出ないこと、資格画面に他区分が出ないこと、フォーム見出しが「得意分野を登録」であることも検証する。

- [ ] **Step 2: 現行1画面表示で失敗することを確認する**

Run: `cd frontend; npm test -- TalentCategoryPages.test.tsx TalentSubmissionPages.test.tsx`

Expected: routeまたは個別ボタンがなくFAIL。

- [ ] **Step 3: TalentProfilePanelを区分コンポーネントへ分割する**

`SkillSection`、`KnowledgeSection`、`CareerSection`、`CertificationSection` をnamed exportし、既存panelは社員詳細用の全区分表示として残す。各新規pageは必要な区分だけを構成する。

- [ ] **Step 4: routesと日本語文言を更新する**

`/skills/edit`、`/careers/edit`、`/certifications/edit` は新routeへredirectして後方互換を保つ。`TALENT APPLICATION`、英語状態コード、`専門知識` を利用者画面から削除し、日本語状態辞書を使用する。

- [ ] **Step 5: フロント対象テストを通す**

Run: `cd frontend; npm test -- TalentCategoryPages.test.tsx TalentSubmissionPages.test.tsx`

Expected: PASS。添付なしで保存・提出できる既存テストもPASS。

- [ ] **Step 6: Task 8をコミットする**

```powershell
git add frontend/src/pages/SkillsPage.tsx frontend/src/pages/CareersPage.tsx frontend/src/pages/CertificationsPage.tsx frontend/src/pages/TalentCategoryPages.test.tsx frontend/src/components/TalentProfilePanel.tsx frontend/src/pages/TalentSubmissionFormPage.tsx frontend/src/App.tsx frontend/src/types.ts
git commit -m "タレント情報を種類別画面へ整理"
```

### Task 9: ダッシュボードへ自己ステータス・確定評価・分析を集約する

**Files:**
- Modify: `backend/src/main/java/com/query/insight/dashboard/DashboardController.java`
- Create: `backend/src/test/java/com/query/insight/dashboard/DashboardControllerIntegrationTests.java`
- Create: `frontend/src/components/RankBadge.tsx`
- Create: `frontend/src/components/AnalysisPanel.tsx`
- Modify: `frontend/src/pages/DashboardPage.tsx`
- Create: `frontend/src/pages/DashboardPage.test.tsx`
- Modify: `frontend/src/types.ts`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Produces: `Dashboard { profile, profileStatus, finalManagerEvaluation, unreadNotifications }`。分析実行はTask 5 endpointをPanelから呼ぶ。
- Consumes: Task 3自己ステータス、Task 4確定評価、Task 5分析・通知。

```typescript
export type ProfileStatus = {
  publicId: string; skillScore: number; knowledgeScore: number; careerScore: number
  certificationScore: number; totalScore: number; grade: EvaluationRank
  missingCategories: string[]; formulaVersion: string; calculatedAt: string; editable: false
}
export type EvaluationRank = 'S' | 'A' | 'B' | 'C' | 'D' | 'F'
```

- [ ] **Step 1: 集約APIの失敗テストを書く**

```java
mockMvc.perform(get("/api/v1/dashboard/me").with(jwtFor(employee)))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.profileStatus.grade").value("A"))
    .andExpect(jsonPath("$.profileStatus.skillScore").isNumber())
    .andExpect(jsonPath("$.profileStatus.editable").value(false))
    .andExpect(jsonPath("$.finalManagerEvaluation.finalRank").value("B"));
```

未確定評価しかない場合は `finalManagerEvaluation` がnullであること、他社員の情報を返さないことを追加する。

- [ ] **Step 2: 現行dashboard応答で失敗することを確認する**

Run: `cd backend; .\mvnw.cmd -Dtest=DashboardControllerIntegrationTests test`

Expected: `profileStatus` がなくFAIL。

- [ ] **Step 3: Controllerをservice集約へ変更する**

`DashboardController` から自己評価detailの直接SQLを削除し、`ProfileStatusService.currentForEmployee` と `EvaluationWorkflowService.finalResult` を使用する。未確定評価は例外ではなくnullへ変換する専用query methodを設ける。応答に編集endpointを含めない。

- [ ] **Step 4: ダッシュボードUIの失敗テストを書く**

```tsx
expect(screen.getByText('自己ステータス')).toBeInTheDocument()
expect(screen.getByText('A')).toBeInTheDocument()
expect(screen.getByText('プロトタイプ分析')).toBeInTheDocument()
expect(screen.queryByText('次にやること')).not.toBeInTheDocument()
expect(screen.queryByText('評価を編集')).not.toBeInTheDocument()
```

- [ ] **Step 5: 大型能力バランスと分析Panelを実装する**

4分野のscoreをRadarChartへ渡せるadapterを作り、総合ランク、総合点、未登録分野、算出日時を並べる。上長評価は確定済みだけを表示し、未確定時は「公開済みの上長評価はありません」とする。AIボタンと結果を同ページ下部へ配置し、旧「次にやること」を削除する。

- [ ] **Step 6: backend/frontend対象テストを通す**

Run: `cd backend; .\mvnw.cmd -Dtest=DashboardControllerIntegrationTests test`

Run: `cd frontend; npm test -- DashboardPage.test.tsx RadarChart.test.tsx`

Expected: PASS。

- [ ] **Step 7: Task 9をコミットする**

```powershell
git add backend/src/main/java/com/query/insight/dashboard/DashboardController.java backend/src/test/java/com/query/insight/dashboard/DashboardControllerIntegrationTests.java frontend/src/components/RankBadge.tsx frontend/src/components/AnalysisPanel.tsx frontend/src/pages/DashboardPage.tsx frontend/src/pages/DashboardPage.test.tsx frontend/src/types.ts frontend/src/styles.css
git commit -m "ダッシュボードへ自己ステータスと分析を集約"
```

### Task 10: 上長評価画面と評価基準を完成させる

**Files:**
- Create: `frontend/src/pages/FinalManagerEvaluationPage.tsx`
- Modify: `frontend/src/pages/ManagerEvaluationDetailPage.tsx`
- Modify: `frontend/src/pages/ManagerEvaluationsPage.tsx`
- Modify: `frontend/src/pages/ExecutiveEvaluationDetailPage.tsx`
- Modify: `frontend/src/pages/ApprovalPages.test.tsx`
- Create: `frontend/src/pages/FinalManagerEvaluationPage.test.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/types.ts`

**Interfaces:**
- Produces: `/evaluations/manager-result` の本人向け確定結果、役職者のrank selector、共通評価基準notice。
- Consumes: Task 4 rank API。

- [ ] **Step 1: ランク入力・注意書き・公開制御の失敗テストを書く**

```tsx
expect(screen.getAllByRole('radio', { name: /S|A|B|C|D|F/ })).toHaveLength(36)
expect(screen.getByRole('heading', { name: '評価基準と注意事項' })).toBeInTheDocument()
expect(screen.queryByText(/習熟度（1～5）/)).not.toBeInTheDocument()
```

本人結果画面は確定ランクとコメントを表示し、scoreやlevel数値を表示しないことを検証する。

- [ ] **Step 2: 現行1〜5ボタンUIで失敗することを確認する**

Run: `cd frontend; npm test -- ApprovalPages.test.tsx FinalManagerEvaluationPage.test.tsx`

Expected: rank radioまたは本人結果routeがなくFAIL。

- [ ] **Step 3: 6軸rank selectorと評価基準noticeを実装する**

```tsx
const ranks: EvaluationRank[] = ['S','A','B','C','D','F']
<fieldset className="rank-selector">
  <legend>{detail.displayName}</legend>
  {ranks.map(rank => <label key={rank}><input type="radio" value={rank} {...register(...)} />{rank}</label>)}
</fieldset>
```

注意書きに各ランク境界、具体的な事実に基づくこと、属性や印象だけで判断しないこと、コメントに機密情報を書かないことを表示する。

- [ ] **Step 4: 一般・役職者・全社役職者のrouteを分ける**

一般メニューの「上長評価」は本人結果へ、役職者の「上長評価入力」は部下一覧へ、ALL scopeの「最終承認」は経営画面へ遷移する。表示可否だけに依存せず既存backend認可を使用する。

- [ ] **Step 5: フロント評価テストを通す**

Run: `cd frontend; npm test -- ApprovalPages.test.tsx FinalManagerEvaluationPage.test.tsx`

Expected: PASS。

- [ ] **Step 6: Task 10をコミットする**

```powershell
git add frontend/src/pages/FinalManagerEvaluationPage.tsx frontend/src/pages/ManagerEvaluationDetailPage.tsx frontend/src/pages/ManagerEvaluationsPage.tsx frontend/src/pages/ExecutiveEvaluationDetailPage.tsx frontend/src/pages/ApprovalPages.test.tsx frontend/src/pages/FinalManagerEvaluationPage.test.tsx frontend/src/App.tsx frontend/src/types.ts
git commit -m "上長評価のランク入力と本人公開画面を追加"
```

### Task 11: 文書同期・全回帰・実動作確認を完了する

**Files:**
- Modify: `README.md`
- Modify: `docs/02-requirements.md`
- Modify: `docs/03-use-cases.md`
- Modify: `docs/04-non-functional-requirements.md`
- Modify: `docs/06-traceability.md`
- Modify: `docs/08-realistic-sample-data.md`
- Modify: `docs/99-open-questions.md`
- Modify: `.codex/PROJECT_RULES.md` only if the approved invariants are not already fully represented

**Interfaces:**
- Produces: 要件・画面・API・テストの現在状態を一致させた証跡。
- Consumes: Tasks 1〜10の完成コードと実行結果。

- [ ] **Step 1: 旧表記と旧導線を検索する**

Run:

```powershell
rg -n "自己評価|専門知識|次にやること|SCR-[0-9]+|TALENT APPLICATION|QUERY INSIGHT /|習熟度（1～5）" frontend/src README.md docs --glob '!docs/source/original/**' --glob '!docs/superpowers/**'
```

Expected: 内部設計上必要な記述以外の利用者向け旧表記を列挙できる。

- [ ] **Step 2: 文書を現在の実装へ同期する**

各画面のroute、API状態、自動算出式、公開タイミング、fallback、通知、任意添付、3権限、検証名を記載する。実装していない機能を実装済みに変更しない。旧自己評価フローを履歴説明として残す場合は「廃止済み」と明示する。

- [ ] **Step 3: バックエンド全テストを実行する**

Run: `cd backend; .\mvnw.cmd test`

Expected: 全テストPASS、failure/error 0。

- [ ] **Step 4: フロントエンド全検証を実行する**

Run:

```powershell
cd frontend
npm ci
npm run lint
npm test -- --maxWorkers=1
npm run build
npm audit --audit-level=moderate
```

Expected: lint 0、全テストPASS、build成功、moderate以上0件。

- [ ] **Step 5: Compose定義と差分を検証する**

Run:

```powershell
cd ..
docker compose config --quiet
git diff --check
git status --short
```

Expected: Compose構文成功、whitespace errorなし、対象外ファイルをstageしていない。

- [ ] **Step 6: Compose実動作を確認する**

Run:

```powershell
docker info
docker compose ps
docker compose up --build -d db clamav backend frontend
docker compose ps
docker compose logs --tail 200 backend frontend
```

PostgreSQLとbackend healthを待ち、`http://localhost:8088` を使用する。Docker環境自体が壊れている場合だけ、理由を記録して既存H2 local fallbackでアプリ確認を行い、PostgreSQL/ClamAV未確認を完了扱いにしない。

- [ ] **Step 7: 実ブラウザで一般ユーザーを確認する**

`ueno / 5050Rock` でログインし、ロゴ、不要文言削除、日本語パンくず、ガラス調、スキル/得意分野、業務経歴、資格、自己ステータス編集不可、AIまたはプロトタイプ分析、確定上長評価、通知バッジ、マスタ2項目を確認する。ログアウトしてログイン画面へ戻す。

- [ ] **Step 8: 役職者・全社役職者フローを確認する**

ローカルサンプルの実在する役職者確認アカウントをREADMEまたはinitializerから取得し、直属部下だけを評価できること、S〜F入力、提出、最終承認、本人公開、通知数更新を確認する。確認用認証情報をログや文書へ新規保存しない。

- [ ] **Step 9: 文書と最終修正をコミットする**

```powershell
git add README.md docs/02-requirements.md docs/03-use-cases.md docs/04-non-functional-requirements.md docs/06-traceability.md docs/08-realistic-sample-data.md docs/99-open-questions.md .codex/PROJECT_RULES.md
git commit -m "刷新後の要件と検証証跡を同期"
```

- [ ] **Step 10: 最終差分を独立レビューする**

設計書の各節をTasks 1〜11のcommitへ対応付け、認可、個人情報、migration、競合、fallback、UI公開範囲を確認する。指摘を修正した場合は対象テストと全回帰を再実行し、修正内容だけを追加commitする。

---

## Completion Evidence

完了報告には次だけを確認済みの数値・URL・commitとともに記載する。

- 自己ステータスの式と境界値テスト結果
- 一般・直属上長・全社役職者・管理者の認可結果
- 上長評価の提出から本人公開までの実動作結果
- 通常AIまたはプロトタイプ分析の実行結果
- 通知バッジの未読・既読同期結果
- backend/frontend/Compose/ブラウザ検証結果
- Docker等の環境制約と未確認範囲
- Git branch、commit、push未実施/実施状況
