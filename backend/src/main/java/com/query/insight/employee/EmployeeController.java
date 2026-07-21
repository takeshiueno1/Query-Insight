package com.query.insight.employee;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {
    private final EmployeeService service;

    public EmployeeController(EmployeeService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER','SALES','HR','SYSTEM_ADMIN','AUDITOR')")
    EmployeeService.PageResponse<EmployeeService.EmployeeSummary> search(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) @Size(max = 30) String department,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.search(employeePublicId(jwt), roles(jwt), keyword, department, page, size);
    }

    @GetMapping("/{publicId}")
    EmployeeService.EmployeeDetail detail(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = "[0-9A-HJKMNP-TV-Z]{26}") String publicId) {
        return service.findAccessible(publicId, employeePublicId(jwt), roles(jwt));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('HR','SYSTEM_ADMIN')")
    EmployeeService.EmployeeDetail create(@Valid @RequestBody EmployeeRequest request) {
        return service.create(request.toService());
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAnyRole('HR','SYSTEM_ADMIN')")
    EmployeeService.EmployeeDetail update(
            @PathVariable @Pattern(regexp = "[0-9A-HJKMNP-TV-Z]{26}") String publicId,
            @Valid @RequestBody EmployeeRequest request) {
        return service.update(publicId, request.toService());
    }

    @PostMapping("/{publicId}/deactivate")
    @PreAuthorize("hasAnyRole('HR','SYSTEM_ADMIN')")
    void deactivate(@PathVariable @Pattern(regexp = "[0-9A-HJKMNP-TV-Z]{26}") String publicId,
            @RequestParam long version) {
        service.deactivate(publicId, version);
    }

    private static String employeePublicId(Jwt jwt) {
        return jwt.getClaimAsString("employeePublicId");
    }

    private static Set<String> roles(Jwt jwt) {
        return Set.copyOf(jwt.getClaimAsStringList("roles"));
    }

    public record EmployeeRequest(@NotBlank @Size(max = 30) String employeeNo,
            @NotBlank @Size(max = 50) String lastName, @NotBlank @Size(max = 50) String firstName,
            @NotBlank @Email @Size(max = 254) String email, @Size(max = 30) String departmentCode,
            String managerPublicId, @Size(max = 100) String positionName,
            @NotBlank @Pattern(regexp = "ACTIVE|LEAVE|RETIRED") String employmentStatus,
            LocalDate hireDate, long version) {
        EmployeeService.EmployeeRequest toService() {
            return new EmployeeService.EmployeeRequest(employeeNo, lastName, firstName, email, departmentCode,
                    managerPublicId, positionName, employmentStatus, hireDate, version);
        }
    }
}
