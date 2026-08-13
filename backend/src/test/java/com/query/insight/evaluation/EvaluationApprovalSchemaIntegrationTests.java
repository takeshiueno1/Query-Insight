package com.query.insight.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }
}
