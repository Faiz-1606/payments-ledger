package com.example.ledger.user;

import java.time.Instant;

import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ledger.security.CurrentUser;

/** Users are created through {@code POST /api/auth/register}; this controller only reads them. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * {@code @AuthenticationPrincipal} injects the principal of the current request's
     * Authentication. For a JWT-authenticated request that is the decoded {@link Jwt}.
     */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.from(userService.getUser(CurrentUser.from(jwt).userId()));
    }

    /** ADMIN only (see SecurityConfig). */
    @GetMapping("/{userId}")
    public UserResponse getUser(@PathVariable @Positive long userId) {
        return UserResponse.from(userService.getUser(userId));
    }

    public record UserResponse(Long id, String fullName, String email, Role role, Instant createdAt) {
        public static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getRole(),
                    user.getCreatedAt());
        }
    }
}
