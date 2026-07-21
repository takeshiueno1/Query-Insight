package com.query.insight.evaluation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/evaluations/me")
public class EvaluationController {
    private final EvaluationService service;

    public EvaluationController(EvaluationService service) {
        this.service = service;
    }

    @GetMapping
    EvaluationService.SelfEvaluation get(@AuthenticationPrincipal Jwt jwt) {
        return service.get(jwt.getClaimAsString("employeePublicId"));
    }

    @PutMapping
    EvaluationService.SelfEvaluation save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SaveRequest request) {
        return service.save(jwt.getClaimAsString("employeePublicId"), request.toService());
    }

    @PostMapping("/submit")
    EvaluationService.SelfEvaluation submit(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SaveRequest request) {
        return service.submit(jwt.getClaimAsString("employeePublicId"), request.toService());
    }

    public record SaveRequest(long version, @NotEmpty @Size(min = 6, max = 6) List<@Valid SaveDetail> details) {
        EvaluationService.SaveRequest toService() {
            return new EvaluationService.SaveRequest(version, details.stream().map(SaveDetail::toService).toList());
        }
    }
    public record SaveDetail(@NotBlank String axisCode, int level, @Size(max = 1500) String evidence) {
        EvaluationService.SaveDetail toService() {
            return new EvaluationService.SaveDetail(axisCode, level, evidence == null ? "" : evidence);
        }
    }
}
