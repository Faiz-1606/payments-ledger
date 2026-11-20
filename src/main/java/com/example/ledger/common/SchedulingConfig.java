package com.example.ledger.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} methods (reconciliation job, outbox publisher). Each job is a bean
 * with its own {@code @ConditionalOnProperty}, so it can be switched off by configuration.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
