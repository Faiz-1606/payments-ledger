package com.example.ledger.user;

import java.net.URI;
import java.time.Instant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** {@code @Valid} runs Bean Validation on the body; failures become a 400 ProblemDetail. */
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        User user = userService.createUser(request.fullName(), request.email());
        return ResponseEntity.created(URI.create("/api/users/" + user.getId())).body(UserResponse.from(user));
    }

    @GetMapping("/{userId}")
    public UserResponse getUser(@PathVariable long userId) {
        return UserResponse.from(userService.getUser(userId));
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Email @Size(max = 255) String email) {
    }

    public record UserResponse(Long id, String fullName, String email, Instant createdAt) {
        static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getCreatedAt());
        }
    }
}
