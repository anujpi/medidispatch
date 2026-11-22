package com.medidispatch.routing;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Saves a {@link RoadGraph} in a small binary file and loads it back, so a city loads in about a second instead of
 * re-reading 100 MB of JSON.
 * <p>
 * Layout ({@code DataOutputStream}: fixed big-endian byte order, readable on any machine):
 * <pre>
 * int MAGIC ("MDRG"), int VERSION
 * int classCount, then each RoadClass name (UTF)      the order the classes had when saved
 * int nodeCount, int edgeCount
 * nodeCount x (double latitude, double longitude)
 * (nodeCount + 1) x int firstEdge
 * edgeCount x (int target, float length, byte class position)
 * </pre>
 * Edges store a class as its position in the SAVED list; loading maps each saved name to today's enum, so reordering
 * {@link RoadClass} can never relabel roads, and a removed class is a clear error.
 */
public final class GraphFile {

    static final int MAGIC = 0x4D445247;                      // "MDRG" in ASCII
    static final int VERSION = 1;

    private GraphFile() {}

    /**
     * @param g   the graph
     * @param out where to write; flushed, not closed
     * @throws IOException if writing fails
     */
    public static void write(RoadGraph g, OutputStream out) throws IOException {
        DataOutputStream data = new DataOutputStream(new BufferedOutputStream(out));
        data.writeInt(MAGIC);
        data.writeInt(VERSION);
        RoadClass[] classes = RoadClass.values();
        data.writeInt(classes.length);
        for (RoadClass c : classes) {
            data.writeUTF(c.name());
        }
        data.writeInt(g.nodeCount());
        data.writeInt(g.edgeCount());
        for (int n = 0; n < g.nodeCount(); n++) {
            data.writeDouble(g.latitude(n));
            data.writeDouble(g.longitude(n));
        }
        for (int n = 0; n <= g.nodeCount(); n++) {
            data.writeInt(n < g.nodeCount() ? g.firstEdge(n) : g.edgeCount());
        }
        for (int e = 0; e < g.edgeCount(); e++) {
            data.writeInt(g.target(e));
            data.writeFloat((float) g.length(e));
            data.writeByte(g.roadClass(e).ordinal());
        }
        data.flush();
    }

    /**
     * @param in where to read from; not closed
     * @return the graph
     * @throws IOException if it is not a road graph file, has another version or an unknown road class, or reading
     *                     fails
     */
    public static RoadGraph read(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(new BufferedInputStream(in));
        if (data.readInt() != MAGIC) {
            throw new IOException("not a MediDispatch road graph file");
        }
        int version = data.readInt();
        if (version != VERSION) {
            throw new IOException("unsupported road graph file version " + version);
        }
        byte[] classMap = new byte[data.readInt()];           // saved position -> today's position
        for (int i = 0; i < classMap.length; i++) {
            String name = data.readUTF();
            try {
                classMap[i] = (byte) RoadClass.valueOf(name).ordinal();
            } catch (IllegalArgumentException e) {
                throw new IOException("unknown road class in file: " + name);
            }
        }
        int nodes = data.readInt();
        int edges = data.readInt();
        double[] lat = new double[nodes];
        double[] lon = new double[nodes];
        for (int n = 0; n < nodes; n++) {
            lat[n] = data.readDouble();
            lon[n] = data.readDouble();
        }
        int[] firstEdge = new int[nodes + 1];
        for (int n = 0; n <= nodes; n++) {
            firstEdge[n] = data.readInt();
        }
        int[] target = new int[edges];
        float[] length = new float[edges];
        byte[] cls = new byte[edges];
        for (int e = 0; e < edges; e++) {
            target[e] = data.readInt();
            length[e] = data.readFloat();
            cls[e] = classMap[data.readUnsignedByte()];
        }
        return new RoadGraph(lat, lon, firstEdge, target, length, cls);
    }
}
