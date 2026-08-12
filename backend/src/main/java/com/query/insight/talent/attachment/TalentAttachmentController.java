package com.query.insight.talent.attachment;

import jakarta.validation.constraints.Pattern;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class TalentAttachmentController {
    private static final String PUBLIC_ID = "[0-7][0-9A-HJKMNP-TV-Z]{25}";
    private final TalentAttachmentService service;

    public TalentAttachmentController(TalentAttachmentService service) {
        this.service = service;
    }

    @PostMapping(path = "/api/v1/talent-submissions/{submissionPublicId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    TalentAttachmentService.Upload upload(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PUBLIC_ID) String submissionPublicId,
            @RequestParam long version,
            @RequestPart MultipartFile file) {
        return service.upload(jwt.getClaimAsString("employeePublicId"), submissionPublicId, version, file);
    }

    @GetMapping("/api/v1/talent-attachments/{attachmentPublicId}")
    ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PUBLIC_ID) String attachmentPublicId) {
        var download = service.download(jwt.getClaimAsString("accountPublicId"),
                jwt.getClaimAsString("employeePublicId"), roles(jwt), attachmentPublicId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(download.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment().filename(download.fileName()).build());
        headers.setCacheControl(CacheControl.noStore());
        headers.set("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(headers).body(download.content());
    }

    @DeleteMapping("/api/v1/talent-attachments/{attachmentPublicId}")
    VersionResponse delete(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PUBLIC_ID) String attachmentPublicId,
            @RequestParam long version) {
        return new VersionResponse(service.delete(jwt.getClaimAsString("employeePublicId"),
                attachmentPublicId, version));
    }

    private Set<String> roles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles == null ? Set.of() : new HashSet<>(roles);
    }

    record VersionResponse(long submissionVersion) {
    }
}
