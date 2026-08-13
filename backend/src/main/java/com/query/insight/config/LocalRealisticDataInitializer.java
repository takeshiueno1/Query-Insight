package com.query.insight.config;

import com.query.insight.common.PublicIdGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
@Order(20)
public class LocalRealisticDataInitializer implements ApplicationRunner {
    private static final String LOCAL_PASSWORD = "QueryInsight2026";
    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 7, 1);
    private static final List<String> LAST_NAMES = List.of(
            "管理", "山田", "佐藤", "鈴木", "高橋", "田中", "伊藤", "渡辺", "中村", "小林",
            "加藤", "吉田", "山本", "松本", "井上", "木村", "林", "清水", "斎藤", "山口",
            "森", "池田", "橋本", "阿部", "石川", "山崎", "中島", "前田", "藤田", "小川",
            "後藤", "岡田", "長谷川", "村上", "近藤", "石井", "坂本", "遠藤", "青木", "藤井",
            "西村", "福田", "太田", "三浦", "藤原", "岡本", "松田", "中川", "中野", "原田");
    private static final List<String> FIRST_NAMES = List.of(
            "太郎", "花子", "健", "葵", "直樹", "美咲", "大輔", "結衣", "拓海", "陽菜",
            "翔太", "彩香", "悠斗", "七海", "亮", "麻衣", "蓮", "真由", "航", "里奈",
            "陸", "遥", "誠", "愛", "颯太", "優奈", "和也", "千尋", "雄大", "未来",
            "健太", "菜々子", "達也", "沙織", "一樹", "恵", "駿", "由佳", "浩平", "明日香",
            "俊介", "香織", "学", "梨沙", "智也", "美月", "祐介", "琴音", "隆", "瑞希");

    private static final Map<String, List<String>> DEPARTMENT_SKILLS = Map.of(
            "DEV", List.of("JAVA", "SPRING", "REACT", "TYPESCRIPT", "POSTGRESQL", "DOCKER", "AWS", "TEST_AUTOMATION"),
            "DATA", List.of("SQL", "PYTHON", "DATA_MODELING", "POSTGRESQL", "AWS", "STATISTICS"),
            "PRODUCT", List.of("PRODUCT_MANAGEMENT", "UX_RESEARCH", "DATA_ANALYSIS", "FACILITATION", "AGILE"),
            "SALES", List.of("ENTERPRISE_SALES", "NEGOTIATION", "CRM", "PROPOSAL", "DATA_ANALYSIS"),
            "CS", List.of("CUSTOMER_SUCCESS", "CRM", "FACILITATION", "DATA_ANALYSIS", "PROPOSAL"),
            "HR", List.of("HR_MANAGEMENT", "FACILITATION", "DATA_ANALYSIS", "LABOR_LAW", "COACHING"),
            "CORP", List.of("PROJECT_MANAGEMENT", "ACCOUNTING", "RISK_MANAGEMENT", "DATA_ANALYSIS", "FACILITATION"));

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;

    public LocalRealisticDataInitializer(JdbcClient jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ensureDepartments(now);
        ensureRoles();
        ensureMasters();
        List<EmployeeSeed> employees = employeeSeeds();
        for (EmployeeSeed employee : employees) {
            ensureEmployee(employee, now);
        }
        for (EmployeeSeed employee : employees) {
            ensureAccountAndRoles(employee, now);
            ensureEvaluation(employee, now);
            ensureTalentProfile(employee, now);
        }
        jdbc.sql("UPDATE evaluation_periods SET manager_deadline=:deadline WHERE status='OPEN'")
                .param("deadline", LocalDateTime.of(2026, 7, 31, 23, 59)).update();
        ensureApprovalExample("QI0004", "EXECUTIVE_REVIEW", now);
        ensureApprovalExample("QI0005", "FINALIZED", now);
        ensureApprovalExample("QI0007", "MANAGER_RETURNED", now);
        ensureTalentWorkflowExamples(now);
    }

    private void ensureTalentWorkflowExamples(LocalDateTime now) {
        if (count("""
                SELECT COUNT(*) FROM talent_submissions ts JOIN employees e ON e.id=ts.employee_id
                WHERE e.employee_no='QITEST' AND CAST(ts.payload_json AS VARCHAR) LIKE '%ローカル確認用下書き%'
                """, Map.of()) == 0) {
            String publicId = PublicIdGenerator.next();
            jdbc.sql("""
                    INSERT INTO talent_submissions(public_id,employee_id,talent_type,logical_public_id,revision_no,
                      status,payload_json,version,created_at,updated_at)
                    SELECT :publicId,e.id,'SKILL',:publicId,1,'DRAFT',
                      JSON_OBJECT('masterPublicId' VALUE sm.public_id,'level' VALUE 3,
                        'yearsExperience' VALUE 1.5,'lastUsedOn' VALUE DATE '2026-07-01',
                        'evidence' VALUE 'ローカル確認用下書き'),0,:now,:now
                    FROM employees e CROSS JOIN (
                      SELECT public_id FROM skill_masters WHERE status='ACTIVE' ORDER BY id LIMIT 1
                    ) sm WHERE e.employee_no='QITEST'
                    """).param("publicId", publicId).param("now", now).update();
            ensureTalentEvent(publicId, "QITEST", "CREATE", null, "DRAFT", null, now);
        }

        if (count("""
                SELECT COUNT(*) FROM talent_submissions ts JOIN employees e ON e.id=ts.employee_id
                WHERE e.employee_no='QI0003' AND CAST(ts.payload_json AS VARCHAR) LIKE '%ローカル確認用承認待ち%'
                """, Map.of()) == 0) {
            String publicId = PublicIdGenerator.next();
            jdbc.sql("""
                    INSERT INTO talent_submissions(public_id,employee_id,talent_type,logical_public_id,revision_no,
                      status,payload_json,version,submitted_at,created_at,updated_at)
                    SELECT :publicId,e.id,'CAREER',:publicId,1,'SUBMITTED',
                      JSON_OBJECT('projectName' VALUE 'ローカル確認用承認待ち','industry' VALUE 'IT',
                        'roleName' VALUE '担当','startDate' VALUE DATE '2026-04-01','endDate' VALUE NULL,
                        'summary' VALUE '申請から承認までの動作確認用データ',
                        'achievements' VALUE 'チーム改善を実施','technologies' VALUE 'Java, React'),1,:now,:now,:now
                    FROM employees e WHERE e.employee_no='QI0003'
                    """).param("publicId", publicId).param("now", now).update();
            ensureTalentEvent(publicId, "QI0003", "CREATE", null, "DRAFT", null, now.minusMinutes(1));
            ensureTalentEvent(publicId, "QI0003", "SUBMIT", "DRAFT", "SUBMITTED", null, now);
        }

        if (count("""
                SELECT COUNT(*) FROM talent_submissions ts JOIN employees e ON e.id=ts.employee_id
                WHERE e.employee_no='QI0007' AND CAST(ts.payload_json AS VARCHAR) LIKE '%LOCAL-RETURN-SAMPLE%'
                """, Map.of()) == 0) {
            String publicId = PublicIdGenerator.next();
            jdbc.sql("""
                    INSERT INTO talent_submissions(public_id,employee_id,talent_type,logical_public_id,revision_no,
                      status,payload_json,version,submitted_at,decided_at,reviewer_account_id,return_reason,
                      created_at,updated_at)
                    SELECT :publicId,e.id,'CERTIFICATION',:publicId,1,'RETURNED',
                      JSON_OBJECT('masterPublicId' VALUE cm.public_id,'acquiredOn' VALUE DATE '2026-01-15',
                        'expiresOn' VALUE NULL,'credentialReference' VALUE 'LOCAL-RETURN-SAMPLE'),2,
                      :now,:now,managerAccount.id,'証明書番号を確認できる根拠を追記してください。',:now,:now
                    FROM employees e
                    CROSS JOIN (SELECT public_id FROM certification_masters WHERE status='ACTIVE' ORDER BY id LIMIT 1) cm
                    CROSS JOIN (SELECT a.id FROM accounts a JOIN employees me ON me.id=a.employee_id
                      WHERE me.employee_no='QI0002') managerAccount
                    WHERE e.employee_no='QI0007'
                    """).param("publicId", publicId).param("now", now).update();
            ensureTalentEvent(publicId, "QI0007", "CREATE", null, "DRAFT", null, now.minusMinutes(2));
            ensureTalentEvent(publicId, "QI0007", "SUBMIT", "DRAFT", "SUBMITTED", null, now.minusMinutes(1));
            ensureTalentEvent(publicId, "QI0002", "RETURN", "SUBMITTED", "RETURNED",
                    "証明書番号を確認できる根拠を追記してください。", now);
        }
    }

    private void ensureTalentEvent(String submissionPublicId, String actorEmployeeNo, String action,
            String fromStatus, String toStatus, String reason, LocalDateTime occurredAt) {
        jdbc.sql("""
                INSERT INTO talent_submission_events(public_id,submission_id,actor_account_id,action,
                  from_status,to_status,reason,occurred_at,trace_id)
                SELECT :publicId,ts.id,a.id,:action,:fromStatus,:toStatus,:reason,:occurredAt,:traceId
                FROM talent_submissions ts JOIN accounts a ON a.employee_id=(
                  SELECT id FROM employees WHERE employee_no=:employeeNo)
                WHERE ts.public_id=:submissionPublicId AND NOT EXISTS (
                  SELECT 1 FROM talent_submission_events ev
                  WHERE ev.submission_id=ts.id AND ev.action=:action)
                """).param("publicId", PublicIdGenerator.next()).param("action", action)
                .param("fromStatus", fromStatus).param("toStatus", toStatus).param("reason", reason)
                .param("occurredAt", occurredAt).param("traceId", PublicIdGenerator.next())
                .param("employeeNo", actorEmployeeNo).param("submissionPublicId", submissionPublicId).update();
    }

    private void ensureApprovalExample(String employeeNo, String targetStatus, LocalDateTime now) {
        Map<String, Object> target = jdbc.sql("""
                SELECT t.id target_id,t.public_id target_public_id,t.evaluator_employee_id
                FROM evaluation_targets t JOIN employees e ON e.id=t.employee_id
                WHERE e.employee_no=:employeeNo
                """).param("employeeNo", employeeNo).query().singleRow();
        long targetId = ((Number) target.get("TARGET_ID")).longValue();
        if (count("SELECT COUNT(*) FROM manager_evaluations WHERE target_id=:targetId", "targetId", targetId) > 0) {
            return;
        }
        String managerStatus = "FINALIZED".equals(targetStatus) ? "FINALIZED"
                : "EXECUTIVE_REVIEW".equals(targetStatus) ? "SUBMITTED" : "DRAFT";
        List<Integer> levels = jdbc.sql("SELECT level FROM self_evaluation_details WHERE target_id=:targetId")
                .param("targetId", targetId).query(Integer.class).list();
        BigDecimal score = BigDecimal.valueOf(levels.stream().mapToInt(Integer::intValue).sum())
                .divide(BigDecimal.valueOf(levels.size()), 2, RoundingMode.HALF_UP);
        String grade = score.compareTo(new BigDecimal("4.50")) >= 0 ? "S"
                : score.compareTo(new BigDecimal("4.00")) >= 0 ? "A"
                : score.compareTo(new BigDecimal("3.00")) >= 0 ? "B" : "C";
        jdbc.sql("""
                INSERT INTO manager_evaluations(public_id,target_id,revision_no,status,summary,weighted_score,grade,
                  submitted_at,finalized_at,version)
                VALUES (:publicId,:targetId,1,:status,:summary,:score,:grade,:submittedAt,:finalizedAt,0)
                """).param("publicId", PublicIdGenerator.next()).param("targetId", targetId)
                .param("status", managerStatus).param("summary", "安定した成果と今後の成長可能性を確認しました。")
                .param("score", score).param("grade", grade)
                .param("submittedAt", "DRAFT".equals(managerStatus) ? null : now)
                .param("finalizedAt", "FINALIZED".equals(managerStatus) ? now : null).update();
        long managerEvaluationId = jdbc.sql("SELECT id FROM manager_evaluations WHERE target_id=:targetId")
                .param("targetId", targetId).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO manager_evaluation_details(manager_evaluation_id,axis_code,level,comment)
                SELECT :managerId,axis_code,level,
                  CASE WHEN axis_code='TECHNICAL' THEN '成果物と日常の行動を踏まえて判断しました。' ELSE NULL END
                FROM self_evaluation_details WHERE target_id=:targetId
                """).param("managerId", managerEvaluationId).param("targetId", targetId).update();
        jdbc.sql("""
                UPDATE evaluation_targets SET status=:status,current_manager_evaluation_id=:managerId,
                  final_score=:finalScore,final_grade=:finalGrade,finalized_at=:finalizedAt,version=version+1
                WHERE id=:targetId
                """).param("status", targetStatus).param("managerId", managerEvaluationId)
                .param("finalScore", "FINALIZED".equals(targetStatus) ? score : null)
                .param("finalGrade", "FINALIZED".equals(targetStatus) ? grade : null)
                .param("finalizedAt", "FINALIZED".equals(targetStatus) ? now : null).param("targetId", targetId).update();
        long actorAccountId = "FINALIZED".equals(targetStatus) || "MANAGER_RETURNED".equals(targetStatus)
                ? accountId("QI0039")
                : jdbc.sql("SELECT id FROM accounts WHERE employee_id=:employeeId")
                        .param("employeeId", ((Number) target.get("EVALUATOR_EMPLOYEE_ID")).longValue())
                        .query(Long.class).single();
        String action = "FINALIZED".equals(targetStatus) ? "EXECUTIVE_APPROVE"
                : "MANAGER_RETURNED".equals(targetStatus) ? "EXECUTIVE_RETURN" : "MANAGER_SUBMIT";
        String fromStatus = "MANAGER_RETURNED".equals(targetStatus) ? "EXECUTIVE_REVIEW" : "MANAGER_IN_PROGRESS";
        jdbc.sql("""
                INSERT INTO evaluation_workflow_events(public_id,target_id,manager_evaluation_id,actor_account_id,
                  action,from_status,to_status,reason,late,occurred_at,trace_id)
                VALUES (:publicId,:targetId,:managerId,:actorId,:action,:fromStatus,:toStatus,:reason,TRUE,:now,:traceId)
                """).param("publicId", PublicIdGenerator.next()).param("targetId", targetId)
                .param("managerId", managerEvaluationId).param("actorId", actorAccountId).param("action", action)
                .param("fromStatus", fromStatus).param("toStatus", targetStatus)
                .param("reason", "MANAGER_RETURNED".equals(targetStatus) ? "経営判断の根拠を総評へ追記してください。" : null)
                .param("now", now).param("traceId", PublicIdGenerator.next()).update();
    }

    private long accountId(String employeeNo) {
        return jdbc.sql("SELECT a.id FROM accounts a JOIN employees e ON e.id=a.employee_id WHERE e.employee_no=:employeeNo")
                .param("employeeNo", employeeNo).query(Long.class).single();
    }

    private void ensureDepartments(LocalDateTime now) {
        ensureDepartment("HQ", "コーポレート本部", null, now);
        ensureDepartment("DEV", "プロダクト開発本部", "HQ", now);
        ensureDepartment("PRODUCT", "プロダクト企画部", "HQ", now);
        ensureDepartment("SALES", "法人営業部", "HQ", now);
        ensureDepartment("DATA", "データプラットフォーム部", "DEV", now);
        ensureDepartment("CS", "カスタマーサクセス部", "HQ", now);
        ensureDepartment("HR", "人事・組織開発部", "HQ", now);
        ensureDepartment("CORP", "経営管理部", "HQ", now);
    }

    private void ensureDepartment(String code, String name, String parentCode, LocalDateTime now) {
        if (count("SELECT COUNT(*) FROM departments WHERE code=:code", "code", code) > 0) return;
        Long parentId = parentCode == null ? null : jdbc.sql("SELECT id FROM departments WHERE code=:code")
                .param("code", parentCode).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO departments(public_id,code,name,parent_id,status,version,created_at,updated_at)
                VALUES (:publicId,:code,:name,:parentId,'ACTIVE',0,:now,:now)
                """).param("publicId", PublicIdGenerator.next()).param("code", code).param("name", name)
                .param("parentId", parentId).param("now", now).update();
    }

    private void ensureRoles() {
        for (String role : List.of("GENERAL", "OFFICER", "ADMIN")) {
            if (count("SELECT COUNT(*) FROM roles WHERE code=:code", "code", role) == 0) {
                jdbc.sql("INSERT INTO roles(code,name,status) VALUES (:code,:name,'ACTIVE')")
                        .param("code", role).param("name", role).update();
            }
        }
    }

    private void ensureMasters() {
        List<MasterSeed> skills = List.of(
                new MasterSeed("JAVA", "Java", "ENGINEERING", "Javaによる業務アプリケーション開発"),
                new MasterSeed("SPRING", "Spring Boot", "ENGINEERING", "Spring BootによるAPI・バッチ開発"),
                new MasterSeed("REACT", "React", "ENGINEERING", "ReactによるWebフロントエンド開発"),
                new MasterSeed("TYPESCRIPT", "TypeScript", "ENGINEERING", "型安全なフロントエンド開発"),
                new MasterSeed("POSTGRESQL", "PostgreSQL", "ENGINEERING", "RDB設計・SQL・運用"),
                new MasterSeed("DOCKER", "Docker", "PLATFORM", "コンテナ化と開発環境標準化"),
                new MasterSeed("AWS", "AWS", "PLATFORM", "AWSを用いたクラウド設計・運用"),
                new MasterSeed("TEST_AUTOMATION", "テスト自動化", "QUALITY", "単体・結合・E2Eテストの自動化"),
                new MasterSeed("SQL", "SQL", "DATA", "分析・業務処理向けSQL設計"),
                new MasterSeed("PYTHON", "Python", "DATA", "データ加工・分析・自動化"),
                new MasterSeed("DATA_MODELING", "データモデリング", "DATA", "分析・業務データモデルの設計"),
                new MasterSeed("STATISTICS", "統計分析", "DATA", "仮説検証と統計的な分析"),
                new MasterSeed("PRODUCT_MANAGEMENT", "プロダクトマネジメント", "PRODUCT", "課題発見からロードマップ策定"),
                new MasterSeed("UX_RESEARCH", "UXリサーチ", "PRODUCT", "ユーザー調査と仮説検証"),
                new MasterSeed("DATA_ANALYSIS", "データ分析", "BUSINESS", "KPI設計と意思決定支援"),
                new MasterSeed("FACILITATION", "ファシリテーション", "BUSINESS", "会議・合意形成の設計と進行"),
                new MasterSeed("AGILE", "アジャイル開発", "DELIVERY", "反復型の計画・実行・改善"),
                new MasterSeed("ENTERPRISE_SALES", "法人営業", "SALES", "法人顧客の課題整理と案件推進"),
                new MasterSeed("NEGOTIATION", "交渉", "SALES", "利害調整と契約条件の合意形成"),
                new MasterSeed("CRM", "CRM活用", "SALES", "顧客情報と商談プロセスの管理"),
                new MasterSeed("PROPOSAL", "提案設計", "SALES", "課題に基づく提案書と価値訴求"),
                new MasterSeed("CUSTOMER_SUCCESS", "カスタマーサクセス", "CUSTOMER", "導入・活用・継続の支援"),
                new MasterSeed("HR_MANAGEMENT", "人材マネジメント", "PEOPLE", "採用・配置・育成・評価の運用"),
                new MasterSeed("LABOR_LAW", "労務法務", "PEOPLE", "労働関連法令と就業規則の実務"),
                new MasterSeed("COACHING", "コーチング", "PEOPLE", "対話による内省と成長支援"),
                new MasterSeed("PROJECT_MANAGEMENT", "プロジェクト管理", "DELIVERY", "計画・リスク・品質・進捗管理"),
                new MasterSeed("ACCOUNTING", "管理会計", "CORPORATE", "予算・実績・採算の管理"),
                new MasterSeed("RISK_MANAGEMENT", "リスク管理", "CORPORATE", "事業・業務リスクの識別と対応"));
        for (MasterSeed skill : skills) ensureMaster("skill_masters", skill);

        List<MasterSeed> knowledge = List.of(
                new MasterSeed("SYSTEM_DESIGN", "システム設計", "ENGINEERING", "可用性・性能・保守性を考慮した設計知識"),
                new MasterSeed("SECURITY", "アプリケーションセキュリティ", "ENGINEERING", "認証・認可・脆弱性対策の知識"),
                new MasterSeed("SRE", "SRE・可観測性", "PLATFORM", "信頼性指標、監視、障害対応の知識"),
                new MasterSeed("DATA_GOVERNANCE", "データガバナンス", "DATA", "品質・権限・ライフサイクル管理の知識"),
                new MasterSeed("SAAS_METRICS", "SaaS指標", "BUSINESS", "ARR、解約率、LTVなどの事業知識"),
                new MasterSeed("HR_POLICY", "人事制度", "PEOPLE", "等級・評価・報酬・育成制度の知識"),
                new MasterSeed("B2B_MARKET", "B2B市場・購買プロセス", "SALES", "法人購買と意思決定構造の知識"),
                new MasterSeed("CUSTOMER_OPERATION", "顧客業務理解", "CUSTOMER", "導入先の業務フローと定着課題の知識"),
                new MasterSeed("FINANCE", "財務・会計", "CORPORATE", "財務諸表と事業採算の知識"),
                new MasterSeed("PRIVACY", "個人情報保護", "GOVERNANCE", "個人データの適正利用と保護の知識"));
        for (MasterSeed item : knowledge) ensureMaster("knowledge_masters", item);

        List<CertificationSeed> certifications = List.of(
                new CertificationSeed("AWS_SAA", "AWS Certified Solutions Architect - Associate", "Amazon Web Services"),
                new CertificationSeed("IPA_AP", "応用情報技術者", "情報処理推進機構"),
                new CertificationSeed("IPA_DB", "データベーススペシャリスト", "情報処理推進機構"),
                new CertificationSeed("PMP", "Project Management Professional", "Project Management Institute"),
                new CertificationSeed("SCRUM", "Professional Scrum Master", "Scrum.org"),
                new CertificationSeed("STAT_2", "統計検定2級", "統計質保証推進協会"),
                new CertificationSeed("CAREER", "キャリアコンサルタント", "厚生労働省"),
                new CertificationSeed("BOOKKEEPING_2", "日商簿記2級", "日本商工会議所"));
        for (CertificationSeed certification : certifications) {
            if (count("SELECT COUNT(*) FROM certification_masters WHERE code=:code", "code", certification.code()) == 0) {
                jdbc.sql("""
                        INSERT INTO certification_masters(public_id,code,name,issuer,status)
                        VALUES (:publicId,:code,:name,:issuer,'ACTIVE')
                        """).param("publicId", PublicIdGenerator.next()).param("code", certification.code())
                        .param("name", certification.name()).param("issuer", certification.issuer()).update();
            }
        }
    }

    private void ensureMaster(String table, MasterSeed master) {
        if (count("SELECT COUNT(*) FROM " + table + " WHERE code=:code", "code", master.code()) > 0) return;
        jdbc.sql("INSERT INTO " + table + "(public_id,code,name,category,description,status) "
                + "VALUES (:publicId,:code,:name,:category,:description,'ACTIVE')")
                .param("publicId", PublicIdGenerator.next()).param("code", master.code()).param("name", master.name())
                .param("category", master.category()).param("description", master.description()).update();
    }

    private List<EmployeeSeed> employeeSeeds() {
        List<EmployeeSeed> result = new ArrayList<>();
        for (int number = 1; number <= 50; number++) {
            String employeeNo = "QI%04d".formatted(number);
            String department = department(number);
            String managerNo = managerNo(number, department);
            String email = switch (number) {
                case 1 -> "admin@query.local";
                case 2 -> "manager@query.local";
                case 3 -> "employee@query.local";
                case 4 -> "aoi.suzuki@example.invalid";
                case 5 -> "naoki.takahashi@example.invalid";
                default -> employeeNo.toLowerCase() + "@query.local";
            };
            result.add(new EmployeeSeed(number, employeeNo, LAST_NAMES.get(number - 1), FIRST_NAMES.get(number - 1),
                    email, department, managerNo, position(number, department),
                    LocalDate.of(2013 + number % 12, 1 + number % 12, 1 + number % 24)));
        }
        result.add(new EmployeeSeed(51, "QITEST", "テスト", "ユーザー", "test@query.local", "DEV", "QI0002",
                "ローカル動作確認ユーザー", LocalDate.of(2026, 7, 1)));
        return result;
    }

    private String department(int number) {
        if (number == 1) return "DEV";
        if (number <= 20) return number >= 17 ? "DATA" : "DEV";
        if (number <= 27) return "PRODUCT";
        if (number <= 34) return "SALES";
        if (number <= 39) return "CORP";
        if (number <= 43) return "HR";
        if (number <= 47) return "DATA";
        return "CS";
    }

    private String managerNo(int number, String department) {
        if (number == 1) return null;
        if (number == 2 || number == 4 || number == 5 || number == 35 || number == 40) return "QI0001";
        if (number == 17) return "QI0006";
        if (number == 48) return "QI0005";
        return switch (department) {
            case "DEV" -> number <= 9 ? "QI0002" : "QI0006";
            case "DATA" -> number <= 20 ? "QI0017" : "QI0018";
            case "PRODUCT" -> "QI0004";
            case "SALES" -> "QI0005";
            case "CORP" -> "QI0035";
            case "HR" -> "QI0040";
            case "CS" -> "QI0048";
            default -> "QI0001";
        };
    }

    private String position(int number, String department) {
        if (number == 1) return "執行役員 CTO";
        if (Set.of(2, 4, 5, 6, 17, 18, 35, 40, 48).contains(number)) return departmentName(department) + " マネージャー";
        return switch (department) {
            case "DEV" -> number % 3 == 0 ? "シニアソフトウェアエンジニア" : "ソフトウェアエンジニア";
            case "DATA" -> number % 2 == 0 ? "データエンジニア" : "データアナリスト";
            case "PRODUCT" -> number % 2 == 0 ? "プロダクトマネージャー" : "UXリサーチャー";
            case "SALES" -> number % 2 == 0 ? "シニアアカウントエグゼクティブ" : "アカウントエグゼクティブ";
            case "CS" -> "カスタマーサクセスマネージャー";
            case "HR" -> number % 2 == 0 ? "HRビジネスパートナー" : "人材開発担当";
            default -> number % 2 == 0 ? "経営企画担当" : "管理会計担当";
        };
    }

    private void ensureEmployee(EmployeeSeed seed, LocalDateTime now) {
        if (count("SELECT COUNT(*) FROM employees WHERE employee_no=:employeeNo", "employeeNo", seed.employeeNo()) > 0) return;
        Long managerId = seed.managerNo() == null ? null : employeeId(seed.managerNo());
        long departmentId = jdbc.sql("SELECT id FROM departments WHERE code=:code").param("code", seed.department())
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO employees(public_id,employee_no,last_name,first_name,email,department_id,
                  manager_employee_id,position_name,employment_status,hire_date,version,created_at,updated_at)
                VALUES (:publicId,:employeeNo,:lastName,:firstName,:email,:departmentId,:managerId,
                  :position,'ACTIVE',:hireDate,0,:now,:now)
                """).param("publicId", PublicIdGenerator.next()).param("employeeNo", seed.employeeNo())
                .param("lastName", seed.lastName()).param("firstName", seed.firstName()).param("email", seed.email())
                .param("departmentId", departmentId).param("managerId", managerId).param("position", seed.position())
                .param("hireDate", seed.hireDate()).param("now", now).update();
    }

    private void ensureAccountAndRoles(EmployeeSeed seed, LocalDateTime now) {
        long employeeId = employeeId(seed.employeeNo());
        boolean testUser = "QITEST".equals(seed.employeeNo());
        if (count("SELECT COUNT(*) FROM accounts WHERE employee_id=:employeeId", "employeeId", employeeId) == 0) {
            jdbc.sql("""
                    INSERT INTO accounts(public_id,employee_id,login_id_normalized,password_hash,status,failed_count,
                      password_changed_at,version) VALUES (:publicId,:employeeId,:loginId,:hash,'ACTIVE',0,:now,0)
                    """).param("publicId", PublicIdGenerator.next()).param("employeeId", employeeId)
                    .param("loginId", testUser ? "ueno" : seed.email().toLowerCase())
                    .param("hash", encoder.encode(testUser ? "5050Rock" : LOCAL_PASSWORD))
                    .param("now", now).update();
        }
        if (count("""
                SELECT COUNT(*) FROM permission_grants g JOIN roles r ON r.id=g.role_id
                WHERE g.account_id=(SELECT id FROM accounts WHERE employee_id=:employeeId)
                  AND r.code IN ('GENERAL','OFFICER','ADMIN') AND g.revoked_at IS NULL
                """, "employeeId", employeeId) > 0) return;
        if (seed.number() == 39) {
            grantIfMissing(employeeId, "OFFICER", "ALL", "ローカル検証用経営者権限", now);
        } else if (isManager(seed.number())) {
            grantIfMissing(employeeId, "OFFICER", "SUBORDINATES", "ローカル検証用評価者権限", now);
        } else {
            grantIfMissing(employeeId, "GENERAL", "SELF", "ローカル検証用本人権限", now);
        }
    }

    private void grantIfMissing(long employeeId, String roleCode, String scope, String reason, LocalDateTime now) {
        long accountId = jdbc.sql("SELECT id FROM accounts WHERE employee_id=:employeeId").param("employeeId", employeeId)
                .query(Long.class).single();
        long existing = jdbc.sql("""
                SELECT COUNT(*) FROM permission_grants g JOIN roles r ON r.id=g.role_id
                WHERE g.account_id=:accountId AND r.code=:roleCode AND g.revoked_at IS NULL
                """).param("accountId", accountId).param("roleCode", roleCode).query(Long.class).single();
        if (existing == 0) {
            jdbc.sql("""
                    INSERT INTO permission_grants(public_id,account_id,role_id,scope_type,valid_from,reason)
                    VALUES (:publicId,:accountId,(SELECT id FROM roles WHERE code=:roleCode),:scope,:now,:reason)
                    """).param("publicId", PublicIdGenerator.next()).param("accountId", accountId)
                    .param("roleCode", roleCode).param("scope", scope).param("now", now).param("reason", reason).update();
        }
    }

    private void ensureEvaluation(EmployeeSeed seed, LocalDateTime now) {
        long periodId = jdbc.sql("SELECT id FROM evaluation_periods WHERE status='OPEN' ORDER BY start_date DESC LIMIT 1")
                .query(Long.class).single();
        long employeeId = employeeId(seed.employeeNo());
        if (count("SELECT COUNT(*) FROM evaluation_targets WHERE period_id=:periodId AND employee_id=:employeeId",
                Map.of("periodId", periodId, "employeeId", employeeId)) > 0) return;
        long evaluatorId = seed.managerNo() == null ? employeeId : employeeId(seed.managerNo());
        double average = 0;
        List<Integer> levels = new ArrayList<>();
        for (int axis = 0; axis < 6; axis++) {
            int level = 2 + Math.floorMod(seed.number() * 7 + axis * 3, 4);
            levels.add(level);
            average += level;
        }
        average /= 6;
        String targetPublicId = PublicIdGenerator.next();
        jdbc.sql("""
                INSERT INTO evaluation_targets(public_id,period_id,employee_id,evaluator_employee_id,status,
                  provisional_score,provisional_grade,submitted_at,version)
                VALUES (:publicId,:periodId,:employeeId,:evaluatorId,'SELF_SUBMITTED',:score,:grade,:now,0)
                """).param("publicId", targetPublicId).param("periodId", periodId).param("employeeId", employeeId)
                .param("evaluatorId", evaluatorId).param("score", BigDecimal.valueOf(average))
                .param("grade", average >= 4.4 ? "A" : average >= 3.5 ? "B" : "C").param("now", now).update();
        long targetId = jdbc.sql("SELECT id FROM evaluation_targets WHERE public_id=:publicId")
                .param("publicId", targetPublicId).query(Long.class).single();
        List<String> axes = jdbc.sql("""
                SELECT c.axis_code FROM evaluation_criteria c JOIN evaluation_periods p
                  ON p.criteria_version_id=c.criteria_version_id WHERE p.id=:periodId ORDER BY c.sort_order
                """).param("periodId", periodId).query(String.class).list();
        for (int index = 0; index < axes.size(); index++) {
            jdbc.sql("""
                    INSERT INTO self_evaluation_details(target_id,axis_code,level,evidence)
                    VALUES (:targetId,:axisCode,:level,:evidence)
                    """).param("targetId", targetId).param("axisCode", axes.get(index)).param("level", levels.get(index))
                    .param("evidence", evaluationEvidence(seed, axes.get(index), levels.get(index))).update();
        }
    }

    private String evaluationEvidence(EmployeeSeed seed, String axis, int level) {
        return switch (axis) {
            case "TECHNICAL" -> "%sとして、主要業務の標準化と品質改善を担当し、レビュー指摘を前期比で削減した。自己評価レベル%d。".formatted(seed.position(), level);
            case "PROBLEM_SOLVING" -> "担当領域の課題を事実と仮説に分け、関係者と原因を整理して再発防止策まで実行した。自己評価レベル%d。".formatted(level);
            case "COMMUNICATION" -> "週次共有と意思決定ログを整備し、部署間の認識差を早期に解消した。自己評価レベル%d。".formatted(level);
            case "LEADERSHIP" -> "目標と役割を明確化し、後輩支援または小規模施策の推進を継続した。自己評価レベル%d。".formatted(level);
            case "BUSINESS" -> "%sのKPIと顧客価値を関連付け、優先順位の判断材料を提示した。自己評価レベル%d。".formatted(departmentName(seed.department()), level);
            default -> "振り返りで改善点を特定し、学習内容を次の業務で検証するサイクルを継続した。自己評価レベル%d。".formatted(level);
        };
    }

    private void ensureTalentProfile(EmployeeSeed seed, LocalDateTime now) {
        long employeeId = employeeId(seed.employeeNo());
        List<String> skills = DEPARTMENT_SKILLS.get(seed.department());
        int count = Math.min(5, skills.size());
        for (int index = 0; index < count; index++) {
            String code = skills.get(Math.floorMod(seed.number() + index, skills.size()));
            int level = 2 + Math.floorMod(seed.number() + index * 2, 4);
            ensureEmployeeSkill(employeeId, seed, code, level, index, now);
        }
        for (String knowledge : knowledgeCodes(seed.department())) {
            ensureEmployeeKnowledge(employeeId, seed, knowledge, 2 + Math.floorMod(seed.number() + knowledge.length(), 4), now);
        }
        ensureCareers(employeeId, seed, now);
        ensureCertification(employeeId, seed, now);
        ensureTalentSubmissionSnapshots(employeeId);
    }

    private void ensureTalentSubmissionSnapshots(long employeeId) {
        jdbc.sql("""
                INSERT INTO talent_submissions(public_id,employee_id,talent_type,logical_public_id,revision_no,
                  status,payload_json,base_record_version,version,decided_at,created_at,updated_at)
                SELECT es.public_id,es.employee_id,'SKILL',es.public_id,1,'APPROVED',
                  JSON_OBJECT('masterPublicId' VALUE sm.public_id,'level' VALUE es.proficiency_level,
                    'yearsExperience' VALUE es.years_experience,'lastUsedOn' VALUE es.last_used_on,
                    'evidence' VALUE es.evidence),es.version,0,es.updated_at,es.created_at,es.updated_at
                FROM employee_skills es JOIN skill_masters sm ON sm.id=es.skill_id
                WHERE es.employee_id=:employeeId AND NOT EXISTS (
                  SELECT 1 FROM talent_submissions ts WHERE ts.employee_id=es.employee_id
                    AND ts.talent_type='SKILL' AND ts.logical_public_id=es.public_id AND ts.revision_no=1)
                """).param("employeeId", employeeId).update();
        jdbc.sql("""
                INSERT INTO talent_submissions(public_id,employee_id,talent_type,logical_public_id,revision_no,
                  status,payload_json,base_record_version,version,decided_at,created_at,updated_at)
                SELECT ek.public_id,ek.employee_id,'KNOWLEDGE',ek.public_id,1,'APPROVED',
                  JSON_OBJECT('masterPublicId' VALUE km.public_id,'level' VALUE ek.proficiency_level,
                    'evidence' VALUE ek.evidence),ek.version,0,ek.updated_at,ek.created_at,ek.updated_at
                FROM employee_knowledge ek JOIN knowledge_masters km ON km.id=ek.knowledge_id
                WHERE ek.employee_id=:employeeId AND NOT EXISTS (
                  SELECT 1 FROM talent_submissions ts WHERE ts.employee_id=ek.employee_id
                    AND ts.talent_type='KNOWLEDGE' AND ts.logical_public_id=ek.public_id AND ts.revision_no=1)
                """).param("employeeId", employeeId).update();
        jdbc.sql("""
                INSERT INTO talent_submissions(public_id,employee_id,talent_type,logical_public_id,revision_no,
                  status,payload_json,base_record_version,version,decided_at,created_at,updated_at)
                SELECT ch.public_id,ch.employee_id,'CAREER',ch.public_id,1,'APPROVED',
                  JSON_OBJECT('projectName' VALUE ch.project_name,'industry' VALUE ch.industry,
                    'roleName' VALUE ch.role_name,'startDate' VALUE ch.start_date,'endDate' VALUE ch.end_date,
                    'summary' VALUE ch.summary,'achievements' VALUE ch.achievements,
                    'technologies' VALUE ch.technologies),ch.version,0,ch.updated_at,ch.created_at,ch.updated_at
                FROM career_histories ch WHERE ch.employee_id=:employeeId AND NOT EXISTS (
                  SELECT 1 FROM talent_submissions ts WHERE ts.employee_id=ch.employee_id
                    AND ts.talent_type='CAREER' AND ts.logical_public_id=ch.public_id AND ts.revision_no=1)
                """).param("employeeId", employeeId).update();
        jdbc.sql("""
                INSERT INTO talent_submissions(public_id,employee_id,talent_type,logical_public_id,revision_no,
                  status,payload_json,base_record_version,version,decided_at,created_at,updated_at)
                SELECT ec.public_id,ec.employee_id,'CERTIFICATION',ec.public_id,1,'APPROVED',
                  JSON_OBJECT('masterPublicId' VALUE cm.public_id,'acquiredOn' VALUE ec.acquired_on,
                    'expiresOn' VALUE ec.expires_on,'credentialReference' VALUE ec.credential_reference),
                  ec.version,0,ec.updated_at,ec.created_at,ec.updated_at
                FROM employee_certifications ec JOIN certification_masters cm ON cm.id=ec.certification_id
                WHERE ec.employee_id=:employeeId AND NOT EXISTS (
                  SELECT 1 FROM talent_submissions ts WHERE ts.employee_id=ec.employee_id
                    AND ts.talent_type='CERTIFICATION' AND ts.logical_public_id=ec.public_id AND ts.revision_no=1)
                """).param("employeeId", employeeId).update();
    }

    private void ensureEmployeeSkill(long employeeId, EmployeeSeed seed, String code, int level, int index,
            LocalDateTime now) {
        long skillId = jdbc.sql("SELECT id FROM skill_masters WHERE code=:code").param("code", code)
                .query(Long.class).single();
        if (count("SELECT COUNT(*) FROM employee_skills WHERE employee_id=:employeeId AND skill_id=:skillId",
                Map.of("employeeId", employeeId, "skillId", skillId)) > 0) return;
        BigDecimal years = BigDecimal.valueOf(Math.min(12, 1 + Math.floorMod(seed.number() + index * 2, 9)) + 0.5);
        jdbc.sql("""
                INSERT INTO employee_skills(public_id,employee_id,skill_id,proficiency_level,years_experience,
                  last_used_on,evidence,version,created_at,updated_at)
                VALUES (:publicId,:employeeId,:skillId,:level,:years,:lastUsedOn,:evidence,0,:now,:now)
                """).param("publicId", PublicIdGenerator.next()).param("employeeId", employeeId).param("skillId", skillId)
                .param("level", level).param("years", years).param("lastUsedOn", REFERENCE_DATE.minusDays(index * 18L))
                .param("evidence", "%sの実務で%sを活用し、成果物レビューと改善を継続した。".formatted(departmentName(seed.department()), code))
                .param("now", now).update();
    }

    private void ensureEmployeeKnowledge(long employeeId, EmployeeSeed seed, String code, int level, LocalDateTime now) {
        long knowledgeId = jdbc.sql("SELECT id FROM knowledge_masters WHERE code=:code").param("code", code)
                .query(Long.class).single();
        if (count("SELECT COUNT(*) FROM employee_knowledge WHERE employee_id=:employeeId AND knowledge_id=:knowledgeId",
                Map.of("employeeId", employeeId, "knowledgeId", knowledgeId)) > 0) return;
        jdbc.sql("""
                INSERT INTO employee_knowledge(public_id,employee_id,knowledge_id,proficiency_level,evidence,
                  version,created_at,updated_at)
                VALUES (:publicId,:employeeId,:knowledgeId,:level,:evidence,0,:now,:now)
                """).param("publicId", PublicIdGenerator.next()).param("employeeId", employeeId)
                .param("knowledgeId", knowledgeId).param("level", level)
                .param("evidence", "社内勉強会と実案件で知識を適用し、判断根拠を文書化してチームへ共有した。")
                .param("now", now).update();
    }

    private void ensureCareers(long employeeId, EmployeeSeed seed, LocalDateTime now) {
        int tenure = Math.max(1, REFERENCE_DATE.getYear() - seed.hireDate().getYear());
        LocalDate currentStart = REFERENCE_DATE.minusYears(Math.min(3, tenure)).withDayOfMonth(1);
        ensureCareer(employeeId, seed, "全社業務基盤高度化", currentStart, null,
                "部門横断メンバー", "複数部署の業務フローを整理し、共通基盤への移行を推進した。",
                "担当領域のリードタイム短縮と運用品質の安定化に貢献した。", now);
        if (tenure >= 3) {
            LocalDate previousStart = currentStart.minusYears(Math.min(3, tenure - 1));
            ensureCareer(employeeId, seed, departmentName(seed.department()) + "改善プロジェクト", previousStart,
                    currentStart.minusDays(1), seed.position(), "現場課題の可視化から施策実行、効果測定までを担当した。",
                    "定例レビューと標準手順を整備し、属人化した業務を再現可能にした。", now);
        }
    }

    private void ensureCareer(long employeeId, EmployeeSeed seed, String projectName, LocalDate start, LocalDate end,
            String role, String summary, String achievements, LocalDateTime now) {
        if (count("""
                SELECT COUNT(*) FROM career_histories
                WHERE employee_id=:employeeId AND project_name=:projectName AND start_date=:startDate
                """, Map.of("employeeId", employeeId, "projectName", projectName, "startDate", start)) > 0) return;
        String technologies = String.join("、", DEPARTMENT_SKILLS.get(seed.department()).stream().limit(4).toList());
        jdbc.sql("""
                INSERT INTO career_histories(public_id,employee_id,project_name,industry,role_name,start_date,end_date,
                  summary,achievements,technologies,version,created_at,updated_at)
                VALUES (:publicId,:employeeId,:projectName,'B2B SaaS',:role,:startDate,:endDate,
                  :summary,:achievements,:technologies,0,:now,:now)
                """).param("publicId", PublicIdGenerator.next()).param("employeeId", employeeId)
                .param("projectName", projectName).param("role", role).param("startDate", start).param("endDate", end)
                .param("summary", summary).param("achievements", achievements).param("technologies", technologies)
                .param("now", now).update();
    }

    private void ensureCertification(long employeeId, EmployeeSeed seed, LocalDateTime now) {
        String code = switch (seed.department()) {
            case "DEV" -> seed.number() % 2 == 0 ? "AWS_SAA" : "IPA_AP";
            case "DATA" -> seed.number() % 2 == 0 ? "IPA_DB" : "STAT_2";
            case "PRODUCT" -> seed.number() % 2 == 0 ? "SCRUM" : "PMP";
            case "HR" -> "CAREER";
            case "CORP" -> "BOOKKEEPING_2";
            default -> seed.number() % 2 == 0 ? "PMP" : "SCRUM";
        };
        long certificationId = jdbc.sql("SELECT id FROM certification_masters WHERE code=:code").param("code", code)
                .query(Long.class).single();
        if (count("SELECT COUNT(*) FROM employee_certifications WHERE employee_id=:employeeId AND certification_id=:certificationId",
                Map.of("employeeId", employeeId, "certificationId", certificationId)) > 0) return;
        LocalDate acquired = REFERENCE_DATE.minusYears(1 + seed.number() % 5).minusMonths(seed.number() % 10);
        jdbc.sql("""
                INSERT INTO employee_certifications(public_id,employee_id,certification_id,acquired_on,expires_on,
                  credential_reference,verification_status,version,created_at,updated_at)
                VALUES (:publicId,:employeeId,:certificationId,:acquiredOn,NULL,:reference,'VERIFIED',0,:now,:now)
                """).param("publicId", PublicIdGenerator.next()).param("employeeId", employeeId)
                .param("certificationId", certificationId).param("acquiredOn", acquired)
                .param("reference", "LOCAL-%04d".formatted(seed.number())).param("now", now).update();
    }

    private List<String> knowledgeCodes(String department) {
        return switch (department) {
            case "DEV" -> List.of("SYSTEM_DESIGN", "SECURITY", "SRE");
            case "DATA" -> List.of("DATA_GOVERNANCE", "SYSTEM_DESIGN", "PRIVACY");
            case "PRODUCT" -> List.of("SAAS_METRICS", "CUSTOMER_OPERATION", "PRIVACY");
            case "SALES" -> List.of("B2B_MARKET", "SAAS_METRICS", "CUSTOMER_OPERATION");
            case "CS" -> List.of("CUSTOMER_OPERATION", "SAAS_METRICS", "B2B_MARKET");
            case "HR" -> List.of("HR_POLICY", "PRIVACY", "FINANCE");
            default -> List.of("FINANCE", "PRIVACY", "SAAS_METRICS");
        };
    }

    private long employeeId(String employeeNo) {
        return jdbc.sql("SELECT id FROM employees WHERE employee_no=:employeeNo").param("employeeNo", employeeNo)
                .query(Long.class).single();
    }

    private long count(String sql, String key, Object value) {
        return jdbc.sql(sql).param(key, value).query(Long.class).single();
    }

    private long count(String sql, Map<String, ?> parameters) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        for (Map.Entry<String, ?> entry : parameters.entrySet()) statement = statement.param(entry.getKey(), entry.getValue());
        return statement.query(Long.class).single();
    }

    private boolean isManager(int number) {
        return Set.of(1, 2, 4, 5, 6, 17, 18, 35, 40, 48).contains(number);
    }

    private String departmentName(String code) {
        return switch (code) {
            case "DEV" -> "プロダクト開発";
            case "DATA" -> "データプラットフォーム";
            case "PRODUCT" -> "プロダクト企画";
            case "SALES" -> "法人営業";
            case "CS" -> "カスタマーサクセス";
            case "HR" -> "人事・組織開発";
            default -> "経営管理";
        };
    }

    private record EmployeeSeed(int number, String employeeNo, String lastName, String firstName, String email,
            String department, String managerNo, String position, LocalDate hireDate) {
    }

    private record MasterSeed(String code, String name, String category, String description) {
    }

    private record CertificationSeed(String code, String name, String issuer) {
    }
}
