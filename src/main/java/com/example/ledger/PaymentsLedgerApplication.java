package com.example.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point. {@code @SpringBootApplication} turns on three things:
 * component scanning of this package and its sub-packages, auto-configuration
 * (DataSource, JPA, MVC, ... created from what is on the classpath), and
 * {@code @Configuration} support for this class.
 *
 * <p>{@code @ConfigurationPropertiesScan} registers every {@code @ConfigurationProperties}
 * record in these packages (e.g. {@code LockingProperties}) as a bean.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentsLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentsLedgerApplication.class, args);
    }
}
