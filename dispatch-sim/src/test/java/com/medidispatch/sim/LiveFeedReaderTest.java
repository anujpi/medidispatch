package com.medidispatch.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Splitting a server-sent-events stream into events, as Spring's SseEmitter writes it. */
public class LiveFeedReaderTest {

    private static List<String> events(String... lines) {
        List<String> got = new ArrayList<>();
        LiveFeedReader.readEvents(List.of(lines).iterator(), (String data) -> got.add(data));
        return got;
    }

    @Test
    void eachBlankLineEndsAnEvent() {
        assertEquals(List.of("{\"a\":1}", "{\"a\":2}"),
                events("event:snapshot", "data:{\"a\":1}", "", "event:snapshot", "data:{\"a\":2}", ""));
    }

    /** Several data lines in one event belong together, joined with newlines. */
    @Test
    void dataSplitOverSeveralLinesIsJoined() {
        assertEquals(List.of("{\"a\":\n1}"), events("data:{\"a\":", "data:1}", ""));
    }

    /** "data: x" and "data:x" mean the same: one space after the colon is optional. */
    @Test
    void oneSpaceAfterTheColonIsDropped() {
        assertEquals(List.of("x", "y"), events("data: x", "", "data:y", ""));
    }

    /** Comments, ids and events without data are skipped; an event cut off by a lost connection is not sent. */
    @Test
    void otherLinesAndUnfinishedEventsAreSkipped() {
        assertEquals(List.of("x"), events(":keep-alive", "id:7", "", "data:x", "", "data:half"));
    }
}
