package com.traderbro;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TraderBro — stage-1 trading engine for MOEX equities via the T-Bank Invest API (sandbox).
 * Entry point of the mono-module application.
 */
@SpringBootApplication
public class TraderBroApplication {

    public static void main(String[] args) {
        SpringApplication.run(TraderBroApplication.class, args);
    }
}