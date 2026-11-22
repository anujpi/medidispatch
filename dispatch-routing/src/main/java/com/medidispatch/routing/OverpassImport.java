package com.medidispatch.routing;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns OpenStreetMap road data, as the Overpass API answers it ({@code [out:json]}, ways plus their nodes), into a
 * {@link RoadGraph}.
 * <p>
 * The answer is one JSON object whose {@code elements} array mixes nodes
 * ({@code {"type": "node", "id": ..., "lat": ..., "lon": ...}}) and ways
 * ({@code {"type": "way", "nodes": [ids...], "tags": {"highway": ..., "oneway": ...}}}). A way's consecutive
 * points become road pieces: A, B, C, D gives A-B, B-C, C-D.
 * <p>
 * <b>Streamed, not loaded whole.</b> A city's file is 50-150 MB; read into one tree it would need well over a
 * gigabyte. Jackson's streaming parser walks the file token by token and only turns one element at a time into a
 * small tree. Nodes go straight into the builder; ways are kept as small records and turned into roads at the end,
 * once every node is known, so the order Overpass sends things in does not matter.
 * <p>
 * Map data &copy; OpenStreetMap contributors (ODbL).
 */
public final class OverpassImport {

    private record Way(long[] nodes, RoadClass roadClass, boolean forward, boolean backward) {}

    private OverpassImport() {}

    /**
     * @param json the Overpass answer; not closed here
     * @return every drivable road in it (not cleaned up: see {@link GraphCleanup})
     * @throws tools.jackson.core.JacksonException if the JSON is broken
     * @throws IllegalArgumentException            if a way refers to a node the answer does not contain
     */
    public static RoadGraph read(InputStream json) {
        // Jackson 3 refuses by default when more data follows what it read; here that is the whole point: one element
        // is read out of the middle of the stream at a time
        JsonMapper mapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        GraphBuilder builder = new GraphBuilder();
        List<Way> ways = new ArrayList<>();
        try (JsonParser parser = mapper.createParser(json)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.PROPERTY_NAME && "elements".equals(parser.currentName())) {
                    parser.nextToken();                       // the array's start
                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        JsonNode element = mapper.readTree(parser);   // one element only
                        String type = element.path("type").asString("");
                        if (type.equals("node")) {
                            builder.addNode(element.path("id").asLong(),
                                    element.path("lat").asDouble(), element.path("lon").asDouble());
                        } else if (type.equals("way")) {
                            toWay(element).ifPresent(w -> ways.add(w));
                        }
                    }
                }
            }
        }
        for (Way w : ways) {
            for (int i = 1; i < w.nodes().length; i++) {
                builder.addRoad(w.nodes()[i - 1], w.nodes()[i], w.roadClass(), w.forward(), w.backward());
            }
        }
        return builder.build();
    }

    // a drivable way, or empty for a footway, cycleway, building outline...
    private static Optional<Way> toWay(JsonNode way) {
        JsonNode tags = way.path("tags");
        Optional<RoadClass> roadClass = RoadClass.fromHighwayTag(tags.path("highway").asString(""));
        if (roadClass.isEmpty()) {
            return Optional.empty();
        }
        JsonNode ids = way.path("nodes");
        long[] nodes = new long[ids.size()];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = ids.get(i).asLong();
        }
        boolean[] directions = directions(tags, roadClass.get());
        return Optional.of(new Way(nodes, roadClass.get(), directions[0], directions[1]));
    }

    /**
     * OpenStreetMap's one-way rules: {@code oneway=yes/true/1} forward only, {@code -1/reverse} backward only, an
     * explicit {@code no/false/0} both ways (even on a roundabout). Without the tag, roundabouts and motorways are
     * one-way and everything else is two-way.
     *
     * @param tags      the way's tags
     * @param roadClass the way's class
     * @return {forward allowed, backward allowed}
     */
    static boolean[] directions(JsonNode tags, RoadClass roadClass) {
        String oneway = tags.path("oneway").asString("");
        switch (oneway) {
            case "yes", "true", "1" -> { return new boolean[] {true, false}; }
            case "-1", "reverse" -> { return new boolean[] {false, true}; }
            case "no", "false", "0" -> { return new boolean[] {true, true}; }
            default -> { }
        }
        String junction = tags.path("junction").asString("");
        if (junction.equals("roundabout") || junction.equals("circular") || roadClass == RoadClass.MOTORWAY) {
            return new boolean[] {true, false};
        }
        return new boolean[] {true, true};
    }
}
