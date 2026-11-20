package com.medidispatch.service.live;

import com.medidispatch.service.DispatchService;
import com.medidispatch.service.LiveSnapshot;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The live feed for screens (control room, hospital): {@code GET /api/live} opens a Server-Sent Events stream, and
 * every second the whole picture ({@link LiveSnapshot}) is pushed down every open stream.
 *
 * <p>What travels on the wire is plain text, one message per second:
 * <pre>
 * event:snapshot
 * data:{"at":"...","units":[...],"openCases":[...],"activeTrips":[...]}
 * </pre>
 * A browser reads it with {@code new EventSource("/api/live")}, which also reconnects by itself if the connection
 * drops.
 *
 * <p>Why full snapshots instead of "what changed" messages: a screen that missed a message (reconnect, dropped
 * Wi-Fi) is correct again one second later, with no event ids, replay or heartbeats to build. The cost is size:
 * the whole fleet every second.
 */
// ponytail: whole snapshot every second, about 10 KB/s per screen at demo scale; at hundreds of units over a weak
// network, switch to deltas (domain events) with a snapshot on connect.
// ponytail: one thread sends to every screen in turn, so a frozen screen delays everyone's update until Tomcat
// gives up on it. Fine for a few screens on a LAN; a queue per screen if that ever matters.
@RestController
public class LiveFeed {

    /** Connections are closed after 30 minutes; EventSource reconnects by itself, and stale ones get cleaned up. */
    private static final long CONNECTION_TIMEOUT_MS = 30 * 60 * 1000L;

    private final DispatchService service;

    // Read every second, changed only when a screen connects or leaves. CopyOnWriteArrayList copies itself on each
    // change, and a loop walks the copy it started with, so adding or removing during a broadcast is safe.
    private final List<SseEmitter> screens = new CopyOnWriteArrayList<>();

    /**
     * @param service where the snapshots come from
     */
    public LiveFeed(DispatchService service) {
        this.service = service;
    }

    /**
     * {@code GET /api/live}: a screen connects.
     *
     * <p>Returning an {@link SseEmitter} tells Spring to send the headers but keep the response open; the request
     * thread goes back to Tomcat, and later any thread can push more messages with {@code send}. The first
     * snapshot is sent at once, so a new or reconnected screen draws immediately instead of waiting up to a second.
     *
     * @return the open stream
     * @throws IOException if the first message cannot be sent
     */
    @GetMapping(path = "/api/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() throws IOException {
        SseEmitter screen = new SseEmitter(CONNECTION_TIMEOUT_MS);
        // However the connection ends (normally, by timeout, or broken), the screen leaves the list.
        screen.onCompletion(() -> screens.remove(screen));
        screen.onTimeout(() -> screens.remove(screen));
        screen.onError(e -> screens.remove(screen));
        // Sent before this method returns: Spring holds it and writes it as soon as the stream starts.
        screen.send(asEvent(service.snapshot()));
        screens.add(screen);
        return screen;
    }

    /**
     * Pushes the current picture to every connected screen, once a second.
     *
     * <p>The lock in {@link DispatchService#snapshot()} is held only while the picture is copied; it is released
     * before the first {@code send}. Sending is network I/O and can block for seconds on a frozen screen, which must
     * never hold up dispatch. This runs on its own scheduler thread for the same reason
     * ({@code spring.task.scheduling.pool.size=2} in application.properties).
     */
    @Scheduled(fixedDelay = 1000)
    public void broadcast() {
        if (screens.isEmpty()) {
            return;
        }
        LiveSnapshot snapshot = service.snapshot();
        for (SseEmitter screen : screens) {
            try {
                screen.send(asEvent(snapshot));
            } catch (IOException | IllegalStateException e) {
                // IOException: the connection is gone (laptop closed, Wi-Fi died). IllegalStateException: the stream
                // already finished. Drop this screen and carry on with the others; Tomcat closes the connection.
                screens.remove(screen);
            }
        }
    }

    /**
     * Ends every open stream as soon as the application starts shutting down.
     *
     * <p>Spring Boot shuts down gracefully: it waits (up to 30 s) for requests still in progress to finish. An SSE
     * stream is a request that never finishes, so without this every shutdown would hang for the full 30 s while a
     * screen is open. {@link ContextClosedEvent} is announced at the very start of shutdown, before the web server
     * starts waiting. The screens' EventSource reconnects by itself once the server is back.
     */
    @EventListener(ContextClosedEvent.class)
    public void closeAll() {
        for (SseEmitter screen : screens) {
            screen.complete();
        }
        screens.clear();
    }

    /**
     * @param snapshot the picture to send
     * @return one SSE message named "snapshot"; the data is the snapshot as JSON (same shapes as the REST API)
     */
    private static SseEmitter.SseEventBuilder asEvent(LiveSnapshot snapshot) {
        return SseEmitter.event().name("snapshot").data(snapshot);
    }
}
