package com.example.ledger.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what.
 *
 * <p>Spring Security is a chain of servlet filters in front of the DispatcherServlet. For each
 * request it (1) authenticates: here {@code BearerTokenAuthenticationFilter} reads
 * {@code Authorization: Bearer <jwt>} and validates the token with {@link #jwtDecoder}; then
 * (2) authorizes: the URL rules below are checked against the resulting authorities
 * (ROLE_USER / ROLE_ADMIN).
 *
 * <p>Two layers of authorization:
 * <ul>
 *   <li><b>Role rules</b> (URL-based, here): deposits, reconciliation, actuator need ADMIN.</li>
 *   <li><b>Ownership rules</b> (data-based, {@link AccessPolicy}, called from services): a user may
 *       read only their own accounts and move money only out of their own accounts. URL
 *       rules can't express that because they don't know who owns account 42.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemDetailsSecurityHandlers problemHandlers)
            throws Exception {
        http
                // CSRF attacks ride on cookies the browser sends automatically. We authenticate
                // with an Authorization header, which a browser never adds on its own, so CSRF
                // protection is unnecessary for this API.
                .csrf(AbstractHttpConfigurer::disable)
                // No HTTP session: every request carries its own token.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Rules are checked top to bottom; the first match wins.
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/demo/**").permitAll() // only exists under the demo profile
                        .requestMatchers(HttpMethod.POST, "/api/deposits").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/users/me").authenticated()
                        .requestMatchers("/api/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        // Static files (the demo page) and the error page are public.
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/demo.html", "/favicon.ico", "/error")
                        .permitAll()
                        // Deny anything not listed, so a new endpoint is closed until someone opens it.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers));
        return http.build();
    }

    /**
     * BCrypt: a deliberately slow, salted password hash. The cost factor makes each guess take
     * about 2^strength rounds of work, which is what makes offline brute-forcing a stolen
     * hash expensive.
     */
    @Bean
    public PasswordEncoder passwordEncoder(SecurityProperties properties) {
        return new BCryptPasswordEncoder(properties.bcryptStrength());
    }

    @Bean
    public JwtEncoder jwtEncoder(SecurityProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    /**
     * Verifies the HS256 signature, then the default validators (exp/nbf with 60s clock skew)
     * plus our issuer. Any failure leads to a 401.
     */
    @Bean
    public JwtDecoder jwtDecoder(SecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /** Maps the {@code roles} claim ["ADMIN"] to the authority ROLE_ADMIN, which {@code hasRole("ADMIN")} checks. */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtTokenService.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static SecretKey signingKey(SecurityProperties properties) {
        return new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
