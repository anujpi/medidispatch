package com.medidispatch.routing;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-off tool: Bangalore's roads as downloaded from Overpass (JSON) -> the largest strongly connected part -> a graph
 * file the rest of the project loads. Prints how much was kept: around 90-98% of the nodes is normal for a city; much
 * less would point to a problem with the data.
 * <p>
 * Arguments (both optional): the JSON file (default {@code data/bangalore-roads.json}) and the graph file to write
 * (default {@code data/bangalore.graph}). Run from the project's root folder.
 */
public final class ImportBangalore {

    private ImportBangalore() {}

    /**
     * @param args optional: the JSON file, then the graph file
     * @throws Exception if reading or writing fails
     */
    public static void main(String[] args) throws Exception {
        Path in = Path.of(args.length > 0 ? args[0] : "data/bangalore-roads.json");
        Path out = Path.of(args.length > 1 ? args[1] : "data/bangalore.graph");
        long started = System.currentTimeMillis();
        RoadGraph raw;
        try (InputStream json = Files.newInputStream(in)) {
            raw = OverpassImport.read(json);
        }
        RoadGraph clean = GraphCleanup.largestStronglyConnected(raw);
        try (OutputStream file = Files.newOutputStream(out)) {
            GraphFile.write(clean, file);
        }
        System.out.printf("read %d nodes and %d edges; kept %d nodes and %d edges (%.1f%%) in %s, %d ms%n",
                raw.nodeCount(), raw.edgeCount(), clean.nodeCount(), clean.edgeCount(),
                100.0 * clean.nodeCount() / Math.max(1, raw.nodeCount()), out, System.currentTimeMillis() - started);
    }
}
