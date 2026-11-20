package com.example.ledger.security;

import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.example.ledger.user.Role;
import com.example.ledger.user.User;
import com.example.ledger.user.UserService;

/**
 * Registration and login, written out explicitly rather than through Spring Security's
 * {@code AuthenticationManager}/{@code UserDetailsService} machinery, so every step is visible.
 */
@Service
public class AuthService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    /** A real hash of a throwaway password, compared against when the email is unknown. */
    private final String dummyHash;

    public AuthService(UserService userService, PasswordEncoder passwordEncoder, JwtTokenService jwtTokenService) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.dummyHash = passwordEncoder.encode("timing-equalizer-not-a-real-password");
    }

    public User register(String fullName, String email, String rawPassword) {
        return userService.createUser(fullName, email, passwordEncoder.encode(rawPassword), Role.USER);
    }

    /**
     * Checks the password and issues a token. When the email is unknown we still run one BCrypt
     * comparison, so "unknown email" and "wrong password" take the same time. Otherwise an
     * attacker could find registered emails by timing the responses.
     */
    public JwtTokenService.IssuedToken login(String email, String rawPassword) {
        Optional<User> user = userService.findByEmail(email);
        String hashToCheck = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = hashToCheck != null && passwordEncoder.matches(rawPassword, hashToCheck);
        if (user.isEmpty() || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        return jwtTokenService.issueFor(user.get());
    }
}
