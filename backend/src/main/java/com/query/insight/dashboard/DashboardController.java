package com.query.insight.dashboard;

import com.query.insight.evaluation.EvaluationWorkflowService;
import com.query.insight.notification.NotificationService;
import com.query.insight.status.ProfileStatusService;
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
    private final ProfileStatusService profileStatuses;
    private final EvaluationWorkflowService evaluations;
    private final NotificationService notifications;

    public DashboardController(JdbcClient jdbc, ProfileStatusService profileStatuses,
            EvaluationWorkflowService evaluations, NotificationService notifications) {
        this.jdbc = jdbc;
        this.profileStatuses = profileStatuses;
        this.evaluations = evaluations;
        this.notifications = notifications;
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
        ProfileStatusService.ProfileStatusResponse profileStatus = profileStatuses.currentForEmployee(employeePublicId);
        EvaluationWorkflowService.FinalResult finalManagerEvaluation = evaluations
                .publishedFinalResult(employeePublicId).orElse(null);
        long unread = notifications.unreadCount(jwt.getClaimAsString("accountPublicId"));
        return new DashboardResponse(profile, profileStatus, finalManagerEvaluation, unread);
    }

    record DashboardResponse(Profile profile, ProfileStatusService.ProfileStatusResponse profileStatus,
            EvaluationWorkflowService.FinalResult finalManagerEvaluation, long unreadNotifications) {
    }
    record Profile(String employeeNo, String name, String department, String positionName, java.time.Instant updatedAt) {
    }
}
