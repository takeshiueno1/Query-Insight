package com.query.insight.analysis;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class AnalysisPrivacySanitizer {
    static final String REMOVED = "[除去]";
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9._%+-])");
    private static final Pattern PUBLIC_ID = Pattern.compile("(?i)(?<![A-Z0-9])[A-Z0-9]{26}(?![A-Z0-9])");
    private static final Pattern EMPLOYEE_NUMBER = Pattern.compile("(?i)(?<![A-Z0-9])QI\\d{4,}(?![A-Z0-9])");

    private final JdbcClient jdbc;

    public AnalysisPrivacySanitizer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    SanitizedRequest sanitize(String periodName,
            List<AiAnalysisClient.AxisInput> axes, AiAnalysisClient.TalentProfileInput talent) {
        List<Pattern> exactValues = knownIdentityValues().stream()
                .map(value -> Pattern.compile(value,
                        Pattern.LITERAL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
                .toList();
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

    private List<String> knownIdentityValues() {
        Set<String> values = new LinkedHashSet<>();
        jdbc.sql("""
                SELECT employee_no,last_name,first_name,email,public_id FROM employees
                """).query((rs, row) -> {
                    String lastName = rs.getString("last_name");
                    String firstName = rs.getString("first_name");
                    addIdentityName(values, lastName, firstName);
                    add(values, rs.getString("employee_no"), rs.getString("email"), rs.getString("public_id"),
                            lastName + firstName, firstName + lastName,
                            lastName + " " + firstName, firstName + " " + lastName,
                            lastName + "　" + firstName, firstName + "　" + lastName);
                    return 0;
                }).list();
        jdbc.sql("SELECT code,name,public_id FROM departments").query((rs, row) -> {
            add(values, rs.getString("code"), rs.getString("name"), rs.getString("public_id"));
            return 0;
        }).list();
        addColumn(values, "SELECT public_id FROM accounts");
        addColumn(values, "SELECT public_id FROM evaluation_targets");
        return values.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    private void addColumn(Set<String> values, String sql) {
        jdbc.sql(sql).query(String.class).list().forEach(value -> add(values, value));
    }

    private static void add(Set<String> values, String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && candidate.trim().length() >= 2) values.add(candidate.trim());
        }
    }

    private static void addIdentityName(Set<String> values, String... names) {
        for (String name : names) {
            if (name != null && !name.isBlank()) values.add(name.trim());
        }
    }

    private static String sanitize(String input, List<Pattern> exactValues) {
        if (input == null) return null;
        String sanitized = EMAIL.matcher(input).replaceAll(REMOVED);
        sanitized = PUBLIC_ID.matcher(sanitized).replaceAll(REMOVED);
        sanitized = EMPLOYEE_NUMBER.matcher(sanitized).replaceAll(REMOVED);
        for (Pattern value : exactValues) {
            sanitized = value.matcher(sanitized).replaceAll(Matcher.quoteReplacement(REMOVED));
        }
        return sanitized;
    }

    record SanitizedRequest(String periodName, List<AiAnalysisClient.AxisInput> axes,
            AiAnalysisClient.TalentProfileInput talentProfile) {
    }
}
