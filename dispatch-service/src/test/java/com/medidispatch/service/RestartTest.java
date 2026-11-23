package com.medidispatch.service;

import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.Consciousness;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.VitalsReading;
import com.medidispatch.service.persistence.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The point of the whole database work: a trip in progress survives a restart.
 *
 * <p>Deliberately NOT a Spring test: Spring's test framework caches and reuses contexts, the opposite of a restart.
 * {@link SpringApplicationBuilder} starts the real application by hand; closing the first {@code try} block shuts it
 * down completely, and the second block starts a brand new one, which knows nothing except what it reads from the
 * {@code app} database. Finishing the trip there proves the restored state is fully usable, not just readable.
 */
class RestartTest {

    // Two points in central Delhi, under 1 km apart.
    private static final Location HOME = new Location(28.6139, 77.2090);
    private static final Location NEARBY = new Location(28.6200, 77.2150);

    @Test
    void aTripInProgressSurvivesARestart() {
        TestDatabase.resetApp();
        String caseId;
        String tripId;

        try (ConfigurableApplicationContext first = start()) {
            DispatchService service = first.getBean(DispatchService.class);
            service.registerPatient(new Patient("p1", HOME));
            service.registerUnit("u1", ResourceType.AMBULANCE_ALS, NEARBY, null, null);
            caseId = service.submitReading(critical("p1")).openCase().id();
            service.tick();
            tripId = service.activeTripOf("u1").orElseThrow().id();
            service.acceptTrip(tripId);
        }

        try (ConfigurableApplicationContext second = start()) {
            DispatchService service = second.getBean(DispatchService.class);
            assertEquals(CaseStatus.RESPONDING, service.findCase(caseId).orElseThrow().status());
            assertEquals(AssignmentStatus.EN_ROUTE, service.findTrip(tripId).orElseThrow().status());
            assertEquals(ResourceStatus.ASSIGNED, service.findUnit("u1").orElseThrow().status());

            assertEquals(AssignmentStatus.ARRIVED, service.arrive(tripId).status());
            service.completeTrip(tripId);
            assertEquals(CaseStatus.CLOSED, service.findCase(caseId).orElseThrow().status());
            assertEquals(ResourceStatus.AVAILABLE, service.findUnit("u1").orElseThrow().status());
        }
    }

    /**
     * Starts the whole application without a web server ({@code WebApplicationType.NONE}: only the engine is needed),
     * on the {@code app} database.
     *
     * <p>The database settings go in as command-line arguments ({@code --key=value}), which outrank
     * application.properties. {@code SpringApplicationBuilder.properties(...)} would NOT work: it sets DEFAULT
     * properties, the lowest priority of all, so application.properties would win.
     */
    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(DispatchServiceApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + TestDatabase.appUrlStarted(),
                        "--spring.datasource.username=" + TestDatabase.username(),
                        "--spring.datasource.password=" + TestDatabase.password(),
                        "--medidispatch.routing.graph-file=");     // straight lines, as in the other tests
    }

    // Every vital far out of range: the scorer rates this CRITICAL.
    private static VitalsReading critical(String patientId) {
        return new VitalsReading(patientId, Instant.now(), 150, 80, 80, 50, 35, 39.5, Consciousness.UNRESPONSIVE);
    }
}
