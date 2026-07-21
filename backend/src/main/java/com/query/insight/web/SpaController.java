package com.query.insight.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
    @GetMapping({
            "/", "/login", "/employees", "/employees/new", "/employees/{publicId}",
            "/employees/{publicId}/edit", "/careers/edit", "/skills/edit", "/certifications/edit",
            "/evaluations/self", "/evaluations/manager", "/evaluations/manage", "/evaluations/history",
            "/analysis", "/notifications", "/masters", "/audit", "/password/change", "/password/reset",
            "/approvals", "/organization", "/accounts", "/error", "/forbidden"
    })
    String index() {
        return "forward:/index.html";
    }
}
