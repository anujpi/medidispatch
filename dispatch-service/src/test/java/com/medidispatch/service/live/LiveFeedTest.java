package com.medidispatch.service.live;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.service.DispatchService;
import com.medidispatch.service.persistence.TestDatabaseConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the live feed over a REAL network connection, the way a browser uses it.
 *
 * <p>{@code RANDOM_PORT} starts the real Tomcat server on a free port ({@code @LocalServerPort} says which), and
 * Java's built-in {@link HttpClient} opens a real TCP connection to it. The body is read line by line AS IT ARRIVES:
 * for SSE the response never ends, so waiting for the end would wait forever.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestDatabaseConfig.class)
class LiveFeedTest {

    @LocalServerPort
    private int port;

    @Autowired
    private DispatchService service;

    /**
     * The first picture arrives at once; a unit registered afterwards shows up in a later message (the broadcast runs
     * every second). {@code @Timeout} fails the test instead of hanging forever if the stream stops sending.
     */
    @Test
    @Timeout(10)
    void aScreenGetsTheCityAtOnceThenEverySecond() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/live")).build();
        // ofInputStream: hand over the body as it arrives instead of after it ends.
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));

        // try (...) closes the connection at the end; the server's next send then fails and drops this screen.
        try (BufferedReader stream = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertEquals("event:snapshot", stream.readLine());
            String first = stream.readLine();
            assertTrue(first.startsWith("data:"));
            assertFalse(first.contains("live-u1"));

            service.registerUnit("live-u1", ResourceType.NURSE, new Location(28.62, 77.21), null, null);

            // Skip lines until a message mentions the new unit; it comes with the next broadcast.
            String line = stream.readLine();
            while (!line.contains("live-u1")) {
                line = stream.readLine();
            }
            assertTrue(line.startsWith("data:"));
        }
    }
}
