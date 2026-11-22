package com.medidispatch.routing;

import com.medidispatch.core.domain.Location;

import java.util.Arrays;

/**
 * Snaps any point (a patient's home, a hospital, an ambulance's GPS fix) to the closest node of a road graph.
 * <p>
 * Checking every node for every question would be too slow, so the graph's bounding box is cut into a grid of cells
 * {@link #CELL_DEGREES} on each side (about 280 m in Bangalore), and each node is listed under its cell, stored as
 * CSR again (the same counting sort as {@link GraphBuilder}). A question looks in the point's own cell, then in the
 * rings of cells around it.
 * <p>
 * <b>When to stop.</b> Finding some node is not enough: a node one ring further out can still be closer (the point
 * may sit on the edge of its cell). A node in ring {@code k} is at least {@code (k - 1)} cell widths away, so once
 * that is more than the best distance so far, no further ring can win: the answer is exact, not a guess. Ties go to
 * the lower node number. Points outside the box are searched from the nearest edge cell.
 */
public final class NearestNode {

    /** The grid's cell size in degrees, about 280 m in Bangalore. */
    static final double CELL_DEGREES = 0.0025;

    private final RoadGraph g;
    private final double minLat;
    private final double minLon;
    private final int rows;
    private final int cols;
    private final int[] cellStart;
    private final int[] nodes;
    private final double cellMetres;

    /**
     * Builds the grid.
     *
     * @param g the graph to snap to
     * @throws IllegalArgumentException if the graph has no nodes
     */
    public NearestNode(RoadGraph g) {
        if (g.nodeCount() == 0) {
            throw new IllegalArgumentException("the graph has no nodes");
        }
        this.g = g;
        double loLat = Double.MAX_VALUE;
        double loLon = Double.MAX_VALUE;
        double hiLat = -Double.MAX_VALUE;
        double hiLon = -Double.MAX_VALUE;
        for (int n = 0; n < g.nodeCount(); n++) {
            loLat = Math.min(loLat, g.latitude(n));
            hiLat = Math.max(hiLat, g.latitude(n));
            loLon = Math.min(loLon, g.longitude(n));
            hiLon = Math.max(hiLon, g.longitude(n));
        }
        minLat = loLat;
        minLon = loLon;
        rows = (int) ((hiLat - loLat) / CELL_DEGREES) + 1;
        cols = (int) ((hiLon - loLon) / CELL_DEGREES) + 1;
        cellStart = new int[rows * cols + 1];
        for (int n = 0; n < g.nodeCount(); n++) {
            cellStart[cellOf(g.latitude(n), g.longitude(n)) + 1]++;
        }
        for (int c = 0; c < rows * cols; c++) {
            cellStart[c + 1] += cellStart[c];
        }
        int[] next = Arrays.copyOf(cellStart, rows * cols);
        nodes = new int[g.nodeCount()];
        for (int n = 0; n < g.nodeCount(); n++) {
            nodes[next[cellOf(g.latitude(n), g.longitude(n))]++] = n;
        }
        // the smaller side of a cell, so "at least (k - 1) cells away" never overstates the distance
        Location corner = new Location(minLat, minLon);
        cellMetres = Math.min(corner.distanceKmTo(new Location(minLat + CELL_DEGREES, minLon)),
                corner.distanceKmTo(new Location(minLat, minLon + CELL_DEGREES))) * 1000;
    }

    /**
     * @param where any point
     * @return the number of the node closest to it (lower number on a tie)
     */
    public int nearest(Location where) {
        int row = clamp((int) Math.floor((where.latitude() - minLat) / CELL_DEGREES), rows);
        int col = clamp((int) Math.floor((where.longitude() - minLon) / CELL_DEGREES), cols);
        int best = -1;
        double bestMetres = Double.MAX_VALUE;
        for (int ring = 0; ring <= Math.max(rows, cols); ring++) {
            if (best != -1 && (ring - 1) * cellMetres > bestMetres) {
                break;                                        // nothing this far out can be closer
            }
            for (int r = row - ring; r <= row + ring; r++) {
                for (int c = col - ring; c <= col + ring; c++) {
                    boolean onRing = Math.abs(r - row) == ring || Math.abs(c - col) == ring;
                    if (!onRing || r < 0 || c < 0 || r >= rows || c >= cols) {
                        continue;
                    }
                    int cell = r * cols + c;
                    for (int i = cellStart[cell]; i < cellStart[cell + 1]; i++) {
                        double metres = where.distanceKmTo(g.location(nodes[i])) * 1000;
                        if (metres < bestMetres || (metres == bestMetres && nodes[i] < best)) {
                            best = nodes[i];
                            bestMetres = metres;
                        }
                    }
                }
            }
        }
        return best;
    }

    private int cellOf(double lat, double lon) {
        int r = clamp((int) ((lat - minLat) / CELL_DEGREES), rows);
        int c = clamp((int) ((lon - minLon) / CELL_DEGREES), cols);
        return r * cols + c;
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }
}
