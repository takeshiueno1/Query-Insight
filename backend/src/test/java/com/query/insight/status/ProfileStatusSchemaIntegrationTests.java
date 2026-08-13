package com.query.insight.status;

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
class ProfileStatusSchemaIntegrationTests {
    @Autowired
    private JdbcClient jdbc;

    @Test
    void profileStatusSnapshotSchemaLinksToManagerEvaluations() {
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name='profile_status_snapshots' AND column_name IN
                  ('skill_score','knowledge_score','career_score','certification_score',
                   'total_score','grade','formula_version','source_fingerprint')
                """).query(Integer.class).single()).isEqualTo(8);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name='manager_evaluations' AND column_name='profile_status_snapshot_id'
                """).query(Integer.class).single()).isEqualTo(1);

        long snapshotId = insertSnapshot("01S00000000000000000000001", "S", 90.00, "1".repeat(64));
        long evaluationId = jdbc.sql("SELECT id FROM manager_evaluations ORDER BY id LIMIT 1")
                .query(Long.class).single();
        assertThat(jdbc.sql("""
                UPDATE manager_evaluations SET profile_status_snapshot_id=:snapshotId WHERE id=:evaluationId
                """).param("snapshotId", snapshotId).param("evaluationId", evaluationId).update()).isEqualTo(1);
    }

    @Test
    void profileStatusSnapshotRejectsInvalidGradeAndScores() {
        assertThatThrownBy(() -> insertSnapshot("01S00000000000000000000002", "X", 90.00, "2".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSnapshot("01S00000000000000000000003", "A", 100.01, "3".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long insertSnapshot(String publicId, String grade, double totalScore, String fingerprint) {
        Long employeeId = jdbc.sql("SELECT id FROM employees ORDER BY id LIMIT 1").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO profile_status_snapshots(public_id,employee_id,skill_score,knowledge_score,career_score,
                  certification_score,total_score,grade,formula_version,source_fingerprint,calculated_at)
                VALUES (:publicId,:employeeId,40.00,20.00,25.00,15.00,:totalScore,:grade,'2026.08.13',
                  :fingerprint,CURRENT_TIMESTAMP)
                """).param("publicId", publicId).param("employeeId", employeeId).param("totalScore", totalScore)
                .param("grade", grade).param("fingerprint", fingerprint)
                .update();
        return jdbc.sql("SELECT id FROM profile_status_snapshots WHERE public_id=:publicId")
                .param("publicId", publicId).query(Long.class).single();
    }
}
