package com.example.ledger.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ledger.security.CurrentUser;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** The caller's 50 most recent notifications (empty in the core profile: no consumer runs). */
    @GetMapping
    public List<NotificationResponse> myNotifications(@AuthenticationPrincipal Jwt jwt) {
        return notificationService.latestFor(CurrentUser.from(jwt).userId()).stream()
                .map(n -> new NotificationResponse(n.getId(), n.getEventId(), n.getAccountId(), n.getMessage(),
                        n.getCreatedAt()))
                .toList();
    }

    public record NotificationResponse(Long id, UUID eventId, Long accountId, String message, Instant createdAt) {
    }
}
