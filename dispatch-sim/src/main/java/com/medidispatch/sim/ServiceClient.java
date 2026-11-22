package com.medidispatch.sim;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

/**
 * A small JSON-over-HTTP client for the running dispatch service, on Java's built-in {@link HttpClient} and Jackson.
 * Bodies go out as JSON (records and maps are turned into JSON by Jackson); answers come back as a status code and a
 * JSON tree. Error statuses are returned, not thrown: the caller decides what a 409 means. Only a failed connection
 * throws.
 */
final class ServiceClient {

    /**
     * One answer from the service.
     *
     * @param status the HTTP status code
     * @param body   the JSON body, or {@code null} if there was none
     */
    record Response(int status, JsonNode body) {

        /** @return {@code true} for a 2xx status */
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * @param base where the service runs, for example {@code http://localhost:8080}
     */
    ServiceClient(URI base) {
        this.base = base;
    }

    /**
     * @param path for example {@code /api/facilities}
     * @return the answer
     * @throws UncheckedIOException if the service cannot be reached
     */
    Response get(String path) {
        return send(request(path).GET());
    }

    /**
     * @param path for example {@code /api/trips/t-1/accept}
     * @param body what to send as JSON, or {@code null} for no body
     * @return the answer
     * @throws UncheckedIOException if the service cannot be reached
     */
    Response post(String path, Object body) {
        return send(request(path).POST(bodyOf(body)));
    }

    /**
     * @param path for example {@code /api/units/u-1/position}
     * @param body what to send as JSON
     * @return the answer
     * @throws UncheckedIOException if the service cannot be reached
     */
    Response put(String path, Object body) {
        return send(request(path).PUT(bodyOf(body)));
    }

    /**
     * Opens a response that never ends (server-sent events) and hands back its lines as they arrive. Closing the
     * returned stream closes the connection.
     *
     * @param path for example {@code /api/live}
     * @return the lines, read lazily from the open connection
     * @throws UncheckedIOException if the service cannot be reached or answers with an error
     */
    Stream<String> stream(String path) {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).header("Accept", "text/event-stream").GET()
                .build();
        try {
            HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new UncheckedIOException(new IOException(path + " answered " + response.statusCode()));
            }
            return response.body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("interrupted", e));
        }
    }

    /**
     * @param text JSON text
     * @return the JSON tree
     */
    JsonNode parse(String text) {
        return json.readTree(text);
    }

    /** Stops the underlying client; open streams are closed. */
    void close() {
        http.shutdownNow();
    }

    /**
     * @param node   a JSON object
     * @param member one of its members
     * @return the member as text, or {@code null} if it is missing or JSON null
     */
    static String text(JsonNode node, String member) {
        JsonNode value = node.get(member);
        return value == null || value.isNull() ? null : value.asString();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(TIMEOUT).header("Content-Type", "application/json");
    }

    private HttpRequest.BodyPublisher bodyOf(Object body) {
        if (body == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            JsonNode body = text == null || text.isBlank() ? null : json.readTree(text);
            return new Response(response.statusCode(), body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("interrupted", e));
        }
    }
}
