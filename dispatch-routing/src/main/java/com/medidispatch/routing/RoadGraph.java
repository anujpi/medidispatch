package com.medidispatch.routing;

import com.medidispatch.core.domain.Location;

/**
 * A city's road network as a directed graph: junctions and road points are nodes, road pieces are edges. Read-only
 * once built (by {@link GraphBuilder}).
 * <p>
 * <b>Stored in arrays, not objects</b> (the "compressed sparse row" layout routing engines use). Every node is a
 * number 0..n-1; its position is {@code latitude[n]}, {@code longitude[n]}. The edges leaving node {@code n} sit next
 * to each other, in slots {@code firstEdge[n]} up to (not including) {@code firstEdge[n + 1]}. For three junctions with
 * roads 0&rarr;1, 0&rarr;2 and 2&rarr;1:
 * <pre>
 * firstEdge  = [0, 2, 2, 3]      node 0: slots 0..1, node 1: none, node 2: slot 2
 * edgeTarget = [1, 2, 1]
 * </pre>
 * Walking a node's roads is then one tight loop over neighbouring memory:
 * {@code for (int e = g.firstEdge(n); e < g.edgeEnd(n); e++) { int next = g.target(e); ... }}. Half a million nodes and
 * a million edges fit in about 20 MB, where objects would need well over 100 MB.
 * <p>
 * The graph is directed: a two-way road is two edges, a one-way street one.
 */
public final class RoadGraph {

    private static final RoadClass[] CLASSES = RoadClass.values();

    private final double[] latitude;
    private final double[] longitude;
    private final int[] firstEdge;
    private final int[] edgeTarget;
    private final float[] edgeLength;
    private final byte[] edgeClass;

    /**
     * Takes the arrays as they are (no copy). Package-private: only the builder and the file loader make graphs.
     *
     * @param latitude   every node's latitude
     * @param longitude  every node's longitude
     * @param firstEdge  where each node's edges start; one extra entry at the end equals the number of edges
     * @param edgeTarget each edge's end node
     * @param edgeLength each edge's length in metres ({@code float}: centimetre-accurate over kilometres, half the
     *                   size of {@code double})
     * @param edgeClass  each edge's {@link RoadClass}, as its position in the enum
     * @throws IllegalArgumentException if the arrays do not fit together
     */
    RoadGraph(double[] latitude, double[] longitude, int[] firstEdge,
              int[] edgeTarget, float[] edgeLength, byte[] edgeClass) {
        if (latitude.length != longitude.length || firstEdge.length != latitude.length + 1) {
            throw new IllegalArgumentException("node arrays do not match");
        }
        int edges = edgeTarget.length;
        if (edgeLength.length != edges || edgeClass.length != edges || firstEdge[latitude.length] != edges) {
            throw new IllegalArgumentException("edge arrays do not match");
        }
        this.latitude = latitude;
        this.longitude = longitude;
        this.firstEdge = firstEdge;
        this.edgeTarget = edgeTarget;
        this.edgeLength = edgeLength;
        this.edgeClass = edgeClass;
    }

    /** @return how many nodes (junctions and road points) */
    public int nodeCount()             { return latitude.length; }

    /** @return how many directed edges (a two-way road counts twice) */
    public int edgeCount()             { return edgeTarget.length; }

    public double latitude(int node)   { return latitude[node]; }
    public double longitude(int node)  { return longitude[node]; }

    /**
     * @param node a node number
     * @return where it is
     */
    public Location location(int node) { return new Location(latitude[node], longitude[node]); }

    /** @return the first slot of the node's edges */
    public int firstEdge(int node)     { return firstEdge[node]; }

    /** @return one past the last slot of the node's edges (equal to {@link #firstEdge} when it has none) */
    public int edgeEnd(int node)       { return firstEdge[node + 1]; }

    /** @return the node the edge leads to */
    public int target(int edge)        { return edgeTarget[edge]; }

    /** @return the edge's length in metres */
    public double length(int edge)     { return edgeLength[edge]; }

    /** @return the edge's kind of road */
    public RoadClass roadClass(int edge) { return CLASSES[edgeClass[edge]]; }
}
