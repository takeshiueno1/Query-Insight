package com.query.insight.config;

import com.query.insight.common.PublicIdGenerator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
@Order(10)
public class LocalDataInitializer implements ApplicationRunner {
    private static final String LOCAL_PASSWORD = "QueryInsight2026";
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;

    public LocalDataInitializer(JdbcClient jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (jdbc.sql("SELECT COUNT(*) FROM accounts").query(Long.class).single() > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long headquartersId = department("HQ", "コーポレート本部", null, now);
        long developmentId = department("DEV", "プロダクト開発本部", headquartersId, now);
        long productId = department("PRODUCT", "プロダクト企画部", headquartersId, now);
        long salesId = department("SALES", "法人営業部", headquartersId, now);

        String adminPublicId = employee("QI0001", "管理", "太郎", "admin@query.local", developmentId, null,
                "開発本部長", LocalDate.of(2015, 4, 1), now);
        long adminEmployeeId = employeeId(adminPublicId);
        String managerPublicId = employee("QI0002", "山田", "花子", "manager@query.local", developmentId,
                adminEmployeeId, "エンジニアリングマネージャー", LocalDate.of(2018, 10, 1), now);
        long managerEmployeeId = employeeId(managerPublicId);
        String employeePublicId = employee("QI0003", "佐藤", "健", "employee@query.local", developmentId,
                managerEmployeeId, "シニアソフトウェアエンジニア", LocalDate.of(2021, 4, 1), now);
        long employeeId = employeeId(employeePublicId);
        String productEmployeePublicId = employee("QI0004", "鈴木", "葵", "aoi.suzuki@example.invalid", productId,
                adminEmployeeId, "プロダクトマネージャー", LocalDate.of(2020, 7, 1), now);
        long productEmployeeId = employeeId(productEmployeePublicId);
        String salesEmployeePublicId = employee("QI0005", "高橋", "直樹", "naoki.takahashi@example.invalid", salesId,
                adminEmployeeId, "アカウントエグゼクティブ", LocalDate.of(2019, 1, 15), now);
        long salesEmployeeId = employeeId(salesEmployeePublicId);

        List.of("GENERAL", "OFFICER", "ADMIN").forEach(role ->
                jdbc.sql("INSERT INTO roles(code,name,status) SELECT :code,:name,'ACTIVE' "
                                + "WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code=:code)")
                        .param("code", role).param("name", role).update());

        long adminAccount = account(adminEmployeeId, "admin@query.local", now);
        long managerAccount = account(managerEmployeeId, "manager@query.local", now);
        long employeeAccount = account(employeeId, "employee@query.local", now);
        grant(adminAccount, "ADMIN", "ALL", "ローカル検証用管理者", now);
        grant(managerAccount, "OFFICER", "SUBORDINATES", "開発チームの評価責任者", now);
        grant(employeeAccount, "GENERAL", "SELF", "本人権限", now);

        long criteriaVersion = criteriaVersion(now);
        long period = evaluationPeriod(criteriaVersion, now);
        seedEvaluation(period, adminEmployeeId, adminEmployeeId, List.of(4, 4, 4, 4, 5, 4), now);
        seedEvaluation(period, managerEmployeeId, adminEmployeeId, List.of(4, 5, 4, 4, 4, 4), now);
        seedEvaluation(period, employeeId, managerEmployeeId, List.of(4, 3, 4, 3, 4, 5), now);
        seedEvaluation(period, productEmployeeId, adminEmployeeId, List.of(3, 4, 5, 4, 4, 4), now);
        seedEvaluation(period, salesEmployeeId, adminEmployeeId, List.of(3, 3, 5, 5, 4, 4), now);

    }

    private long department(String code, String name, Long parentId, LocalDateTime now) {
        String publicId = PublicIdGenerator.next();
        jdbc.sql("""
                INSERT INTO departments(public_id,code,name,parent_id,status,version,created_at,updated_at)
                VALUES (:publicId,:code,:name,:parentId,'ACTIVE',0,:now,:now)
                """).param("publicId", publicId).param("code", code).param("name", name)
                .param("parentId", parentId).param("now", now).update();
        return jdbc.sql("SELECT id FROM departments WHERE public_id=:publicId").param("publicId", publicId)
                .query(Long.class).single();
    }

    private String employee(String employeeNo, String lastName, String firstName, String email, long departmentId,
            Long managerId, String position, LocalDate hireDate, LocalDateTime now) {
        String publicId = PublicIdGenerator.next();
        jdbc.sql("""
                INSERT INTO employees(public_id,employee_no,last_name,first_name,email,department_id,
                  manager_employee_id,position_name,employment_status,hire_date,version,created_at,updated_at)
                VALUES (:publicId,:employeeNo,:lastName,:firstName,:email,:departmentId,:managerId,:position,
                  'ACTIVE',:hireDate,0,:now,:now)
                """).param("publicId", publicId).param("employeeNo", employeeNo).param("lastName", lastName)
                .param("firstName", firstName).param("email", email).param("departmentId", departmentId)
                .param("managerId", managerId).param("position", position).param("hireDate", hireDate)
                .param("now", now).update();
        return publicId;
    }

    private long employeeId(String publicId) {
        return jdbc.sql("SELECT id FROM employees WHERE public_id=:publicId").param("publicId", publicId)
                .query(Long.class).single();
    }

    private long account(long employeeId, String loginId, LocalDateTime now) {
        String publicId = PublicIdGenerator.next();
        jdbc.sql("""
                INSERT INTO accounts(public_id,employee_id,login_id_normalized,password_hash,status,failed_count,
                  password_changed_at,version) VALUES (:publicId,:employeeId,:loginId,:hash,'ACTIVE',0,:now,0)
                """).param("publicId", publicId).param("employeeId", employeeId)
                .param("loginId", loginId).param("hash", encoder.encode(LOCAL_PASSWORD)).param("now", now).update();
        return jdbc.sql("SELECT id FROM accounts WHERE public_id=:publicId").param("publicId", publicId)
                .query(Long.class).single();
    }

    private void grant(long accountId, String roleCode, String scope, String reason, LocalDateTime now) {
        jdbc.sql("""
                INSERT INTO permission_grants(public_id,account_id,role_id,scope_type,valid_from,reason)
                VALUES (:publicId,:accountId,(SELECT id FROM roles WHERE code=:roleCode),:scope,:now,:reason)
                """).param("publicId", PublicIdGenerator.next()).param("accountId", accountId)
                .param("roleCode", roleCode).param("scope", scope).param("now", now).param("reason", reason).update();
    }

    private long criteriaVersion(LocalDateTime now) {
        String publicId = PublicIdGenerator.next();
        jdbc.sql("""
                INSERT INTO evaluation_criteria_versions(public_id,version_name,effective_from,status,
                  grade_boundaries_json,published_at,version)
                VALUES (:publicId,'2026-H2-local',:effectiveFrom,'PUBLISHED',
                  CAST(:boundaries AS JSONB),:now,0)
                """).param("publicId", publicId).param("effectiveFrom", LocalDate.of(2026, 7, 1))
                .param("boundaries", "{\"S\":4.50,\"A\":4.00,\"B\":3.00,\"C\":0.00}")
                .param("now", now).update();
        long id = jdbc.sql("SELECT id FROM evaluation_criteria_versions WHERE public_id=:publicId")
                .param("publicId", publicId).query(Long.class).single();
        axes().forEach(axis -> jdbc.sql("""
                INSERT INTO evaluation_criteria(criteria_version_id,axis_code,display_name,description,weight,sort_order)
                VALUES (:versionId,:code,:name,:description,1.0,:sortOrder)
                """).param("versionId", id).param("code", axis.code()).param("name", axis.name())
                .param("description", axis.description()).param("sortOrder", axis.sortOrder()).update());
        return id;
    }

    private long evaluationPeriod(long criteriaVersion, LocalDateTime now) {
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        String publicId = PublicIdGenerator.next();
        jdbc.sql("""
                INSERT INTO evaluation_periods(public_id,name,start_date,end_date,self_deadline,manager_deadline,
                  criteria_version_id,status,version)
                VALUES (:publicId,:name,:startDate,:endDate,:selfDeadline,:managerDeadline,:criteriaVersion,'OPEN',0)
                """).param("publicId", publicId).param("name", year + "年度 下期評価")
                .param("startDate", LocalDate.of(year, 7, 1)).param("endDate", LocalDate.of(year, 12, 31))
                .param("selfDeadline", LocalDateTime.of(year, 12, 15, 23, 59))
                .param("managerDeadline", LocalDateTime.of(year, 12, 25, 23, 59))
                .param("criteriaVersion", criteriaVersion).update();
        return jdbc.sql("SELECT id FROM evaluation_periods WHERE public_id=:publicId")
                .param("publicId", publicId).query(Long.class).single();
    }

    private void seedEvaluation(long periodId, long employeeId, long evaluatorId, List<Integer> levels,
            LocalDateTime now) {
        String targetPublicId = PublicIdGenerator.next();
        double score = levels.stream().mapToInt(Integer::intValue).average().orElseThrow();
        jdbc.sql("""
                INSERT INTO evaluation_targets(public_id,period_id,employee_id,evaluator_employee_id,status,
                  provisional_score,provisional_grade,submitted_at,version)
                VALUES (:publicId,:periodId,:employeeId,:evaluatorId,'SELF_SUBMITTED',:score,:grade,:submittedAt,1)
                """).param("publicId", targetPublicId).param("periodId", periodId).param("employeeId", employeeId)
                .param("evaluatorId", evaluatorId).param("score", score).param("grade", grade(score))
                .param("submittedAt", now.minusDays(1)).update();
        long targetId = jdbc.sql("SELECT id FROM evaluation_targets WHERE public_id=:publicId")
                .param("publicId", targetPublicId).query(Long.class).single();
        List<Axis> axes = axes();
        for (int index = 0; index < axes.size(); index++) {
            Axis axis = axes.get(index);
            jdbc.sql("""
                    INSERT INTO self_evaluation_details(target_id,axis_code,level,evidence)
                    VALUES (:targetId,:axisCode,:level,:evidence)
                    """).param("targetId", targetId).param("axisCode", axis.code()).param("level", levels.get(index))
                    .param("evidence", evidence(axis.code(), levels.get(index))).update();
        }
    }

    private static String grade(double score) {
        if (score >= 4.5) return "S";
        if (score >= 4.0) return "A";
        if (score >= 3.0) return "B";
        return "C";
    }

    private static String evidence(String axisCode, int level) {
        return switch (axisCode) {
            case "TECHNICAL" -> "主要サービスの障害原因を切り分け、恒久対策と再発防止テストを実装した。評価レベル" + level + "相当。";
            case "DESIGN" -> "性能・セキュリティ・運用制約を設計レビューで明文化し、段階移行できる構成を提案した。";
            case "BUSINESS" -> "利用部門へのヒアリングから業務上の判断基準を整理し、受け入れ条件へ反映した。";
            case "COMMUNICATION" -> "技術的な選択肢とリスクを非技術部門にも説明し、関係者間の合意形成を支援した。";
            case "DELIVERY" -> "依存タスクとリスクを週次で更新し、重要マイルストーンを期限内に完了した。";
            case "IMPROVEMENT" -> "振り返りで検出した手戻り要因を自動検査へ組み込み、同種不具合を削減した。";
            default -> throw new IllegalArgumentException("Unknown axis: " + axisCode);
        };
    }

    private static List<Axis> axes() {
        return List.of(
                new Axis("TECHNICAL", "技術力", "技術を適切に選択・活用できる", 1),
                new Axis("DESIGN", "設計力", "制約を踏まえ保守可能な設計ができる", 2),
                new Axis("BUSINESS", "業務理解", "業務目的と利用者価値を理解できる", 3),
                new Axis("COMMUNICATION", "説明力", "根拠を明確に共有し合意形成できる", 4),
                new Axis("DELIVERY", "推進力", "課題を管理し成果まで推進できる", 5),
                new Axis("IMPROVEMENT", "改善力", "振り返りから継続的に改善できる", 6));
    }

    record Axis(String code, String name, String description, int sortOrder) {
    }
}
