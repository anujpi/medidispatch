package com.medidispatch.service;

import com.medidispatch.core.engine.StraightLineTravelTime;
import com.medidispatch.core.port.TravelTime;
import com.medidispatch.routing.GraphBuilder;
import com.medidispatch.routing.GraphFile;
import com.medidispatch.routing.RoadClass;
import com.medidispatch.routing.RoadTravelTime;
import com.medidispatch.routing.TrafficModel;
import com.medidispatch.routing.TrafficProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which {@link TravelTime} the service runs on: real roads when the graph file is there, straight lines when not.
 * Plain JUnit, no Spring: the bean method is called directly. {@code @TempDir} gives each test an empty folder that
 * JUnit deletes afterwards.
 */
class RoutingConfigTest {

    private final EngineConfig config = new EngineConfig();
    private final TrafficModel traffic = new TrafficModel(TrafficProfile.bangaloreWeekday());

    // a tiny two-node road graph, saved where the test says
    private static Path writeGraph(Path file) throws IOException {
        GraphBuilder b = new GraphBuilder();
        b.addNode(1, 12.970, 77.590);
        b.addNode(2, 12.975, 77.590);
        b.addRoad(1, 2, RoadClass.PRIMARY, true, true);
        try (OutputStream out = Files.newOutputStream(file)) {
            GraphFile.write(b.build(), out);
        }
        return file;
    }

    @Test
    void withoutAGraphFileRoutingUsesStraightLines(@TempDir Path dir) throws IOException {
        TravelTime travel = config.travelTime(dir.resolve("missing.graph").toString(), traffic, Clock.systemUTC());

        assertInstanceOf(StraightLineTravelTime.class, travel);
    }

    @Test
    void withAGraphFileRoutingUsesTheRoads(@TempDir Path dir) throws IOException {
        Path file = writeGraph(dir.resolve("city.graph"));

        TravelTime travel = config.travelTime(file.toString(), traffic, Clock.systemUTC());

        RoadTravelTime roads = assertInstanceOf(RoadTravelTime.class, travel);
        assertEquals(2, roads.graph().nodeCount());
    }

    /** A blank setting means "off" (the tests use it to stay on straight lines). */
    @Test
    void aBlankSettingTurnsRoutingOff() throws IOException {
        assertInstanceOf(StraightLineTravelTime.class, config.travelTime("", traffic, Clock.systemUTC()));
        assertInstanceOf(StraightLineTravelTime.class, config.travelTime(null, traffic, Clock.systemUTC()));
    }

    /** IntelliJ may start the service inside dispatch-service/: a relative path is also tried one folder up. */
    @Test
    void aRelativePathIsAlsoTriedOneFolderUp() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        Path folderAbove = root.getParent();
        assertNotNull(folderAbove, "the tests run inside a folder that has a parent");
        Path probe = Files.createTempFile(folderAbove, "routing-config-test", ".graph");
        try {
            Optional<Path> found = EngineConfig.findGraph(probe.getFileName().toString());

            assertTrue(found.isPresent());
            assertEquals(probe.toRealPath(), found.get().toRealPath());
        } finally {
            Files.deleteIfExists(probe);
        }
        assertEquals(Optional.empty(), EngineConfig.findGraph("no-such-folder/no-such.graph"));
    }
}
