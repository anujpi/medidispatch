package com.medidispatch.sim;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Listens to the service's live feed ({@code GET /api/live}, server-sent events) on its own thread and hands every
 * event's data (one snapshot of the city, as JSON text) to a callback. If the connection drops, it waits a moment and
 * connects again, until {@link #stop()}.
 * <p>
 * The SSE format, as far as needed here: an event is a few lines such as {@code event:snapshot} and
 * {@code data:{...}}, ended by a blank line. Several {@code data:} lines in one event are joined with newlines.
 */
final class LiveFeedReader {

    private static final long RECONNECT_DELAY_MS = 2_000;

    private final ServiceClient service;
    private final Consumer<String> onEvent;
    private final Runnable onError;
    private final AtomicReference<Stream<String>> open = new AtomicReference<>();
    private volatile boolean stopped;
    private Thread thread;

    /**
     * @param service the service to listen to
     * @param onEvent receives each event's data, on the reader's thread
     * @param onError called once for every lost connection (so the caller can count it)
     */
    LiveFeedReader(ServiceClient service, Consumer<String> onEvent, Runnable onError) {
        this.service = service;
        this.onEvent = onEvent;
        this.onError = onError;
    }

    /** Connects and keeps listening on a daemon thread named "live-feed". */
    void start() {
        thread = new Thread(() -> listen(), "live-feed");
        thread.setDaemon(true);
        thread.start();
    }

    /** Closes the connection and ends the thread. */
    void stop() {
        stopped = true;
        Stream<String> lines = open.getAndSet(null);
        if (lines != null) {
            lines.close();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void listen() {
        while (!stopped) {
            try (Stream<String> lines = service.stream("/api/live")) {
                open.set(lines);
                readEvents(lines.iterator(), onEvent);
            } catch (RuntimeException e) {
                if (!stopped) {
                    onError.run();
                }
            }
            if (!stopped) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /**
     * Splits SSE lines into events and hands each event's data on. Lines other than {@code data:} (such as
     * {@code event:} or {@code id:}) are skipped; an event without data is skipped too.
     *
     * @param lines   the lines of the stream, as they arrive
     * @param onEvent receives each event's data
     */
    static void readEvents(Iterator<String> lines, Consumer<String> onEvent) {
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        while (lines.hasNext()) {
            String line = lines.next();
            if (line.isEmpty()) {                             // a blank line ends the event
                if (hasData) {
                    onEvent.accept(data.toString());
                }
                data.setLength(0);
                hasData = false;
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (value.startsWith(" ")) {
                    value = value.substring(1);               // one optional space after the colon
                }
                if (hasData) {
                    data.append('\n');
                }
                data.append(value);
                hasData = true;
            }
        }
    }
}
