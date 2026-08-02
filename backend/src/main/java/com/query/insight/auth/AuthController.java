package com.query.insight.auth;

import com.query.insight.common.ApiException;
import com.query.insight.common.TraceIdFilter;
import com.query.insight.security.AccountPrincipal;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final String REFRESH_COOKIE = "refresh_token";
    private final AuthService service;
    private final Duration refreshTtl;
    private final boolean secureCookie;

    public AuthController(AuthService service, @Value("${app.auth.refresh-token-ttl}") Duration refreshTtl,
            @Value("${app.auth.secure-cookie}") boolean secureCookie) {
        this.service = service;
        this.refreshTtl = refreshTtl;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        AuthService.Session session = service.login(request.loginId(), request.password(), traceId(httpRequest));
        return withSession(session);
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(HttpServletRequest request) {
        AuthService.Session session = service.refresh(cookie(request), traceId(request));
        return withSession(session);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        service.logout(cookieOrNull(request), traceId(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .header("Clear-Site-Data", "\"cache\", \"cookies\", \"storage\"").build();
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return new MeResponse(jwt.getClaimAsString("accountPublicId"), jwt.getClaimAsString("employeePublicId"),
                jwt.getClaimAsString("displayName"), Set.copyOf(jwt.getClaimAsStringList("roles")));
    }

    @PostMapping("/password-reset-requests")
    ResponseEntity<Void> requestReset() {
        return ResponseEntity.accepted().build();
    }

    private ResponseEntity<TokenResponse> withSession(AuthService.Session session) {
        AccountPrincipal principal = session.principal();
        TokenResponse body = new TokenResponse(session.accessToken(), "Bearer", session.expiresIn(),
                new MeResponse(principal.accountPublicId(), principal.employeePublicId(), principal.displayName(),
                        principal.roles()));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString()).body(body);
    }

    private ResponseCookie refreshCookie(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value).httpOnly(true).secure(secureCookie).sameSite("Strict")
                .path("/api/v1/auth").maxAge(refreshTtl).build();
    }

    private ResponseCookie expiredCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "").httpOnly(true).secure(secureCookie).sameSite("Strict")
                .path("/api/v1/auth").maxAge(Duration.ZERO).build();
    }

    private static String cookie(HttpServletRequest request) {
        String value = cookieOrNull(request);
        if (value == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "セッションの有効期限が切れました");
        }
        return value;
    }

    private static String cookieOrNull(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies()).filter(cookie -> REFRESH_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }

    private static String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.ATTRIBUTE);
    }

    public record LoginRequest(@NotBlank @Size(max = 254) String loginId,
            @NotBlank @Size(max = 128) String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn, MeResponse user) {
    }

    public record MeResponse(String accountPublicId, String employeePublicId, String displayName, Set<String> roles) {
    }
}
