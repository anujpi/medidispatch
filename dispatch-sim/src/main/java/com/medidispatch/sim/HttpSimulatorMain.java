package com.medidispatch.sim;

import java.net.URI;

/**
 * Runs a Bangalore city against the running dispatch service, until Ctrl+C. Prints a status line every 10 seconds.
 * <p>
 * Arguments (both optional): the service's address (default {@code http://localhost:8080}) and the city's speed,
 * simulated seconds per real second (default 10). The service's database must be empty: {@code docker compose down -v},
 * {@code docker compose up -d}, then start the service.
 */
public final class HttpSimulatorMain {

    private static final long STATUS_EVERY_MS = 10_000;

    private HttpSimulatorMain() {}

    /**
     * @param args optional: the service's address, then the speed
     * @throws InterruptedException if the main thread is interrupted while waiting
     */
    public static void main(String[] args) throws InterruptedException {
        URI service = URI.create(args.length > 0 ? args[0] : "http://localhost:8080");
        int speed = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        LiveConfig config = LiveConfig.bangalore().withStartSpeed(speed);
        HttpSimulation city = new HttpSimulation(config, service);
        try {
            city.setUp();
        } catch (RuntimeException e) {
            System.err.println("Could not set up the city at " + service + ": " + e.getMessage());
            System.exit(1);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> city.stop()));
        city.start();
        System.out.printf("Bangalore city running against %s at speed %d (seed %d). Ctrl+C to stop.%n",
                service, speed, config.seed());
        while (true) {
            Thread.sleep(STATUS_EVERY_MS);
            System.out.println(city.status());
        }
    }
}
