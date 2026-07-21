package com.query.insight.analysis;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai-analyses")
public class AiAnalysisController {
    private final AiAnalysisService service;

    public AiAnalysisController(AiAnalysisService service) {
        this.service = service;
    }

    @PostMapping
    AiAnalysisService.AnalysisResponse create(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return service.create(jwt.getClaimAsString("employeePublicId"),
                jwt.getClaimAsString("accountPublicId"), (String) request.getAttribute("traceId"));
    }
}
