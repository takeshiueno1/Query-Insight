package com.query.insight.talent;

import jakarta.validation.constraints.Pattern;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/employees/{publicId}/talent-profile")
public class TalentProfileController {
    private final TalentProfileService service;

    public TalentProfileController(TalentProfileService service) {
        this.service = service;
    }

    @GetMapping
    TalentProfileService.TalentProfile find(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = "[0-9A-HJKMNP-TV-Z]{26}") String publicId) {
        return service.findAccessible(publicId, jwt.getClaimAsString("employeePublicId"),
                jwt.getClaimAsString("accountPublicId"), Set.copyOf(jwt.getClaimAsStringList("roles")));
    }
}
