package com.medidispatch.routing;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class GraphFileTest {

    private static RoadGraph sample() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(1, 12.970, 77.590);
        b.addNode(2, 12.971, 77.590);
        b.addNode(3, 12.971, 77.591);
        b.addRoad(1, 2, RoadClass.PRIMARY, true, true);
        b.addRoad(2, 3, RoadClass.LIVING_STREET, true, false);
        return b.build();
    }

    private static byte[] saved(RoadGraph g) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GraphFile.write(g, bytes);
        return bytes.toByteArray();
    }

    @Test
    void whatIsSavedComesBackTheSame() throws IOException {
        RoadGraph g = sample();

        RoadGraph back = GraphFile.read(new ByteArrayInputStream(saved(g)));

        assertEquals(g.nodeCount(), back.nodeCount());
        assertEquals(g.edgeCount(), back.edgeCount());
        for (int n = 0; n < g.nodeCount(); n++) {
            assertEquals(g.location(n), back.location(n));
            assertEquals(g.firstEdge(n), back.firstEdge(n));
            assertEquals(g.edgeEnd(n), back.edgeEnd(n));
        }
        for (int e = 0; e < g.edgeCount(); e++) {
            assertEquals(g.target(e), back.target(e));
            assertEquals(g.length(e), back.length(e));
            assertEquals(g.roadClass(e), back.roadClass(e));
        }
    }

    @Test
    void anEmptyGraphRoundTrips() throws IOException {
        RoadGraph back = GraphFile.read(new ByteArrayInputStream(saved(new GraphBuilder().build())));

        assertEquals(0, back.nodeCount());
        assertEquals(0, back.edgeCount());
    }

    @Test
    void aFileThatIsNotAGraphIsRefused() {
        byte[] junk = "hello, this is not a graph".getBytes();

        IOException refused = assertThrows(IOException.class, () -> GraphFile.read(new ByteArrayInputStream(junk)));
        assertTrue(refused.getMessage().contains("not a MediDispatch road graph"));
    }

    @Test
    void anotherVersionIsRefused() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(GraphFile.MAGIC);
        data.writeInt(GraphFile.VERSION + 1);

        IOException refused = assertThrows(IOException.class,
                () -> GraphFile.read(new ByteArrayInputStream(bytes.toByteArray())));
        assertTrue(refused.getMessage().contains("version"));
    }

    /** A file naming a road class this code does not have gives a clear error, not wrong roads. */
    @Test
    void anUnknownRoadClassIsRefused() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(GraphFile.MAGIC);
        data.writeInt(GraphFile.VERSION);
        data.writeInt(1);
        data.writeUTF("HOVERCRAFT_LANE");

        IOException refused = assertThrows(IOException.class,
                () -> GraphFile.read(new ByteArrayInputStream(bytes.toByteArray())));
        assertTrue(refused.getMessage().contains("HOVERCRAFT_LANE"));
    }
}
