package com.medidispatch.routing;

import com.medidispatch.core.domain.Location;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Collects road points and road pieces in any order and builds a {@link RoadGraph}.
 * <p>
 * OpenStreetMap numbers its nodes with huge ids (like 5294831127); arrays need small ones, so {@link #addNode} hands
 * out 0, 1, 2, ... and remembers which OpenStreetMap node got which. The arrays grow by doubling when full (what
 * {@code ArrayList} does inside, without turning every number into an object).
 * <p>
 * {@link #build()} groups the edges by their start node with a counting sort: count each node's edges, turn the
 * counts into start positions (a running total), then drop every edge into the next free slot of its node's block.
 * Two passes over the edges and one over the nodes: linear time, where a general sort would be n log n.
 */
public final class GraphBuilder {

    private final Map<Long, Integer> indexOf = new HashMap<>();
    private double[] latitude = new double[1024];
    private double[] longitude = new double[1024];
    private int nodes;

    private int[] from = new int[1024];
    private int[] to = new int[1024];
    private float[] length = new float[1024];
    private byte[] roadClass = new byte[1024];
    private int edges;

    /**
     * Adds a road point. The same OpenStreetMap node added again (a junction appears in every road through it)
     * keeps its first number and position.
     *
     * @param osmId OpenStreetMap's id for the node
     * @param lat   its latitude
     * @param lon   its longitude
     * @return the node's number in the graph
     */
    public int addNode(long osmId, double lat, double lon) {
        Integer known = indexOf.get(osmId);
        if (known != null) {
            return known;
        }
        if (nodes == latitude.length) {
            latitude = Arrays.copyOf(latitude, nodes * 2);
            longitude = Arrays.copyOf(longitude, nodes * 2);
        }
        latitude[nodes] = lat;
        longitude[nodes] = lon;
        indexOf.put(osmId, nodes);
        return nodes++;
    }

    /**
     * Adds a road piece between two added nodes, measured in metres. A piece from a node to itself (OpenStreetMap
     * sometimes repeats a point) is skipped.
     *
     * @param fromOsmId the start node's OpenStreetMap id
     * @param toOsmId   the end node's OpenStreetMap id
     * @param c         the kind of road
     * @param forward   whether traffic may go from the start to the end
     * @param backward  whether traffic may go from the end to the start (both: a two-way road)
     * @throws IllegalArgumentException if a node was never added or the class is missing
     */
    public void addRoad(long fromOsmId, long toOsmId, RoadClass c, boolean forward, boolean backward) {
        if (c == null) {
            throw new IllegalArgumentException("road class is required");
        }
        Integer a = indexOf.get(fromOsmId);
        Integer b = indexOf.get(toOsmId);
        if (a == null || b == null) {
            throw new IllegalArgumentException("unknown node " + (a == null ? fromOsmId : toOsmId));
        }
        if (a.equals(b)) {
            return;
        }
        float metres = (float) (new Location(latitude[a], longitude[a])
                .distanceKmTo(new Location(latitude[b], longitude[b])) * 1000);
        if (forward) {
            addEdge(a, b, metres, c);
        }
        if (backward) {
            addEdge(b, a, metres, c);
        }
    }

    /** @return nodes added so far */
    public int nodeCount() { return nodes; }

    /** @return directed edges added so far */
    public int edgeCount() { return edges; }

    /**
     * @return the graph, with every node's edges grouped together (see the class description)
     */
    public RoadGraph build() {
        int[] firstEdge = new int[nodes + 1];
        for (int e = 0; e < edges; e++) {
            firstEdge[from[e] + 1]++;                         // 1. count, one place to the right
        }
        for (int n = 0; n < nodes; n++) {
            firstEdge[n + 1] += firstEdge[n];                 // 2. running total: where each block starts
        }
        int[] next = Arrays.copyOf(firstEdge, nodes);         // the next free slot in each node's block
        int[] target = new int[edges];
        float[] len = new float[edges];
        byte[] cls = new byte[edges];
        for (int e = 0; e < edges; e++) {
            int slot = next[from[e]]++;                       // 3. place
            target[slot] = to[e];
            len[slot] = length[e];
            cls[slot] = roadClass[e];
        }
        return new RoadGraph(Arrays.copyOf(latitude, nodes), Arrays.copyOf(longitude, nodes),
                firstEdge, target, len, cls);
    }

    private void addEdge(int a, int b, float metres, RoadClass c) {
        if (edges == from.length) {
            from = Arrays.copyOf(from, edges * 2);
            to = Arrays.copyOf(to, edges * 2);
            length = Arrays.copyOf(length, edges * 2);
            roadClass = Arrays.copyOf(roadClass, edges * 2);
        }
        from[edges] = a;
        to[edges] = b;
        length[edges] = metres;
        roadClass[edges] = (byte) c.ordinal();
        edges++;
    }
}
