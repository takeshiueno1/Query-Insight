package com.query.insight.dashboard;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final JdbcClient jdbc;

    public DashboardController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/me")
    DashboardResponse me(@AuthenticationPrincipal Jwt jwt) {
        String employeePublicId = jwt.getClaimAsString("employeePublicId");
        Profile profile = jdbc.sql("""
                SELECT e.employee_no,CONCAT(e.last_name,' ',e.first_name) name,d.name department,e.position_name,
                  e.updated_at FROM employees e LEFT JOIN departments d ON d.id=e.department_id
                  WHERE e.public_id=:publicId
                """).param("publicId", employeePublicId)
                .query((rs, row) -> new Profile(rs.getString("employee_no"), rs.getString("name"),
                        rs.getString("department"), rs.getString("position_name"),
                        rs.getTimestamp("updated_at").toInstant())).single();
        List<Score> scores = jdbc.sql("""
                SELECT c.axis_code,c.display_name,COALESCE(s.level,0) level
                FROM evaluation_criteria c
                JOIN evaluation_criteria_versions cv ON cv.id=c.criteria_version_id AND cv.status='PUBLISHED'
                LEFT JOIN evaluation_targets t ON t.employee_id=(SELECT id FROM employees WHERE public_id=:publicId)
                LEFT JOIN self_evaluation_details s ON s.target_id=t.id AND s.axis_code=c.axis_code
                ORDER BY c.sort_order
                """).param("publicId", employeePublicId)
                .query((rs, row) -> new Score(rs.getString("axis_code"), rs.getString("display_name"), rs.getInt("level")))
                .list();
        long unread = jdbc.sql("""
                SELECT COUNT(*) FROM notifications n JOIN accounts a ON a.id=n.recipient_account_id
                JOIN employees e ON e.id=a.employee_id WHERE e.public_id=:publicId AND n.read_at IS NULL
                """).param("publicId", employeePublicId).query(Long.class).single();
        return new DashboardResponse(profile, scores, unread);
    }

    record DashboardResponse(Profile profile, List<Score> scores, long unreadNotifications) {
    }
    record Profile(String employeeNo, String name, String department, String positionName, java.time.Instant updatedAt) {
    }
    record Score(String axisCode, String displayName, int level) {
    }
}
