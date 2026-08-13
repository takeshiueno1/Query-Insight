package com.query.insight.talent;

import com.query.insight.common.ApiException;
import com.query.insight.common.TraceIdFilter;
import com.query.insight.talent.TalentSubmission.Status;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/manager/talent-submissions")
@PreAuthorize("hasRole('OFFICER') and principal.claims['scopes'].contains('SUBORDINATES')")
public class ManagerTalentSubmissionController {
    private final TalentSubmissionService service;

    public ManagerTalentSubmissionController(TalentSubmissionService service) {
        this.service = service;
    }

    @GetMapping
    List<TalentSubmissionService.ManagerListItem> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "SUBMITTED") String status) {
        return service.managerList(employee(jwt), status(status));
    }

    @GetMapping("/{publicId:[0-7][0-9A-HJKMNP-TV-Z]{25}}")
    TalentSubmissionService.ManagerDetail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        return service.managerDetail(employee(jwt), publicId);
    }

    @PostMapping("/{publicId:[0-7][0-9A-HJKMNP-TV-Z]{25}}/approve")
    TalentSubmissionController.Response approve(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId,
            @RequestBody JsonNode request, HttpServletRequest servletRequest) {
        requireOnly(request, Set.of("version"));
        return TalentSubmissionController.Response.from(service.approve(employee(jwt), account(jwt), publicId,
                version(request), traceId(servletRequest)));
    }

    @PostMapping("/{publicId:[0-7][0-9A-HJKMNP-TV-Z]{25}}/return")
    TalentSubmissionController.Response returnToEmployee(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String publicId, @RequestBody JsonNode request,
            HttpServletRequest servletRequest) {
        requireOnly(request, Set.of("version", "reason"));
        String reason = request.path("reason").isTextual() ? request.path("reason").asText() : null;
        return TalentSubmissionController.Response.from(service.returnToEmployee(employee(jwt), account(jwt),
                publicId, version(request), reason, traceId(servletRequest)));
    }

    private static Status status(String value) {
        try {
            return Status.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TALENT_STATUS_INVALID", "申請状態が不正です");
        }
    }

    private static String employee(Jwt jwt) {
        return jwt.getClaimAsString("employeePublicId");
    }

    private static String account(Jwt jwt) {
        return jwt.getClaimAsString("accountPublicId");
    }

    private static String traceId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(TraceIdFilter.ATTRIBUTE));
    }

    private static long version(JsonNode request) {
        if (request == null || !request.path("version").isIntegralNumber()
                || !request.path("version").canConvertToLong()) {
            throw invalidDecisionRequest();
        }
        return request.path("version").longValue();
    }

    private static void requireOnly(JsonNode request, Set<String> allowed) {
        if (request == null || !request.isObject()
                || request.propertyStream().anyMatch(entry -> !allowed.contains(entry.getKey()))) {
            throw invalidDecisionRequest();
        }
    }

    private static ApiException invalidDecisionRequest() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "入力内容を確認してください");
    }
}
