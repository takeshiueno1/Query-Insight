package com.query.insight.auth;

import com.query.insight.audit.AuditService;
import com.query.insight.common.ApiException;
import com.query.insight.common.Hashing;
import com.query.insight.common.PublicIdGenerator;
import com.query.insight.security.AccountPrincipal;
import com.query.insight.security.JwtService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AuthRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final Duration refreshTtl;

    public AuthService(AuthRepository repository, PasswordEncoder passwordEncoder, JwtService jwtService,
            AuditService auditService, @Value("${app.auth.refresh-token-ttl}") Duration refreshTtl) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.refreshTtl = refreshTtl;
    }

    @Transactional
    public Session login(String loginId, String password, String traceId) {
        String normalized = loginId.strip().toLowerCase(Locale.ROOT);
        AuthRepository.AccountRecord account = repository.findAccount(normalized)
                .orElseThrow(AuthService::invalidCredentials);
        Instant now = Instant.now();
        if (!"ACTIVE".equals(account.status()) || account.lockedUntil() != null && account.lockedUntil().isAfter(now)) {
            auditService.record(account.id(), "AUTH_LOGIN", "ACCOUNT", account.publicId(), "DENIED", null, traceId);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(password, account.passwordHash())) {
            int failedCount = account.failedCount() + 1;
            Instant lockedUntil = failedCount >= 5 ? now.plus(Duration.ofMinutes(15)) : null;
            repository.loginFailed(account.id(), failedCount, lockedUntil);
            auditService.record(account.id(), "AUTH_LOGIN", "ACCOUNT", account.publicId(), "DENIED", null, traceId);
            throw invalidCredentials();
        }
        repository.loginSucceeded(account.id(), now);
        auditService.record(account.id(), "AUTH_LOGIN", "ACCOUNT", account.publicId(), "SUCCESS", "SELF", traceId);
        return createSession(repository.principal(account), PublicIdGenerator.next(), now);
    }

    @Transactional
    public Session refresh(String rawToken, String traceId) {
        Instant now = Instant.now();
        AuthRepository.RefreshRecord refresh = repository.findRefreshToken(Hashing.sha256(rawToken))
                .orElseThrow(AuthService::invalidRefresh);
        if (refresh.revokedAt() != null || refresh.expiresAt().isBefore(now)) {
            throw invalidRefresh();
        }
        if (refresh.usedAt() != null) {
            repository.revokeFamily(refresh.familyId(), now);
            throw invalidRefresh();
        }
        AuthRepository.AccountRecord account = repository.findAccountById(refresh.accountId())
                .filter(candidate -> "ACTIVE".equals(candidate.status()))
                .orElseThrow(AuthService::invalidRefresh);
        Session session = createSession(repository.principal(account), refresh.familyId(), now);
        long replacementId = repository.findRefreshToken(Hashing.sha256(session.refreshToken())).orElseThrow().id();
        repository.rotateRefreshToken(refresh.id(), replacementId, now);
        auditService.record(account.id(), "AUTH_REFRESH", "ACCOUNT", account.publicId(), "SUCCESS", "SELF", traceId);
        return session;
    }

    @Transactional
    public void logout(String rawToken, String traceId) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findRefreshToken(Hashing.sha256(rawToken)).ifPresent(refresh -> {
            repository.revokeFamily(refresh.familyId(), Instant.now());
            auditService.record(refresh.accountId(), "AUTH_LOGOUT", "ACCOUNT", null, "SUCCESS", "SELF", traceId);
        });
    }

    public AccountPrincipal me(long accountId) {
        return repository.findAccountById(accountId).map(repository::principal).orElseThrow(AuthService::invalidRefresh);
    }

    private Session createSession(AccountPrincipal principal, String familyId, Instant now) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawRefresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.insertRefreshToken(principal.accountId(), Hashing.sha256(rawRefresh), familyId, now, now.plus(refreshTtl));
        JwtService.AccessToken access = jwtService.issue(principal);
        return new Session(access.value(), access.expiresIn(), rawRefresh, principal);
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "ログインIDまたはパスワードが正しくありません");
    }

    private static ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "セッションの有効期限が切れました。再度ログインしてください");
    }

    public record Session(String accessToken, long expiresIn, String refreshToken, AccountPrincipal principal) {
    }
}
