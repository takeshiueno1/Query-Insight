package com.query.insight.master;

import com.query.insight.common.ApiException;
import com.query.insight.common.TraceIdFilter;
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
public class MasterRequestController {
    private final MasterRequestService service;

    public MasterRequestController(MasterRequestService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/master-requests")
    MasterRequestService.Row create(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode request,
            HttpServletRequest servletRequest) {
        requireOnly(request, Set.of("type", "description"));
        String type = request.path("type").isTextual() ? request.path("type").asText() : null;
        String description = request.path("description").isTextual() ? request.path("description").asText() : null;
        return service.create(account(jwt), type, description, traceId(servletRequest));
    }

    @GetMapping("/api/v1/master-requests/me")
    List<MasterRequestService.Row> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(account(jwt));
    }

    @GetMapping("/api/v1/admin/master-requests")
    @PreAuthorize("hasRole('ADMIN')")
    List<MasterRequestService.Row> adminList(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "SUBMITTED") String status) {
        return service.adminList(account(jwt), roles(jwt), status(status));
    }

    @PostMapping("/api/v1/admin/master-requests/{publicId:[0-7][0-9A-HJKMNP-TV-Z]{25}}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    MasterRequestService.Row approve(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId,
            @RequestBody JsonNode request, HttpServletRequest servletRequest) {
        requireOnly(request, Set.of("version"));
        return service.approve(account(jwt), roles(jwt), publicId, version(request), traceId(servletRequest));
    }

    @PostMapping("/api/v1/admin/master-requests/{publicId:[0-7][0-9A-HJKMNP-TV-Z]{25}}/return")
    @PreAuthorize("hasRole('ADMIN')")
    MasterRequestService.Row returnRequest(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId,
            @RequestBody JsonNode request, HttpServletRequest servletRequest) {
        requireOnly(request, Set.of("version", "reason"));
        String reason = request.path("reason").isTextual() ? request.path("reason").asText() : null;
        return service.returnRequest(account(jwt), roles(jwt), publicId, version(request), reason,
                traceId(servletRequest));
    }

    private static MasterRequestService.Status status(String value) {
        try {
            return MasterRequestService.Status.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static long version(JsonNode request) {
        if (request == null || !request.path("version").isIntegralNumber()
                || !request.path("version").canConvertToLong()) throw invalid();
        return request.path("version").longValue();
    }

    private static void requireOnly(JsonNode request, Set<String> allowed) {
        if (request == null || !request.isObject()
                || request.propertyStream().anyMatch(entry -> !allowed.contains(entry.getKey()))) throw invalid();
    }

    private static String account(Jwt jwt) {
        return jwt.getClaimAsString("accountPublicId");
    }

    private static Set<String> roles(Jwt jwt) {
        List<String> value = jwt.getClaimAsStringList("roles");
        return value == null ? Set.of() : Set.copyOf(value);
    }

    private static String traceId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(TraceIdFilter.ATTRIBUTE));
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "入力内容を確認してください");
    }
}
