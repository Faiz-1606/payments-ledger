package com.example.ledger.security;

import java.util.List;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is calling, as read from a validated JWT: {@code sub} is the user id and {@code roles}
 * the role names. Services take this instead of the raw token so they stay independent of how
 * authentication happened.
 */
public record CurrentUser(long userId, boolean admin) {

    public static CurrentUser from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(JwtTokenService.ROLES_CLAIM);
        boolean admin = roles != null && roles.contains("ADMIN");
        return new CurrentUser(Long.parseLong(jwt.getSubject()), admin);
    }
}
