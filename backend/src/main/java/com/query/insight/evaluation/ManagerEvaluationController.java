package com.query.insight.evaluation;

import com.query.insight.common.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/manager-evaluations")
@PreAuthorize("hasRole('OFFICER') and principal.claims['scopes'].contains('SUBORDINATES')")
public class ManagerEvaluationController {
    private final EvaluationWorkflowService service;

    public ManagerEvaluationController(EvaluationWorkflowService service) {
        this.service = service;
    }

    @GetMapping
    List<EvaluationWorkflowService.ManagerListItem> list(@AuthenticationPrincipal Jwt jwt) {
        return service.managerList(jwt.getClaimAsString("accountPublicId"), jwt.getClaimAsString("employeePublicId"));
    }

    @GetMapping("/{targetPublicId}")
    EvaluationWorkflowService.ManagerEvaluationResponse detail(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String targetPublicId) {
        return service.managerDetail(jwt.getClaimAsString("accountPublicId"),
                jwt.getClaimAsString("employeePublicId"), targetPublicId);
    }

    @PutMapping("/{targetPublicId}")
    EvaluationWorkflowService.ManagerEvaluationResponse save(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String targetPublicId, @Valid @RequestBody SaveRequest request,
            HttpServletRequest servletRequest) {
        return service.saveManager(jwt.getClaimAsString("employeePublicId"), jwt.getClaimAsString("accountPublicId"),
                targetPublicId, request.toService(), traceId(servletRequest));
    }

    @PostMapping("/{targetPublicId}/return")
    EvaluationWorkflowService.ManagerEvaluationResponse returnToEmployee(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String targetPublicId, @Valid @RequestBody ReasonRequest request,
            HttpServletRequest servletRequest) {
        return service.returnToEmployee(jwt.getClaimAsString("employeePublicId"),
                jwt.getClaimAsString("accountPublicId"), targetPublicId, request.version(), request.reason(),
                traceId(servletRequest));
    }

    @PostMapping("/{targetPublicId}/submit")
    EvaluationWorkflowService.ManagerEvaluationResponse submit(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String targetPublicId, @Valid @RequestBody VersionRequest request,
            HttpServletRequest servletRequest) {
        return service.submitManager(jwt.getClaimAsString("employeePublicId"),
                jwt.getClaimAsString("accountPublicId"), targetPublicId, request.version(), traceId(servletRequest));
    }

    private static String traceId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(TraceIdFilter.ATTRIBUTE));
    }

    public record SaveRequest(@NotNull Long version, @NotEmpty List<@Valid Detail> details,
            @Size(max = 3000) String summary) {
        EvaluationWorkflowService.ManagerSaveRequest toService() {
            return new EvaluationWorkflowService.ManagerSaveRequest(version,
                    details.stream().map(Detail::toService).toList(), summary);
        }
    }

    public record Detail(@NotBlank String axisCode, @NotBlank String rank,
            @Size(max = 1500) String comment) {
        EvaluationWorkflowService.ManagerDetailInput toService() {
            return new EvaluationWorkflowService.ManagerDetailInput(axisCode, rank, comment);
        }
    }

    public record VersionRequest(@NotNull Long version) {
    }

    public record ReasonRequest(@NotNull Long version, @NotBlank @Size(max = 1000) String reason) {
    }
}
