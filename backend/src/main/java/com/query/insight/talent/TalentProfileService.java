package com.query.insight.talent;

import com.query.insight.employee.EmployeeService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class TalentProfileService {
    private final JdbcClient jdbc;
    private final EmployeeService employees;

    public TalentProfileService(JdbcClient jdbc, EmployeeService employees) {
        this.jdbc = jdbc;
        this.employees = employees;
    }

    public TalentProfile findAccessible(String targetPublicId, String actorPublicId, Set<String> roles) {
        employees.findAccessible(targetPublicId, actorPublicId, roles);
        long employeeId = jdbc.sql("SELECT id FROM employees WHERE public_id=:publicId")
                .param("publicId", targetPublicId).query(Long.class).single();
        List<Skill> skills = jdbc.sql("""
                SELECT sm.code,sm.name,sm.category,es.proficiency_level,es.years_experience,
                  es.last_used_on,es.evidence
                FROM employee_skills es JOIN skill_masters sm ON sm.id=es.skill_id
                WHERE es.employee_id=:employeeId ORDER BY es.proficiency_level DESC,sm.name
                """).param("employeeId", employeeId)
                .query((rs, row) -> new Skill(rs.getString("code"), rs.getString("name"),
                        rs.getString("category"), rs.getInt("proficiency_level"),
                        rs.getBigDecimal("years_experience"), rs.getObject("last_used_on", LocalDate.class),
                        rs.getString("evidence"))).list();
        List<Knowledge> knowledge = jdbc.sql("""
                SELECT km.code,km.name,km.category,ek.proficiency_level,ek.evidence
                FROM employee_knowledge ek JOIN knowledge_masters km ON km.id=ek.knowledge_id
                WHERE ek.employee_id=:employeeId ORDER BY ek.proficiency_level DESC,km.name
                """).param("employeeId", employeeId)
                .query((rs, row) -> new Knowledge(rs.getString("code"), rs.getString("name"),
                        rs.getString("category"), rs.getInt("proficiency_level"), rs.getString("evidence"))).list();
        List<Career> careers = jdbc.sql("""
                SELECT project_name,industry,role_name,start_date,end_date,summary,achievements,technologies
                FROM career_histories WHERE employee_id=:employeeId ORDER BY start_date DESC
                """).param("employeeId", employeeId)
                .query((rs, row) -> new Career(rs.getString("project_name"), rs.getString("industry"),
                        rs.getString("role_name"), rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class), rs.getString("summary"),
                        rs.getString("achievements"), rs.getString("technologies"))).list();
        List<Certification> certifications = jdbc.sql("""
                SELECT cm.code,cm.name,cm.issuer,ec.acquired_on,ec.expires_on,ec.verification_status
                FROM employee_certifications ec JOIN certification_masters cm ON cm.id=ec.certification_id
                WHERE ec.employee_id=:employeeId ORDER BY ec.acquired_on DESC
                """).param("employeeId", employeeId)
                .query((rs, row) -> new Certification(rs.getString("code"), rs.getString("name"),
                        rs.getString("issuer"), rs.getObject("acquired_on", LocalDate.class),
                        rs.getObject("expires_on", LocalDate.class), rs.getString("verification_status"))).list();
        return new TalentProfile(skills, knowledge, careers, certifications);
    }

    public record TalentProfile(List<Skill> skills, List<Knowledge> knowledge, List<Career> careers,
            List<Certification> certifications) {
    }

    public record Skill(String code, String name, String category, int level, BigDecimal yearsExperience,
            LocalDate lastUsedOn, String evidence) {
    }

    public record Knowledge(String code, String name, String category, int level, String evidence) {
    }

    public record Career(String projectName, String industry, String roleName, LocalDate startDate,
            LocalDate endDate, String summary, String achievements, String technologies) {
    }

    public record Certification(String code, String name, String issuer, LocalDate acquiredOn,
            LocalDate expiresOn, String verificationStatus) {
    }
}
