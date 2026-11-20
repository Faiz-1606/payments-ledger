package com.example.ledger.security;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ledger.user.User;
import com.example.ledger.user.UserController.UserResponse;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(request.fullName(), request.email(), request.password());
        return ResponseEntity.created(URI.create("/api/users/" + user.getId())).body(UserResponse.from(user));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        JwtTokenService.IssuedToken token = authService.login(request.email(), request.password());
        return new TokenResponse(token.accessToken(), "Bearer", token.expiresInSeconds());
    }

    /** BCrypt only uses the first 72 bytes of a password, hence the upper bound. */
    public record RegisterRequest(
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
    }
}
