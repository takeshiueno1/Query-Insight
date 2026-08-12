package com.query.insight.talent;

import com.query.insight.common.ApiException;
import com.query.insight.common.TraceIdFilter;
import com.query.insight.talent.TalentPayloads.Payload;
import com.query.insight.talent.TalentSubmission.Type;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/talent-submissions")
public class TalentSubmissionController {
    private final TalentSubmissionService service;
    private final ObjectMapper objectMapper;

    public TalentSubmissionController(TalentSubmissionService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/me")
    List<Response> mine(@AuthenticationPrincipal Jwt jwt, @RequestParam String type) {
        return service.mine(employee(jwt), type(type)).stream().map(Response::from).toList();
    }

    @PostMapping("/{type}")
    Response create(@AuthenticationPrincipal Jwt jwt, @PathVariable String type,
            @Valid @RequestBody CreateRequest request, HttpServletRequest servletRequest) {
        Type talentType = type(type);
        return Response.from(service.create(employee(jwt), talentType, payload(talentType, request.payload()),
                account(jwt), traceId(servletRequest)));
    }

    @PutMapping("/{publicId:[0-7][0-9A-HJKMNP-TV-Z]{25}}")
    Response update(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId,
            @Valid @RequestBody UpdateRequest request, HttpServletRequest servletRequest) {
        var current = service.own(employee(jwt), publicId);
        return Response.from(service.update(employee(jwt), publicId, request.version(),
                payload(current.type(), request.payload()), account(jwt), traceId(servletRequest)));
    }

    @PostMapping("/{publicId:[0-7][0-9A-HJKMNP-TV-Z]{25}}/submit")
    Response submit(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId,
            @Valid @RequestBody VersionRequest request, HttpServletRequest servletRequest) {
        return Response.from(service.submit(employee(jwt), account(jwt), publicId,
                request.version(), traceId(servletRequest)));
    }

    private Payload payload(Type type, JsonNode node) {
        if (node == null || node.isNull()) {
            throw invalidPayload();
        }
        try {
            return switch (type) {
                case SKILL -> objectMapper.treeToValue(node, TalentPayloads.SkillPayload.class);
                case KNOWLEDGE -> objectMapper.treeToValue(node, TalentPayloads.KnowledgePayload.class);
                case CAREER -> objectMapper.treeToValue(node, TalentPayloads.CareerPayload.class);
                case CERTIFICATION -> objectMapper.treeToValue(node, TalentPayloads.CertificationPayload.class);
            };
        } catch (JacksonException | IllegalArgumentException exception) {
            throw invalidPayload();
        }
    }

    private static Type type(String value) {
        try {
            return Type.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TALENT_TYPE_INVALID", "申請種別が不正です");
        }
    }

    private static ApiException invalidPayload() {
        return new ApiException(HttpStatus.BAD_REQUEST, "TALENT_PAYLOAD_INVALID", "申請内容が不正です");
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

    public record CreateRequest(@NotNull JsonNode payload) {
    }

    public record UpdateRequest(@NotNull Long version, @NotNull JsonNode payload) {
    }

    public record VersionRequest(@NotNull Long version) {
    }

    public record Response(String publicId, Type type, String logicalPublicId, int revisionNo,
            TalentSubmission.Status status, JsonNode payload, Long baseRecordVersion, long version,
            java.time.Instant submittedAt, java.time.Instant decidedAt, String returnReason,
            java.time.Instant createdAt, java.time.Instant updatedAt) {
        static Response from(TalentSubmissionRepository.Row row) {
            return new Response(row.publicId(), row.type(), row.logicalPublicId(), row.revisionNo(), row.status(),
                    row.payload(), row.baseRecordVersion(), row.version(), row.submittedAt(), row.decidedAt(),
                    row.returnReason(), row.createdAt(), row.updatedAt());
        }
    }
}
