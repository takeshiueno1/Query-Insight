package com.query.insight.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.query.insight.common.PublicIdGenerator;
import com.query.insight.talent.TalentPayloads;
import com.query.insight.talent.TalentSubmission;
import com.query.insight.talent.TalentSubmissionService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
class ProfileStatusServiceIntegrationTests {
    private static final Instant CALCULATED_AT = Instant.parse("2026-08-13T03:00:00Z");
    private static final LocalDate CALCULATED_ON = LocalDate.of(2026, 8, 13);

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private TalentSubmissionService submissions;

    private ProfileStatusService service;
    private long employeeId;
    private String employeePublicId;

    @BeforeEach
    void setUp() {
        service = new ProfileStatusService(jdbc, objectMapper, dataSource,
                Clock.fixed(CALCULATED_AT, ZoneOffset.UTC));
        employeeId = jdbc.sql("SELECT id FROM employees WHERE employee_no='QITEST'")
                .query(Long.class).single();
        employeePublicId = jdbc.sql("SELECT public_id FROM employees WHERE id=:id")
                .param("id", employeeId).query(String.class).single().trim();
        jdbc.sql("DELETE FROM profile_status_snapshots WHERE employee_id=:employeeId")
                .param("employeeId", employeeId).update();
        jdbc.sql("DELETE FROM employee_skills WHERE employee_id=:employeeId")
                .param("employeeId", employeeId).update();
        jdbc.sql("DELETE FROM employee_knowledge WHERE employee_id=:employeeId")
                .param("employeeId", employeeId).update();
        jdbc.sql("DELETE FROM career_histories WHERE employee_id=:employeeId")
                .param("employeeId", employeeId).update();
        jdbc.sql("DELETE FROM employee_certifications WHERE employee_id=:employeeId")
                .param("employeeId", employeeId).update();
    }

    @Test
    void averagesOnlyOfficiallyAppliedSkillAndKnowledgeLevels() {
        insertSkill(masterId("skill_masters", 0), 5);
        insertSkill(masterId("skill_masters", 1), 3);
        insertKnowledge(masterId("knowledge_masters", 0), 4);

        String pendingMaster = jdbc.sql("SELECT public_id FROM skill_masters ORDER BY id OFFSET 2 ROWS FETCH NEXT 1 ROW ONLY")
                .query(String.class).single().trim();
        Actor employee = actor("QITEST");
        var draft = submissions.create(employee.employeePublicId(), TalentSubmission.Type.SKILL,
                new TalentPayloads.SkillPayload(pendingMaster, 1, new BigDecimal("1.0"),
                        LocalDate.of(2026, 8, 1), "提出中の根拠"),
                employee.accountPublicId(), "01P00000000000000000000001");
        submissions.submit(employee.employeePublicId(), employee.accountPublicId(), draft.publicId(),
                draft.version(), "01P00000000000000000000002");

        var snapshot = service.recalculate(employeeId);

        assertThat(snapshot.result().skillScore()).isEqualByComparingTo("80.00");
        assertThat(snapshot.result().knowledgeScore()).isEqualByComparingTo("80.00");
    }

    @Test
    void mergesCareerMonthsInclusivelyAndClampsOpenAndFutureRanges() {
        insertCareer("overlap-a", LocalDate.of(2026, 1, 15), LocalDate.of(2026, 3, 2));
        insertCareer("overlap-b", LocalDate.of(2026, 3, 20), LocalDate.of(2026, 5, 1));
        insertCareer("open", LocalDate.of(2026, 7, 31), null);
        insertCareer("future", LocalDate.of(2026, 9, 1), null);

        var snapshot = service.recalculate(employeeId);

        // Jan-May is five inclusive months; Jul-Aug is two. March is counted once.
        assertThat(snapshot.result().careerScore()).isEqualByComparingTo("5.83");
    }

    @Test
    void countsOnlyVerifiedCertificationsThatAreValidOnTheCalculationDate() {
        insertCertification(masterId("certification_masters", 0), "VERIFIED", null);
        insertCertification(masterId("certification_masters", 1), "VERIFIED", CALCULATED_ON);
        insertCertification(masterId("certification_masters", 2), "VERIFIED", CALCULATED_ON.minusDays(1));
        insertCertification(masterId("certification_masters", 3), "PENDING", CALCULATED_ON.plusDays(1));

        var snapshot = service.recalculate(employeeId);

        assertThat(snapshot.result().certificationScore()).isEqualByComparingTo("40.00");
    }

    @Test
    void returnsZeroGradeFAndAllMissingCategoriesWhenNoEligibleDataExists() {
        var response = service.currentForEmployee(employeePublicId);

        assertThat(response.skillScore()).isEqualByComparingTo("0.00");
        assertThat(response.knowledgeScore()).isEqualByComparingTo("0.00");
        assertThat(response.careerScore()).isEqualByComparingTo("0.00");
        assertThat(response.certificationScore()).isEqualByComparingTo("0.00");
        assertThat(response.totalScore()).isEqualByComparingTo("0.00");
        assertThat(response.grade()).isEqualTo("F");
        assertThat(response.missingCategories())
                .containsExactly("SKILL", "KNOWLEDGE", "CAREER", "CERTIFICATION");
        assertThat(response.editable()).isFalse();
    }

    @Test
    void reusesTheSameSnapshotUntilTheApprovedSourceChanges() {
        var first = service.recalculate(employeeId);
        var duplicate = service.recalculate(employeeId);

        assertThat(duplicate.publicId()).isEqualTo(first.publicId());
        assertThat(snapshotCount()).isOne();

        insertSkill(masterId("skill_masters", 0), 5);
        var changed = service.recalculate(employeeId);

        assertThat(changed.publicId()).isNotEqualTo(first.publicId());
        assertThat(snapshotCount()).isEqualTo(2);
    }

    @Test
    void insertConflictPathReturnsTheExistingFingerprintSnapshot() {
        var existing = service.recalculate(employeeId);

        var recovered = service.insertOrFindConcurrent(employeeId, existing.result(),
                existing.sourceFingerprint(), CALCULATED_AT.plusSeconds(1));

        assertThat(recovered.publicId()).isEqualTo(existing.publicId());
        assertThat(recovered.calculatedAt()).isEqualTo(existing.calculatedAt());
        assertThat(snapshotCount()).isOne();
    }

    @Test
    void currentCalculatesWhenMissingAndThenReturnsTheLatestSnapshot() {
        var calculatedOnRead = service.currentForEmployee(employeePublicId);
        assertThat(snapshotCount()).isOne();

        insertKnowledge(masterId("knowledge_masters", 0), 5);
        var newest = service.recalculate(employeeId);
        var current = service.currentForEmployee(employeePublicId);

        assertThat(current.publicId()).isNotEqualTo(calculatedOnRead.publicId());
        assertThat(current.publicId()).isEqualTo(newest.publicId());
        assertThat(current.knowledgeScore()).isEqualByComparingTo("100.00");
    }

    private void insertSkill(long masterId, int level) {
        jdbc.sql("""
                INSERT INTO employee_skills(public_id,employee_id,skill_id,proficiency_level,years_experience,
                  last_used_on,evidence,version,created_at,updated_at)
                VALUES (:publicId,:employeeId,:masterId,:level,1.0,:lastUsed,'approved',0,:now,:now)
                """).param("publicId", PublicIdGenerator.next()).param("employeeId", employeeId)
                .param("masterId", masterId).param("level", level).param("lastUsed", CALCULATED_ON)
                .param("now", Timestamp.from(CALCULATED_AT)).update();
    }

    private void insertKnowledge(long masterId, int level) {
        jdbc.sql("""
                INSERT INTO employee_knowledge(public_id,employee_id,knowledge_id,proficiency_level,evidence,
                  version,created_at,updated_at)
                VALUES (:publicId,:employeeId,:masterId,:level,'approved',0,:now,:now)
                """).param("publicId", PublicIdGenerator.next()).param("employeeId", employeeId)
                .param("masterId", masterId).param("level", level)
                .param("now", Timestamp.from(CALCULATED_AT)).update();
    }

    private void insertCareer(String project, LocalDate startDate, LocalDate endDate) {
        jdbc.sql("""
                INSERT INTO career_histories(public_id,employee_id,project_name,industry,role_name,start_date,
                  end_date,summary,achievements,technologies,version,created_at,updated_at)
                VALUES (:publicId,:employeeId,:project,'IT','member',:startDate,:endDate,
                  'approved','approved','Java',0,:now,:now)
                """).param("publicId", PublicIdGenerator.next()).param("employeeId", employeeId)
                .param("project", project).param("startDate", startDate)
                .param("endDate", endDate, Types.DATE).param("now", Timestamp.from(CALCULATED_AT)).update();
    }

    private void insertCertification(long masterId, String verificationStatus, LocalDate expiresOn) {
        jdbc.sql("""
                INSERT INTO employee_certifications(public_id,employee_id,certification_id,acquired_on,
                  expires_on,credential_reference,verification_status,version,created_at,updated_at)
                VALUES (:publicId,:employeeId,:masterId,:acquired,:expires,NULL,:status,0,:now,:now)
                """).param("publicId", PublicIdGenerator.next()).param("employeeId", employeeId)
                .param("masterId", masterId).param("acquired", CALCULATED_ON.minusYears(1))
                .param("expires", expiresOn, Types.DATE).param("status", verificationStatus)
                .param("now", Timestamp.from(CALCULATED_AT)).update();
    }

    private long masterId(String table, int offset) {
        assertThat(table).isIn("skill_masters", "knowledge_masters", "certification_masters");
        return jdbc.sql("SELECT id FROM " + table + " ORDER BY id OFFSET :offset ROWS FETCH NEXT 1 ROW ONLY")
                .param("offset", offset).query(Long.class).single();
    }

    private int snapshotCount() {
        return jdbc.sql("SELECT COUNT(*) FROM profile_status_snapshots WHERE employee_id=:employeeId")
                .param("employeeId", employeeId).query(Integer.class).single();
    }

    private Actor actor(String employeeNo) {
        return jdbc.sql("""
                SELECT e.public_id employee_public_id,a.public_id account_public_id
                FROM employees e JOIN accounts a ON a.employee_id=e.id WHERE e.employee_no=:employeeNo
                """).param("employeeNo", employeeNo)
                .query((rs, row) -> new Actor(rs.getString("employee_public_id").trim(),
                        rs.getString("account_public_id").trim())).single();
    }

    private record Actor(String employeePublicId, String accountPublicId) {
    }
}
