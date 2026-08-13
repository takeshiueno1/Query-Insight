package com.query.insight.security;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final String issuer;
    private final Duration ttl;

    public JwtService(JwtEncoder encoder, @Value("${app.auth.issuer}") String issuer,
            @Value("${app.auth.access-token-ttl}") Duration ttl) {
        this.encoder = encoder;
        this.issuer = issuer;
        this.ttl = ttl;
    }

    public AccessToken issue(AccountPrincipal principal) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .notBefore(now.minusSeconds(1))
                .expiresAt(now.plus(ttl))
                .subject(principal.accountPublicId())
                .claim("accountPublicId", principal.accountPublicId())
                .claim("employeePublicId", principal.employeePublicId())
                .claim("displayName", principal.displayName())
                .claim("roles", principal.roles())
                .claim("scopes", principal.scopes())
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
        return new AccessToken(value, ttl.toSeconds());
    }

    public record AccessToken(String value, long expiresIn) {
    }
}
