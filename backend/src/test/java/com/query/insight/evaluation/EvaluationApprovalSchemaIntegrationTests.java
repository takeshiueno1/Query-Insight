package com.query.insight.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }
}
