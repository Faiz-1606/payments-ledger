package com.example.ledger.security;

import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.example.ledger.user.User;

/**
 * Issues signed access tokens (JWTs).
 *
 * <p>A JWT is three base64url parts, {@code header.payload.signature}. The payload (claims) is
 * readable by anyone; the HMAC signature proves we issued it and that nobody changed it. Because
 * the server can verify a token on its own, no session is stored anywhere ("stateless").
 *
 * <p>Claims we write: {@code iss} (issuer), {@code sub} (user id), {@code iat} (issued at),
 * {@code exp} (expiry), {@code roles} (e.g. ["USER"]).
 */
@Service
public class JwtTokenService {

    public static final String ROLES_CLAIM = "roles";

    private final JwtEncoder jwtEncoder;
    private final SecurityProperties properties;

    public JwtTokenService(JwtEncoder jwtEncoder, SecurityProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public IssuedToken issueFor(User user) {
        return issue(user.getId(), user.getRole().name(), Instant.now());
    }

    /** Package-visible for tests that need tokens issued at a chosen time (e.g. expired ones). */
    IssuedToken issue(long userId, String role, Instant issuedAt) {
        Instant expiresAt = issuedAt.plus(properties.tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(Long.toString(userId))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(ROLES_CLAIM, List.of(role))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, properties.tokenTtl().toSeconds());
    }

    public record IssuedToken(String accessToken, long expiresInSeconds) {
    }
}
