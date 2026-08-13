package com.query.insight.evaluation;

import com.query.insight.common.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/executive/evaluations")
@PreAuthorize("hasRole('OFFICER') and principal.claims['scopes'].contains('ALL')")
public class ExecutiveEvaluationController {
    private final EvaluationWorkflowService service;

    public ExecutiveEvaluationController(EvaluationWorkflowService service) {
        this.service = service;
    }

    @GetMapping
    EvaluationWorkflowService.ExecutiveDashboard dashboard(@AuthenticationPrincipal Jwt jwt) {
        return service.executiveDashboard(jwt.getClaimAsString("accountPublicId"));
    }

    @GetMapping("/{targetPublicId}")
    EvaluationWorkflowService.ExecutiveEvaluationResponse detail(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String targetPublicId) {
        return service.executiveDetail(jwt.getClaimAsString("accountPublicId"), targetPublicId);
    }

    @PostMapping("/{targetPublicId}/approve")
    EvaluationWorkflowService.ExecutiveEvaluationResponse approve(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String targetPublicId, @Valid @RequestBody ApprovalRequest request,
            HttpServletRequest servletRequest) {
        return service.approve(jwt.getClaimAsString("accountPublicId"), targetPublicId, request.version(),
                request.comment(), traceId(servletRequest));
    }

    @PostMapping("/{targetPublicId}/return")
    EvaluationWorkflowService.ExecutiveEvaluationResponse returnToManager(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String targetPublicId, @Valid @RequestBody ReasonRequest request,
            HttpServletRequest servletRequest) {
        return service.returnToManager(jwt.getClaimAsString("accountPublicId"), targetPublicId, request.version(),
                request.reason(), traceId(servletRequest));
    }

    @PostMapping("/{targetPublicId}/reopen")
    EvaluationWorkflowService.ExecutiveEvaluationResponse reopen(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String targetPublicId, @Valid @RequestBody ReasonRequest request,
            HttpServletRequest servletRequest) {
        return service.reopen(jwt.getClaimAsString("accountPublicId"), targetPublicId, request.version(),
                request.reason(), traceId(servletRequest));
    }

    private static String traceId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(TraceIdFilter.ATTRIBUTE));
    }

    public record ApprovalRequest(@NotNull Long version, @Size(max = 2000) String comment) {
    }

    public record ReasonRequest(@NotNull Long version, @NotBlank @Size(max = 1000) String reason) {
    }
}
