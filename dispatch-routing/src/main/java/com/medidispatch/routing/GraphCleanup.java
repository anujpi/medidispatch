package com.medidispatch.routing;

import java.util.Arrays;

/**
 * Removes the parts of a road graph a vehicle could get stuck in or never reach.
 * <p>
 * Raw map data has islands: a gated estate whose only road is missing from the map, a one-way stub with no way out.
 * If a patient's home snapped onto one, there would be no route at all. So only the largest <b>strongly connected
 * component</b> is kept: the biggest set of nodes in which every node can drive to every other AND back. (A plain
 * "connected" check would keep a one-way dead end: you can drive in, never out.)
 * <p>
 * <b>Kosaraju's algorithm.</b> (1) A depth-first search over the whole graph notes the order in which nodes finish
 * (all their roads explored). (2) Every road is reversed. (3) On the reversed graph, taking nodes from the last to
 * finish to the first, each search from a node not yet labelled reaches exactly one component: in the reversed graph
 * a node only reaches nodes that could reach it in the original, and the finish order stops a search from leaking
 * into another component.
 * <p>
 * Both searches use an explicit stack (plain arrays), not recursion: a recursive search goes as deep as the longest
 * chain of roads, hundreds of thousands in a city, and Java's call stack overflows at about ten thousand.
 */
public final class GraphCleanup {

    private GraphCleanup() {}

    /**
     * @param g the raw graph
     * @return a new graph with only the largest strongly connected component, renumbered; an empty graph unchanged
     */
    public static RoadGraph largestStronglyConnected(RoadGraph g) {
        int n = g.nodeCount();
        if (n == 0) {
            return g;
        }
        int[] finishOrder = finishOrder(g);
        RoadGraph reversed = reverse(g);
        int[] component = new int[n];
        Arrays.fill(component, -1);
        int components = 0;
        int[] size = new int[n];
        int[] stack = new int[n];
        for (int i = n - 1; i >= 0; i--) {                    // last to finish first
            int start = finishOrder[i];
            if (component[start] != -1) {
                continue;
            }
            int top = 0;
            stack[top++] = start;
            component[start] = components;
            while (top > 0) {
                int node = stack[--top];
                size[components]++;
                for (int e = reversed.firstEdge(node); e < reversed.edgeEnd(node); e++) {
                    int next = reversed.target(e);
                    if (component[next] == -1) {
                        component[next] = components;         // labelled when pushed, so pushed only once
                        stack[top++] = next;
                    }
                }
            }
            components++;
        }
        int largest = 0;
        for (int c = 1; c < components; c++) {
            if (size[c] > size[largest]) {
                largest = c;
            }
        }
        return keep(g, component, largest);
    }

    // step 1: the order in which a depth-first search finishes the nodes
    private static int[] finishOrder(RoadGraph g) {
        int n = g.nodeCount();
        boolean[] seen = new boolean[n];
        int[] order = new int[n];
        int finished = 0;
        int[] stackNode = new int[n];                         // the path being explored...
        int[] stackEdge = new int[n];                         // ...and, per node on it, the next edge to try
        for (int start = 0; start < n; start++) {
            if (seen[start]) {
                continue;
            }
            int top = 0;
            stackNode[top] = start;
            stackEdge[top] = g.firstEdge(start);
            top++;
            seen[start] = true;
            while (top > 0) {
                int node = stackNode[top - 1];
                int e = stackEdge[top - 1];
                if (e < g.edgeEnd(node)) {
                    stackEdge[top - 1] = e + 1;
                    int next = g.target(e);
                    if (!seen[next]) {
                        seen[next] = true;
                        stackNode[top] = next;
                        stackEdge[top] = g.firstEdge(next);
                        top++;
                    }
                } else {
                    order[finished++] = node;                 // every road from here explored: it finishes
                    top--;
                }
            }
        }
        return order;
    }

    // step 2: every road pointing the other way (the old node numbers double as ids for the builder)
    private static RoadGraph reverse(RoadGraph g) {
        GraphBuilder b = new GraphBuilder();
        for (int node = 0; node < g.nodeCount(); node++) {
            b.addNode(node, g.latitude(node), g.longitude(node));
        }
        for (int node = 0; node < g.nodeCount(); node++) {
            for (int e = g.firstEdge(node); e < g.edgeEnd(node); e++) {
                b.addRoad(g.target(e), node, g.roadClass(e), true, false);
            }
        }
        return b.build();
    }

    // a new graph of one component; roads leaving it are dropped
    private static RoadGraph keep(RoadGraph g, int[] component, int keep) {
        GraphBuilder b = new GraphBuilder();
        for (int node = 0; node < g.nodeCount(); node++) {
            if (component[node] == keep) {
                b.addNode(node, g.latitude(node), g.longitude(node));
            }
        }
        for (int node = 0; node < g.nodeCount(); node++) {
            if (component[node] != keep) {
                continue;
            }
            for (int e = g.firstEdge(node); e < g.edgeEnd(node); e++) {
                if (component[g.target(e)] == keep) {
                    b.addRoad(node, g.target(e), g.roadClass(e), true, false);
                }
            }
        }
        return b.build();
    }
}
