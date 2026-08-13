package com.query.insight.talent;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TalentSubmissionSchemaIntegrationTests {
    private static final String EMPLOYEE_PUBLIC_ID = "01J00000000000000000000001";
    private static final String SKILL_PUBLIC_ID = "01J00000000000000000000002";
    private static final String KNOWLEDGE_PUBLIC_ID = "01J00000000000000000000003";
    private static final String CERTIFICATION_PUBLIC_ID = "01J00000000000000000000004";

    @Test
    void migratesV4TalentProfilesIntoApprovedSubmissionRevisions() {
        DataSource dataSource = dataSource();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("4"))
                .load()
                .migrate();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        insertExistingTalentProfile(jdbc);

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("6"));
        assertThat(count(jdbc, "talent_submissions")).isEqualTo(4);
        assertThat(count(jdbc, "talent_submission_events")).isZero();
        assertThat(count(jdbc, "talent_attachments")).isZero();
        assertThat(count(jdbc, "master_addition_requests")).isZero();
        assertThat(jdbc.sql("""
                SELECT talent_type, status, revision_no, logical_public_id
                FROM talent_submissions
                ORDER BY talent_type
                """).query((rs, rowNum) -> String.join(":",
                        rs.getString("talent_type"),
                        rs.getString("status"),
                        Integer.toString(rs.getInt("revision_no")),
                        rs.getString("logical_public_id").trim())).list())
                .containsExactly(
                        "CAREER:APPROVED:1:01J00000000000000000000007",
                        "CERTIFICATION:APPROVED:1:01J00000000000000000000008",
                        "KNOWLEDGE:APPROVED:1:01J00000000000000000000006",
                        "SKILL:APPROVED:1:01J00000000000000000000005");
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:talent_schema;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    private void insertExistingTalentProfile(JdbcClient jdbc) {
        jdbc.sql("""
                INSERT INTO departments(id,public_id,code,name,status,version,created_at,updated_at)
                VALUES (1,'01J00000000000000000000000','DEV','開発部','ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).update();
        jdbc.sql("""
                INSERT INTO employees(id,public_id,employee_no,last_name,first_name,email,department_id,
                  employment_status,version,created_at,updated_at)
                VALUES (1,:publicId,'QI-TALENT-1','試験','社員','talent-test@example.invalid',1,
                  'ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).param("publicId", EMPLOYEE_PUBLIC_ID).update();
        jdbc.sql("""
                INSERT INTO skill_masters(id,public_id,code,name,category,description,status)
                VALUES (1,:publicId,'JAVA','Java','ENGINEERING','Java開発','ACTIVE')
                """).param("publicId", SKILL_PUBLIC_ID).update();
        jdbc.sql("""
                INSERT INTO knowledge_masters(id,public_id,code,name,category,description,status)
                VALUES (1,:publicId,'DOMAIN','業務知識','BUSINESS','業務知識','ACTIVE')
                """).param("publicId", KNOWLEDGE_PUBLIC_ID).update();
        jdbc.sql("""
                INSERT INTO certification_masters(id,public_id,code,name,issuer,status)
                VALUES (1,:publicId,'CERT','認定資格','認定機関','ACTIVE')
                """).param("publicId", CERTIFICATION_PUBLIC_ID).update();
        jdbc.sql("""
                INSERT INTO employee_skills(id,public_id,employee_id,skill_id,proficiency_level,years_experience,
                  last_used_on,evidence,version,created_at,updated_at)
                VALUES (1,'01J00000000000000000000005',1,1,4,3.5,DATE '2026-07-01','成果物',2,
                  CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).update();
        jdbc.sql("""
                INSERT INTO employee_knowledge(id,public_id,employee_id,knowledge_id,proficiency_level,evidence,
                  version,created_at,updated_at)
                VALUES (1,'01J00000000000000000000006',1,1,3,'担当経験',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).update();
        jdbc.sql("""
                INSERT INTO career_histories(id,public_id,employee_id,project_name,industry,role_name,start_date,
                  end_date,summary,achievements,technologies,version,created_at,updated_at)
                VALUES (1,'01J00000000000000000000007',1,'基幹刷新','製造','開発者',DATE '2025-04-01',
                  DATE '2026-03-31','刷新を担当','期限内完了','Java',3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).update();
        jdbc.sql("""
                INSERT INTO employee_certifications(id,public_id,employee_id,certification_id,acquired_on,
                  expires_on,credential_reference,verification_status,version,created_at,updated_at)
                VALUES (1,'01J00000000000000000000008',1,1,DATE '2025-06-01',DATE '2028-05-31',
                  'REF-001','VERIFIED',4,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).update();
    }

    private int count(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }
}
