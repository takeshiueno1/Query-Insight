package com.query.insight.status;

import com.query.insight.common.Hashing;
import com.query.insight.common.PublicIdGenerator;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProfileStatusService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean h2Database;

    @Autowired
    public ProfileStatusService(JdbcClient jdbc, ObjectMapper objectMapper, DataSource dataSource) {
        this(jdbc, objectMapper, dataSource, Clock.systemUTC());
    }

    ProfileStatusService(JdbcClient jdbc, ObjectMapper objectMapper, DataSource dataSource, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.h2Database = isH2(dataSource);
    }

    @Transactional
    public ProfileStatusResponse currentForEmployee(String employeePublicId) {
        long employeeId = jdbc.sql("SELECT id FROM employees WHERE public_id=:publicId")
                .param("publicId", employeePublicId).query(Long.class).single();
        return findLatest(employeeId).orElseGet(() -> recalculate(employeeId)).toResponse();
    }

    @Transactional
    public Snapshot recalculate(long employeeId) {
        lockEmployee(employeeId);
        Source source = approvedSource(employeeId, LocalDate.now(clock));
        ProfileStatusCalculator.Result result = ProfileStatusCalculator.calculate(source.toInput());
        String fingerprint = Hashing.sha256(toStableJson(source));
        return findByFingerprint(employeeId, fingerprint)
                .orElseGet(() -> insertOrFindConcurrent(employeeId, result, fingerprint, clock.instant()));
    }

    private void lockEmployee(long employeeId) {
        jdbc.sql("SELECT id FROM employees WHERE id=:employeeId FOR UPDATE")
                .param("employeeId", employeeId).query(Long.class).single();
    }

    private Source approvedSource(long employeeId, LocalDate calculatedOn) {
        List<LevelSource> skills = jdbc.sql("""
                SELECT skill_id,proficiency_level FROM employee_skills
                WHERE employee_id=:employeeId ORDER BY skill_id,id
                """).param("employeeId", employeeId)
                .query((rs, row) -> new LevelSource(rs.getLong("skill_id"), rs.getInt("proficiency_level"))).list();
        List<LevelSource> knowledge = jdbc.sql("""
                SELECT knowledge_id,proficiency_level FROM employee_knowledge
                WHERE employee_id=:employeeId ORDER BY knowledge_id,id
                """).param("employeeId", employeeId)
                .query((rs, row) -> new LevelSource(rs.getLong("knowledge_id"), rs.getInt("proficiency_level"))).list();
        List<CareerSource> careers = jdbc.sql("""
                SELECT id,start_date,end_date FROM career_histories
                WHERE employee_id=:employeeId ORDER BY start_date,end_date,id
                """).param("employeeId", employeeId).query((rs, row) -> new CareerSource(
                        rs.getLong("id"), rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class))).list();
        List<CertificationSource> certifications = jdbc.sql("""
                SELECT certification_id,acquired_on,expires_on,verification_status FROM employee_certifications
                WHERE employee_id=:employeeId ORDER BY certification_id,id
                """).param("employeeId", employeeId).query((rs, row) -> new CertificationSource(
                        rs.getLong("certification_id"), rs.getObject("acquired_on", LocalDate.class),
                        rs.getObject("expires_on", LocalDate.class), rs.getString("verification_status"))).list();
        return new Source(calculatedOn, skills, knowledge, careers, certifications);
    }

    private Optional<Snapshot> findLatest(long employeeId) {
        return jdbc.sql("""
                SELECT id,public_id,employee_id,skill_score,knowledge_score,career_score,certification_score,
                  total_score,grade,source_fingerprint,calculated_at
                FROM profile_status_snapshots WHERE employee_id=:employeeId
                ORDER BY calculated_at DESC,id DESC LIMIT 1
                """).param("employeeId", employeeId).query(this::snapshot).optional();
    }

    private Optional<Snapshot> findByFingerprint(long employeeId, String fingerprint) {
        return jdbc.sql("""
                SELECT id,public_id,employee_id,skill_score,knowledge_score,career_score,certification_score,
                  total_score,grade,source_fingerprint,calculated_at
                FROM profile_status_snapshots
                WHERE employee_id=:employeeId AND formula_version=:formulaVersion
                  AND source_fingerprint=:fingerprint
                """).param("employeeId", employeeId).param("formulaVersion", ProfileStatusCalculator.FORMULA_VERSION)
                .param("fingerprint", fingerprint).query(this::snapshot).optional();
    }

    Snapshot insertOrFindConcurrent(long employeeId, ProfileStatusCalculator.Result result,
            String fingerprint, Instant calculatedAt) {
        String publicId = PublicIdGenerator.next();
        int inserted = jdbc.sql(insertSql()).param("publicId", publicId).param("employeeId", employeeId)
                .param("skill", result.skillScore()).param("knowledge", result.knowledgeScore())
                .param("career", result.careerScore()).param("certification", result.certificationScore())
                .param("total", result.totalScore()).param("grade", result.grade())
                .param("formulaVersion", ProfileStatusCalculator.FORMULA_VERSION)
                .param("fingerprint", fingerprint).param("calculatedAt", Timestamp.from(calculatedAt)).update();
        if (inserted == 0) {
            return findByFingerprint(employeeId, fingerprint).orElseThrow();
        }
        return findByFingerprint(employeeId, fingerprint).orElseThrow();
    }

    private String insertSql() {
        if (h2Database) {
            return """
                    MERGE INTO profile_status_snapshots target
                    USING (VALUES (:publicId,:employeeId,:skill,:knowledge,:career,:certification,:total,:grade,
                      :formulaVersion,:fingerprint,:calculatedAt))
                      source(public_id,employee_id,skill_score,knowledge_score,career_score,certification_score,
                        total_score,grade,formula_version,source_fingerprint,calculated_at)
                    ON target.employee_id=source.employee_id AND target.formula_version=source.formula_version
                      AND target.source_fingerprint=source.source_fingerprint
                    WHEN NOT MATCHED THEN INSERT(public_id,employee_id,skill_score,knowledge_score,career_score,
                      certification_score,total_score,grade,formula_version,source_fingerprint,calculated_at)
                    VALUES(source.public_id,source.employee_id,source.skill_score,source.knowledge_score,
                      source.career_score,source.certification_score,source.total_score,source.grade,
                      source.formula_version,source.source_fingerprint,source.calculated_at)
                    """;
        }
        return """
                INSERT INTO profile_status_snapshots(public_id,employee_id,skill_score,knowledge_score,career_score,
                  certification_score,total_score,grade,formula_version,source_fingerprint,calculated_at)
                VALUES (:publicId,:employeeId,:skill,:knowledge,:career,:certification,:total,:grade,
                  :formulaVersion,:fingerprint,:calculatedAt)
                ON CONFLICT (employee_id,formula_version,source_fingerprint) DO NOTHING
                """;
    }

    private static boolean isH2(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return "H2".equals(connection.getMetaData().getDatabaseProductName());
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Database product could not be identified", exception);
        }
    }

    private Snapshot snapshot(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        ProfileStatusCalculator.Result result = new ProfileStatusCalculator.Result(
                rs.getBigDecimal("skill_score"), rs.getBigDecimal("knowledge_score"),
                rs.getBigDecimal("career_score"), rs.getBigDecimal("certification_score"),
                rs.getBigDecimal("total_score"), rs.getString("grade"));
        return new Snapshot(rs.getLong("id"), rs.getString("public_id").trim(), rs.getLong("employee_id"), result,
                rs.getString("source_fingerprint").trim(), rs.getTimestamp("calculated_at").toInstant());
    }

    private String toStableJson(Source source) {
        try {
            return objectMapper.writeValueAsString(source);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Profile status source could not be serialized", exception);
        }
    }

    private static int careerMonths(List<CareerSource> careers, LocalDate calculatedOn) {
        List<MonthRange> ranges = careers.stream()
                .filter(career -> !career.startDate().isAfter(calculatedOn))
                .map(career -> new MonthRange(YearMonth.from(career.startDate()),
                        YearMonth.from(career.endDate() == null || career.endDate().isAfter(calculatedOn)
                                ? calculatedOn : career.endDate())))
                .sorted(Comparator.comparing(MonthRange::start).thenComparing(MonthRange::end))
                .toList();
        if (ranges.isEmpty()) return 0;

        long total = 0;
        YearMonth start = ranges.getFirst().start();
        YearMonth end = ranges.getFirst().end();
        for (MonthRange range : ranges.subList(1, ranges.size())) {
            if (!range.start().isAfter(end.plusMonths(1))) {
                if (range.end().isAfter(end)) end = range.end();
            } else {
                total += ChronoUnit.MONTHS.between(start, end) + 1;
                start = range.start();
                end = range.end();
            }
        }
        total += ChronoUnit.MONTHS.between(start, end) + 1;
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    private static int validCertificationCount(List<CertificationSource> certifications, LocalDate calculatedOn) {
        return (int) certifications.stream()
                .filter(certification -> "VERIFIED".equals(certification.verificationStatus()))
                .filter(certification -> !certification.acquiredOn().isAfter(calculatedOn))
                .filter(certification -> certification.expiresOn() == null
                        || !certification.expiresOn().isBefore(calculatedOn))
                .count();
    }

    private static List<String> missingCategories(ProfileStatusCalculator.Result result) {
        List<String> missing = new ArrayList<>(4);
        if (result.skillScore().compareTo(ZERO) == 0) missing.add("SKILL");
        if (result.knowledgeScore().compareTo(ZERO) == 0) missing.add("KNOWLEDGE");
        if (result.careerScore().compareTo(ZERO) == 0) missing.add("CAREER");
        if (result.certificationScore().compareTo(ZERO) == 0) missing.add("CERTIFICATION");
        return List.copyOf(missing);
    }

    private record Source(LocalDate calculatedOn, List<LevelSource> skills, List<LevelSource> knowledge,
            List<CareerSource> careers, List<CertificationSource> certifications) {
        ProfileStatusCalculator.Input toInput() {
            return new ProfileStatusCalculator.Input(
                    skills.stream().map(LevelSource::level).toList(),
                    knowledge.stream().map(LevelSource::level).toList(),
                    careerMonths(careers, calculatedOn), validCertificationCount(certifications, calculatedOn));
        }
    }

    private record LevelSource(long masterId, int level) {
    }

    private record CareerSource(long sourceId, LocalDate startDate, LocalDate endDate) {
    }

    private record CertificationSource(long masterId, LocalDate acquiredOn, LocalDate expiresOn,
            String verificationStatus) {
    }

    private record MonthRange(YearMonth start, YearMonth end) {
    }

    public record ProfileStatusResponse(String publicId, BigDecimal skillScore, BigDecimal knowledgeScore,
            BigDecimal careerScore, BigDecimal certificationScore, BigDecimal totalScore, String grade,
            List<String> missingCategories, String formulaVersion, Instant calculatedAt, boolean editable) {
    }

    public record Snapshot(long id, String publicId, long employeeId, ProfileStatusCalculator.Result result,
            String sourceFingerprint, Instant calculatedAt) {
        ProfileStatusResponse toResponse() {
            return new ProfileStatusResponse(publicId, result.skillScore(), result.knowledgeScore(),
                    result.careerScore(), result.certificationScore(), result.totalScore(), result.grade(),
                    missingCategories(result), ProfileStatusCalculator.FORMULA_VERSION, calculatedAt, false);
        }
    }
}
