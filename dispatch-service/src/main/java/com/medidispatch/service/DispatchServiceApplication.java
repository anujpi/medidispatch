package com.medidispatch.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Starts the dispatch service.
 *
 * <p>{@code @SpringBootApplication} tells Spring to scan this package for {@code @Configuration},
 * {@code @Service} and other annotated classes, build them, and start the embedded web server.
 * {@code @EnableScheduling} switches on {@code @Scheduled} methods; without it
 * {@link DispatchService#tick()} would never run.
 */
@SpringBootApplication
@EnableScheduling
public class DispatchServiceApplication {

    /**
     * Program entry point.
     *
     * @param args command-line arguments, passed to Spring (for example {@code --server.port=9090})
     */
    public static void main(String[] args) {
        SpringApplication.run(DispatchServiceApplication.class, args);
    }
}
