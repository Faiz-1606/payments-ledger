package com.example.ledger.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code ledger.demo.*}, used only when the {@code demo} profile is active.
 *
 * @param accounts            how many demo wallets race against each other
 * @param openingBalancePaise each demo wallet's balance after a reset
 * @param maxTransfers        upper limit for N in one race (the endpoint is public)
 * @param threads             how many transfers run truly in parallel
 * @param maxAmountPaise      each transfer moves a random amount from 1 to this
 */
@ConfigurationProperties(prefix = "ledger.demo")
public record DemoProperties(
        @DefaultValue("4") int accounts,
        @DefaultValue("100000") long openingBalancePaise,
        @DefaultValue("2000") int maxTransfers,
        @DefaultValue("16") int threads,
        @DefaultValue("5000") long maxAmountPaise) {
}
