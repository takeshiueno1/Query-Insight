package com.query.insight.analysis;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class AnalysisPrivacySanitizer {
    static final String REMOVED = "[除去]";
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9._%+-])");
    private static final Pattern PUBLIC_ID = Pattern.compile("(?i)(?<![A-Z0-9])[A-Z0-9]{26}(?![A-Z0-9])");

    private final JdbcClient jdbc;

    public AnalysisPrivacySanitizer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    SanitizedRequest sanitize(long employeeId, long targetId, String periodName,
            List<AiAnalysisClient.AxisInput> axes, AiAnalysisClient.TalentProfileInput talent) {
        List<String> exactValues = identityValues(employeeId, targetId);
        List<AiAnalysisClient.AxisInput> sanitizedAxes = axes.stream()
                .map(axis -> new AiAnalysisClient.AxisInput(sanitize(axis.axisCode(), exactValues),
                        sanitize(axis.displayName(), exactValues), axis.level(),
                        sanitize(axis.evidence(), exactValues)))
                .toList();
        AiAnalysisClient.TalentProfileInput sanitizedTalent = new AiAnalysisClient.TalentProfileInput(
                sanitize(talent.currentRole(), exactValues), talent.tenureYears(),
                talent.skills().stream().map(skill -> new AiAnalysisClient.SkillInput(
                        sanitize(skill.name(), exactValues), skill.level(), skill.yearsExperience(),
                        sanitize(skill.evidence(), exactValues))).toList(),
                talent.knowledge().stream().map(knowledge -> new AiAnalysisClient.KnowledgeInput(
                        sanitize(knowledge.name(), exactValues), knowledge.level(),
                        sanitize(knowledge.evidence(), exactValues))).toList(),
                talent.experiences().stream().map(experience -> new AiAnalysisClient.ExperienceInput(
                        sanitize(experience.role(), exactValues), sanitize(experience.industry(), exactValues),
                        sanitize(experience.summary(), exactValues),
                        sanitize(experience.achievements(), exactValues),
                        sanitize(experience.technologies(), exactValues))).toList(),
                talent.certifications().stream().map(certification -> new AiAnalysisClient.CertificationInput(
                        sanitize(certification.name(), exactValues),
                        sanitize(certification.issuer(), exactValues))).toList());
        return new SanitizedRequest(sanitize(periodName, exactValues), sanitizedAxes, sanitizedTalent);
    }

    private List<String> identityValues(long employeeId, long targetId) {
        Identity identity = jdbc.sql("""
                SELECT e.last_name,e.first_name,e.email,e.employee_no,e.public_id employee_public_id,
                  d.name department_name,d.code department_code,d.public_id department_public_id,
                  a.public_id account_public_id,a.login_id_normalized,t.public_id target_public_id
                FROM employees e
                JOIN accounts a ON a.employee_id=e.id
                JOIN evaluation_targets t ON t.id=:targetId AND t.employee_id=e.id
                LEFT JOIN departments d ON d.id=e.department_id
                WHERE e.id=:employeeId
                """).param("employeeId", employeeId).param("targetId", targetId)
                .query((rs, row) -> new Identity(rs.getString("last_name"), rs.getString("first_name"),
                        rs.getString("email"), rs.getString("employee_no"), rs.getString("employee_public_id"),
                        rs.getString("department_name"), rs.getString("department_code"),
                        rs.getString("department_public_id"), rs.getString("account_public_id"),
                        rs.getString("login_id_normalized"), rs.getString("target_public_id")))
                .single();
        Set<String> values = new LinkedHashSet<>();
        add(values, identity.lastName(), identity.firstName(), identity.email(), identity.employeeNo(),
                identity.employeePublicId(), identity.departmentName(), identity.departmentCode(),
                identity.departmentPublicId(), identity.accountPublicId(), identity.loginId(),
                identity.targetPublicId());
        if (identity.lastName() != null && identity.firstName() != null) {
            add(values, identity.lastName() + identity.firstName(), identity.firstName() + identity.lastName(),
                    identity.lastName() + " " + identity.firstName(),
                    identity.firstName() + " " + identity.lastName(),
                    identity.lastName() + "　" + identity.firstName(),
                    identity.firstName() + "　" + identity.lastName());
        }
        return values.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    private static void add(Set<String> values, String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) values.add(candidate.trim());
        }
    }

    private static String sanitize(String input, List<String> exactValues) {
        if (input == null) return null;
        String sanitized = EMAIL.matcher(input).replaceAll(REMOVED);
        sanitized = PUBLIC_ID.matcher(sanitized).replaceAll(REMOVED);
        for (String value : exactValues) sanitized = sanitized.replace(value, REMOVED);
        return sanitized;
    }

    record SanitizedRequest(String periodName, List<AiAnalysisClient.AxisInput> axes,
            AiAnalysisClient.TalentProfileInput talentProfile) {
    }

    private record Identity(String lastName, String firstName, String email, String employeeNo,
            String employeePublicId, String departmentName, String departmentCode, String departmentPublicId,
            String accountPublicId, String loginId, String targetPublicId) {
    }
}
