package com.query.insight.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class EvaluationApprovalSchemaIntegrationTests {
    @Autowired
    private JdbcClient jdbc;

    @Test
    void evaluationApprovalSchemaAndExecutiveRoleExist() {
        assertThat(count("manager_evaluations")).isPositive();
        assertThat(count("manager_evaluation_details")).isPositive();
        assertThat(count("evaluation_workflow_events")).isPositive();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM roles WHERE code='EXECUTIVE'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name='evaluation_targets' AND column_name IN
                  ('current_manager_evaluation_id','final_score','final_grade','finalized_at')
                """).query(Integer.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name='evaluation_workflow_events' AND column_name='deadline_type'
                """).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void managerEvaluationDetailAllowsZeroButRejectsSix() {
        long evaluationId = jdbc.sql("SELECT id FROM manager_evaluations ORDER BY id LIMIT 1")
                .query(Long.class).single();
        assertThat(jdbc.sql("""
                INSERT INTO manager_evaluation_details(manager_evaluation_id,axis_code,level,comment)
                VALUES (:evaluationId,'TASK1_LEVEL_ZERO',0,'未評価')
                """).param("evaluationId", evaluationId).update()).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO manager_evaluation_details(manager_evaluation_id,axis_code,level,comment)
                VALUES (:evaluationId,'TASK1_LEVEL_SIX',6,'範囲外')
                """).param("evaluationId", evaluationId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rankScoresCanPersistOneHundredWithoutLosingTwoDecimalPlaces() {
        assertThat(decimalShape("manager_evaluations", "weighted_score"))
                .isEqualTo(new DecimalShape(5, 2));
        assertThat(decimalShape("evaluation_targets", "final_score"))
                .isEqualTo(new DecimalShape(5, 2));
    }

    @Test
    void v9RecalculatesLegacyManagerAndFinalScoresWhileLeavingDetailFreeRowsUntouched() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:legacy_rank_score;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "sa", "", true);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("8")).load().migrate();
        JdbcClient legacyJdbc = JdbcClient.create(dataSource);
        insertLegacyRankEvaluations(legacyJdbc);

        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        flyway.migrate();

        assertThat(managerScore(legacyJdbc, "08R00000000000000000000001"))
                .isEqualTo(new ScoreAndGrade("63.33", "C"));
        assertThat(targetScore(legacyJdbc, "07R00000000000000000000001"))
                .isEqualTo(new ScoreAndGrade("63.33", "C"));
        assertThat(managerScore(legacyJdbc, "08R00000000000000000000002"))
                .isEqualTo(new ScoreAndGrade("4.25", "A"));
        assertThat(targetScore(legacyJdbc, "07R00000000000000000000002"))
                .isEqualTo(new ScoreAndGrade("2.50", "C"));
        assertThat(managerScore(legacyJdbc, "08R00000000000000000000003"))
                .isEqualTo(new ScoreAndGrade(null, null));
        assertThat(targetScore(legacyJdbc, "07R00000000000000000000003"))
                .isEqualTo(new ScoreAndGrade(null, null));
        assertThat(managerScore(legacyJdbc, "08R00000000000000000000004"))
                .isEqualTo(new ScoreAndGrade("63.33", "C"));
        assertThat(targetScore(legacyJdbc, "07R00000000000000000000004"))
                .isEqualTo(new ScoreAndGrade(null, null));
        assertThat(flyway.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("9"));
    }

    private void insertLegacyRankEvaluations(JdbcClient legacyJdbc) {
        legacyJdbc.sql("""
                INSERT INTO departments(public_id,code,name,status,version,created_at,updated_at)
                VALUES ('01R00000000000000000000001','LEGACY_RANK','旧評価部','ACTIVE',0,
                  CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).update();
        legacyJdbc.sql("""
                INSERT INTO employees(public_id,employee_no,last_name,first_name,email,department_id,
                  employment_status,version,created_at,updated_at)
                VALUES
                  ('01R00000000000000000000002','QI-RANK-MGR','旧','評価者','rank-manager@example.invalid',
                    (SELECT id FROM departments WHERE code='LEGACY_RANK'),'ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
                  ('01R00000000000000000000003','QI-RANK-01','旧','対象一','rank-one@example.invalid',
                    (SELECT id FROM departments WHERE code='LEGACY_RANK'),'ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
                  ('01R00000000000000000000004','QI-RANK-02','旧','対象二','rank-two@example.invalid',
                    (SELECT id FROM departments WHERE code='LEGACY_RANK'),'ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
                  ('01R00000000000000000000005','QI-RANK-03','旧','対象三','rank-three@example.invalid',
                    (SELECT id FROM departments WHERE code='LEGACY_RANK'),'ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
                  ('01R00000000000000000000006','QI-RANK-04','旧','対象四','rank-four@example.invalid',
                    (SELECT id FROM departments WHERE code='LEGACY_RANK'),'ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).update();
        legacyJdbc.sql("""
                INSERT INTO evaluation_criteria_versions(public_id,version_name,effective_from,status,
                  grade_boundaries_json,published_at,version)
                VALUES ('04R00000000000000000000001','legacy-rank-v1',DATE '2026-01-01','ACTIVE',
                  CAST('{"S":4.50,"A":4.00,"B":3.00,"C":0.00}' AS JSONB),CURRENT_TIMESTAMP,0)
                """).update();
        legacyJdbc.sql("""
                INSERT INTO evaluation_periods(public_id,name,start_date,end_date,self_deadline,manager_deadline,
                  criteria_version_id,status,version)
                VALUES ('06R00000000000000000000001','旧ランク期間',DATE '2026-01-01',DATE '2026-12-31',
                  CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                  (SELECT id FROM evaluation_criteria_versions WHERE version_name='legacy-rank-v1'),'OPEN',0)
                """).update();
        legacyJdbc.sql("""
                INSERT INTO evaluation_targets(public_id,period_id,employee_id,evaluator_employee_id,status,
                  final_score,final_grade,finalized_at,version)
                SELECT '07R00000000000000000000001',p.id,e.id,m.id,'FINALIZED',3.33,'B',CURRENT_TIMESTAMP,0
                FROM evaluation_periods p,employees e,employees m
                WHERE p.name='旧ランク期間' AND e.employee_no='QI-RANK-01' AND m.employee_no='QI-RANK-MGR'
                """).update();
        legacyJdbc.sql("""
                INSERT INTO evaluation_targets(public_id,period_id,employee_id,evaluator_employee_id,status,
                  final_score,final_grade,finalized_at,version)
                SELECT '07R00000000000000000000002',p.id,e.id,m.id,'FINALIZED',2.50,'C',CURRENT_TIMESTAMP,0
                FROM evaluation_periods p,employees e,employees m
                WHERE p.name='旧ランク期間' AND e.employee_no='QI-RANK-02' AND m.employee_no='QI-RANK-MGR'
                """).update();
        legacyJdbc.sql("""
                INSERT INTO evaluation_targets(public_id,period_id,employee_id,evaluator_employee_id,status,version)
                SELECT '07R00000000000000000000003',p.id,e.id,m.id,'DRAFT',0
                FROM evaluation_periods p,employees e,employees m
                WHERE p.name='旧ランク期間' AND e.employee_no='QI-RANK-03' AND m.employee_no='QI-RANK-MGR'
                """).update();
        legacyJdbc.sql("""
                INSERT INTO evaluation_targets(public_id,period_id,employee_id,evaluator_employee_id,status,version)
                SELECT '07R00000000000000000000004',p.id,e.id,m.id,'EXECUTIVE_REVIEW',0
                FROM evaluation_periods p,employees e,employees m
                WHERE p.name='旧ランク期間' AND e.employee_no='QI-RANK-04' AND m.employee_no='QI-RANK-MGR'
                """).update();
        legacyJdbc.sql("""
                INSERT INTO manager_evaluations(public_id,target_id,revision_no,status,summary,weighted_score,grade,
                  submitted_at,finalized_at,version)
                SELECT '08R00000000000000000000001',id,1,'FINALIZED','旧尺度',3.33,'B',
                  CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0 FROM evaluation_targets
                WHERE public_id='07R00000000000000000000001'
                """).update();
        legacyJdbc.sql("""
                INSERT INTO manager_evaluations(public_id,target_id,revision_no,status,summary,weighted_score,grade,
                  submitted_at,finalized_at,version)
                SELECT '08R00000000000000000000002',id,1,'FINALIZED','明細なし',4.25,'A',
                  CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0 FROM evaluation_targets
                WHERE public_id='07R00000000000000000000002'
                """).update();
        legacyJdbc.sql("""
                INSERT INTO manager_evaluations(public_id,target_id,revision_no,status,summary,version)
                SELECT '08R00000000000000000000003',id,1,'DRAFT','未提出',0 FROM evaluation_targets
                WHERE public_id='07R00000000000000000000003'
                """).update();
        legacyJdbc.sql("""
                INSERT INTO manager_evaluations(public_id,target_id,revision_no,status,summary,submitted_at,version)
                SELECT '08R00000000000000000000004',id,1,'SUBMITTED','承認待ち',CURRENT_TIMESTAMP,0
                FROM evaluation_targets WHERE public_id='07R00000000000000000000004'
                """).update();
        legacyJdbc.sql("""
                INSERT INTO manager_evaluation_details(manager_evaluation_id,axis_code,level,comment)
                SELECT m.id,v.axis_code,v.level,'旧評価' FROM manager_evaluations m
                CROSS JOIN (VALUES ('TECHNICAL',5),('DESIGN',4),('BUSINESS',3),
                  ('COMMUNICATION',2),('DELIVERY',1),('IMPROVEMENT',0)) v(axis_code,level)
                WHERE m.public_id IN ('08R00000000000000000000001','08R00000000000000000000003',
                  '08R00000000000000000000004')
                """).update();
        legacyJdbc.sql("""
                UPDATE evaluation_targets SET current_manager_evaluation_id=(
                  SELECT id FROM manager_evaluations WHERE public_id='08R00000000000000000000001')
                WHERE public_id='07R00000000000000000000001'
                """).update();
        legacyJdbc.sql("""
                UPDATE evaluation_targets SET current_manager_evaluation_id=(
                  SELECT id FROM manager_evaluations WHERE public_id='08R00000000000000000000002')
                WHERE public_id='07R00000000000000000000002'
                """).update();
        legacyJdbc.sql("""
                UPDATE evaluation_targets SET current_manager_evaluation_id=(
                  SELECT id FROM manager_evaluations WHERE public_id='08R00000000000000000000003')
                WHERE public_id='07R00000000000000000000003'
                """).update();
        legacyJdbc.sql("""
                UPDATE evaluation_targets SET current_manager_evaluation_id=(
                  SELECT id FROM manager_evaluations WHERE public_id='08R00000000000000000000004')
                WHERE public_id='07R00000000000000000000004'
                """).update();
    }

    private ScoreAndGrade managerScore(JdbcClient legacyJdbc, String publicId) {
        return legacyJdbc.sql("SELECT weighted_score,grade FROM manager_evaluations WHERE public_id=:publicId")
                .param("publicId", publicId).query((rs, row) -> new ScoreAndGrade(
                        decimalText(rs.getBigDecimal("weighted_score")), rs.getString("grade"))).single();
    }

    private ScoreAndGrade targetScore(JdbcClient legacyJdbc, String publicId) {
        return legacyJdbc.sql("SELECT final_score,final_grade FROM evaluation_targets WHERE public_id=:publicId")
                .param("publicId", publicId).query((rs, row) -> new ScoreAndGrade(
                        decimalText(rs.getBigDecimal("final_score")), rs.getString("final_grade"))).single();
    }

    private static String decimalText(java.math.BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private DecimalShape decimalShape(String table, String column) {
        return jdbc.sql("""
                SELECT numeric_precision,numeric_scale FROM information_schema.columns
                WHERE table_name=:tableName AND column_name=:columnName
                """).param("tableName", table).param("columnName", column)
                .query((rs, row) -> new DecimalShape(rs.getInt("numeric_precision"), rs.getInt("numeric_scale")))
                .single();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private record DecimalShape(int precision, int scale) {
    }

    private record ScoreAndGrade(String score, String grade) {
    }
}
