package com.example.ledger.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ledger.transfer.locking.LockingMode;

/**
 * Backend for the race demo page (static/demo.html). Exists only under the {@code demo}
 * profile. Public (no token) by design: it can only ever touch DEMO wallets, and races are
 * capped in size and run one at a time.
 */
@RestController
@RequestMapping("/api/demo")
@Profile("demo")
public class DemoController {

    private final DemoService demoService;

    public DemoController(DemoService demoService) {
        this.demoService = demoService;
    }

    @GetMapping("/state")
    public DemoService.DemoState state() {
        return demoService.state();
    }

    @PostMapping("/race")
    public DemoService.RaceResult race(@Valid @RequestBody RaceRequest request) {
        return demoService.race(request.mode(), request.transfers());
    }

    /** Replaces the demo wallets with a fresh, correctly funded set. */
    @PostMapping("/reset")
    public DemoService.DemoState reset() {
        demoService.reset();
        return demoService.state();
    }

    public record RaceRequest(@NotNull LockingMode mode, @NotNull @Positive Integer transfers) {
    }
}
