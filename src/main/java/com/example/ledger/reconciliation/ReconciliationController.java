package com.example.ledger.reconciliation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ledger.common.error.NotFoundException;

/** ADMIN only: everything under /api/admin requires ROLE_ADMIN (SecurityConfig). */
@RestController
@RequestMapping("/api/admin/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /** Runs reconciliation now and returns the report. */
    @PostMapping("/runs")
    public ReconciliationReport runNow() {
        return reconciliationService.run();
    }

    /** The most recent report (from the schedule or a manual run). */
    @GetMapping("/latest")
    public ReconciliationReport latest() {
        return reconciliationService.latest().orElseThrow(NotFoundException::reconciliationReport);
    }
}
