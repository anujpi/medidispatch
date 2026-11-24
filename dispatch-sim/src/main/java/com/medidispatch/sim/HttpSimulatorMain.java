package com.medidispatch.sim;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Runs a Bangalore city against the running dispatch service, until Ctrl+C or {@code q}. Prints a status line every
 * 10 seconds.
 * <p>
 * Arguments (both optional): the service's address (default {@code http://localhost:8080}) and the city's speed,
 * simulated seconds per real second (default 10). The service's database must be empty: {@code docker compose down -v},
 * {@code docker compose up -d}, then start the service.
 * <p>
 * While it runs, type a command and press Enter (in IntelliJ: in the Run window): a number from 1 to
 * {@link LiveSimulation#MAX_SPEED} sets the speed, {@code p} pauses, {@code r} resumes, {@code s} prints the status,
 * {@code q} stops the city and quits.
 */
public final class HttpSimulatorMain {

    private static final long STATUS_EVERY_MS = 10_000;

    /** What {@link #command} answers to {@code q}: the caller stops the program. */
    static final String QUIT = "stopping the city";

    static final String HELP = "commands: a number 1-" + LiveSimulation.MAX_SPEED
            + " = speed, p = pause, r = resume, s = status, q = quit";

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
        System.out.println(HELP);
        Thread console = new Thread(() -> readCommands(city), "console");
        console.setDaemon(true);                              // never keeps the program alive on its own
        console.start();
        while (true) {
            Thread.sleep(STATUS_EVERY_MS);
            System.out.println(city.status());
        }
    }

    // one command per line, until the console closes (no console at all: it just ends)
    private static void readCommands(HttpSimulation city) {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line = in.readLine();
            while (line != null) {
                String reply = command(line, city);
                System.out.println(reply);
                if (reply.equals(QUIT)) {
                    System.exit(0);                           // the shutdown hook stops the city
                }
                line = in.readLine();
            }
        } catch (IOException e) {
            System.err.println("console closed: " + e.getMessage());
        }
    }

    /**
     * Carries out one typed command.
     *
     * @param line what was typed (spaces and case do not matter)
     * @param city the running city
     * @return what to print; {@link #QUIT} for {@code q}
     */
    static String command(String line, HttpSimulation city) {
        String c = line.trim().toLowerCase();
        switch (c) {
            case "p" -> {
                city.pause();
                return "paused: the service still runs in real time; crews on a trip will time out after ~30 s";
            }
            case "r" -> {
                city.resume();
                return "running at speed " + city.speed();
            }
            case "s" -> {
                return city.status();
            }
            case "q" -> {
                return QUIT;
            }
            default -> {
                int speed;
                try {
                    speed = Integer.parseInt(c);
                } catch (NumberFormatException e) {
                    return HELP;
                }
                if (speed < 1 || speed > LiveSimulation.MAX_SPEED) {
                    return "speed must be 1 to " + LiveSimulation.MAX_SPEED;
                }
                city.setSpeed(speed);
                return "speed " + speed + (city.paused() ? " (still paused: r to resume)" : "");
            }
        }
    }
}
