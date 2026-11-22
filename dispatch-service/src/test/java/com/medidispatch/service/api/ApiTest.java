package com.medidispatch.service.api;

import com.jayway.jsonpath.JsonPath;
import com.medidispatch.service.DispatchService;
import com.medidispatch.service.persistence.TestDatabaseConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the REST API the way a phone would use it: real HTTP requests (methods, paths, JSON bodies) checked for
 * their status codes and JSON answers.
 *
 * <p>{@code @SpringBootTest} starts the whole application; {@code @AutoConfigureMockMvc} adds {@link MockMvc}, a
 * fake HTTP client that hands requests straight to Spring, so no real network port is opened.
 *
 * <p>All tests share ONE running application (Spring keeps the context between tests), and the scheduled tick
 * runs in the background. So every test uses its own ids, and the only test that dispatches works in another
 * city (Mumbai, over 1,000 km away), so no test can steal another test's unit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseConfig.class)
class ApiTest {

    @Autowired
    private MockMvc http;

    @Autowired
    private DispatchService service;

    // Text blocks (""" ... """) are multi-line strings; formatted(...) fills in each %s / %d in order.
    private static String patient(String id, double latitude, double longitude) {
        return """
                {"id": "%s", "home": {"latitude": %s, "longitude": %s}}
                """.formatted(id, latitude, longitude);
    }

    private static String unit(String id, String type, double latitude, double longitude) {
        return """
                {"id": "%s", "type": "%s", "position": {"latitude": %s, "longitude": %s}}
                """.formatted(id, type, latitude, longitude);
    }

    private static String hospital(String id, double latitude, double longitude, String capabilities) {
        return """
                {"id": "%s", "name": "Hospital %s", "kind": "HOSPITAL",
                 "position": {"latitude": %s, "longitude": %s}, "capabilities": %s}
                """.formatted(id, id, latitude, longitude, capabilities);
    }

    private static String reading(String patientId, Instant at, int heartRate, int spo2, String consciousness) {
        return """
                {"patientId": "%s", "timeStamp": "%s", "heartRate": %d, "spo2": %d, "systolicBp": 120,
                 "diastolicBp": 80, "respRate": 14, "temperature": 36.8, "consciousness": "%s"}
                """.formatted(patientId, at, heartRate, spo2, consciousness);
    }

    private static String criticalReading(String patientId) {
        return """
                {"patientId": "%s", "timeStamp": "%s", "heartRate": 150, "spo2": 80, "systolicBp": 80,
                 "diastolicBp": 50, "respRate": 35, "temperature": 39.5, "consciousness": "UNRESPONSIVE"}
                """.formatted(patientId, Instant.now());
    }

    /**
     * The whole story over HTTP, including the weak-network cases: a step out of order is a 409, and a repeated
     * step is a 200 that changes nothing.
     */
    @Test
    void aPatientIsServedOverHttp() throws Exception {
        http.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON)
                        .content(patient("mumbai-p1", 19.0760, 72.8777)))
                .andExpect(status().isCreated());
        http.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON)
                        .content(unit("mumbai-u1", "AMBULANCE_ALS", 19.0800, 72.8800)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        String readingAnswer = http.perform(post("/api/readings").contentType(MediaType.APPLICATION_JSON)
                        .content(criticalReading("mumbai-p1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ACCEPTED"))
                .andExpect(jsonPath("$.openCase.band").value("CRITICAL"))
                .andReturn().getResponse().getContentAsString();
        // JsonPath.read pulls one value out of a JSON string, like jsonPath(...) above but for use in the test.
        String caseId = JsonPath.read(readingAnswer, "$.openCase.id");

        // Run the dispatch loop now instead of waiting up to a second for the scheduled one.
        service.tick();

        String tripAnswer = http.perform(get("/api/units/mumbai-u1/trip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.caseId").value(caseId))
                .andReturn().getResponse().getContentAsString();
        String tripId = JsonPath.read(tripAnswer, "$.id");

        http.perform(post("/api/trips/" + tripId + "/arrive"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        http.perform(post("/api/trips/" + tripId + "/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EN_ROUTE"));
        http.perform(post("/api/trips/" + tripId + "/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EN_ROUTE"));

        http.perform(put("/api/units/mumbai-u1/position").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"latitude": 19.0770, "longitude": 72.8780}
                                """))
                .andExpect(status().isNoContent());

        http.perform(post("/api/trips/" + tripId + "/arrive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"));
        http.perform(post("/api/trips/" + tripId + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
        http.perform(post("/api/trips/" + tripId + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        http.perform(get("/api/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        http.perform(get("/api/patients/mumbai-p1/case"))
                .andExpect(status().isNotFound());
        http.perform(get("/api/units/mumbai-u1/trip"))
                .andExpect(status().isNoContent());
    }

    /** A device resending the same reading gets 200 DUPLICATE, not an error, so it stops resending. */
    @Test
    void aReadingSentTwiceIsADuplicateNotAnError() throws Exception {
        http.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON)
                        .content(patient("dup-p1", 28.6139, 77.2090)))
                .andExpect(status().isCreated());
        String healthy = reading("dup-p1", Instant.now(), 72, 98, "ALERT");

        http.perform(post("/api/readings").contentType(MediaType.APPLICATION_JSON).content(healthy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ACCEPTED"));
        http.perform(post("/api/readings").contentType(MediaType.APPLICATION_JSON).content(healthy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
    }

    /** Registering an id that already exists is a 409 with a ProblemDetail body. */
    @Test
    void registeringTheSameIdTwiceIsAConflict() throws Exception {
        String body = patient("twice-p1", 28.6139, 77.2090);
        http.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        http.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Patient twice-p1 already exists"));
    }

    /** "No job right now" is 204, not an error. */
    @Test
    void aNewUnitHasNoTrip() throws Exception {
        http.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON)
                        .content(unit("idle-u1", "NURSE", 28.6200, 77.2150)))
                .andExpect(status().isCreated());
        http.perform(get("/api/units/idle-u1/trip"))
                .andExpect(status().isNoContent());
        http.perform(get("/api/units/idle-u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("NURSE"))
                .andExpect(jsonPath("$.latitude").value(28.62));
    }

    /** Every unknown id is a 404, on reads and on actions. */
    @Test
    void unknownThingsAreNotFound() throws Exception {
        http.perform(get("/api/trips/nope")).andExpect(status().isNotFound());
        http.perform(post("/api/trips/nope/accept")).andExpect(status().isNotFound());
        http.perform(post("/api/trips/nope/arrive")).andExpect(status().isNotFound());
        http.perform(post("/api/trips/nope/complete")).andExpect(status().isNotFound());
        http.perform(get("/api/units/nope")).andExpect(status().isNotFound());
        http.perform(get("/api/units/nope/trip")).andExpect(status().isNotFound());
        http.perform(post("/api/units/nope/back-in-service")).andExpect(status().isNotFound());
        http.perform(put("/api/units/nope/position").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"latitude": 28.6, "longitude": 77.2}
                                """))
                .andExpect(status().isNotFound());
        http.perform(get("/api/cases/nope")).andExpect(status().isNotFound());
        http.perform(get("/api/patients/nope/case")).andExpect(status().isNotFound());
    }

    /** Bodies that are broken or hold impossible values are a 400, and nothing is created. */
    @Test
    void badRequestsAreRejected() throws Exception {
        http.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "bad-p1"}
                                """))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "bad-p2", "home": {"latitude": 28.6}}
                                """))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON)
                        .content(patient("bad-p3", 200, 77.2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        http.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON)
                        .content(patient(" ", 28.6, 77.2)))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON)
                        .content(unit("bad-u1", "TRUCK", 28.6, 77.2)))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/readings").contentType(MediaType.APPLICATION_JSON)
                        .content(reading("bad-p4", Instant.now(), 999, 98, "ALERT")))
                .andExpect(status().isBadRequest());

        http.perform(get("/api/units/bad-u1")).andExpect(status().isNotFound());
    }

    // ---- hospitals and clinics (H3b) ----

    /** Register, list, find, switch diversion with PUT (twice is harmless), and the error codes around them. */
    @Test
    void facilitiesOverHttp() throws Exception {
        http.perform(post("/api/facilities").contentType(MediaType.APPLICATION_JSON)
                        .content(hospital("api-h1", 13.0827, 80.2707, "[\"CARDIAC\"]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("HOSPITAL"))
                .andExpect(jsonPath("$.capabilities[0]").value("CARDIAC"))
                .andExpect(jsonPath("$.accepting").value(true));
        http.perform(post("/api/facilities").contentType(MediaType.APPLICATION_JSON)
                        .content(hospital("api-h1", 13.0827, 80.2707, "[]")))
                .andExpect(status().isConflict());

        http.perform(get("/api/facilities/api-h1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Hospital api-h1"));
        http.perform(get("/api/facilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'api-h1')]").exists());
        http.perform(get("/api/facilities/api-nowhere")).andExpect(status().isNotFound());

        for (int i = 0; i < 2; i++) {
            http.perform(put("/api/facilities/api-h1/accepting").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"accepting": false}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accepting").value(false));
        }
        http.perform(put("/api/facilities/api-nowhere/accepting").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accepting": true}
                                """))
                .andExpect(status().isNotFound());
    }

    /** The domain's rules surface as 400s: a clinic that accepts, an unknown capability or kind, an unknown base. */
    @Test
    void badFacilityRequestsAreRejected() throws Exception {
        http.perform(post("/api/facilities").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "api-c1", "name": "Lane Clinic", "kind": "CLINIC",
                                 "position": {"latitude": 13.08, "longitude": 80.27}, "accepting": true}
                                """))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/facilities").contentType(MediaType.APPLICATION_JSON)
                        .content(hospital("api-h2", 13.08, 80.27, "[\"DENTIST\"]")))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/facilities").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "api-h3", "name": "X", "kind": "SPA", "position": {"latitude": 13.08, "longitude": 80.27}}
                                """))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "api-u-nobase", "type": "NURSE", "position": {"latitude": 13.08, "longitude": 80.27},
                                 "baseId": "api-nowhere"}
                                """))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/units/api-u-nobase")).andExpect(status().isNotFound());
    }

    /**
     * Scene to hospital over HTTP, in Kolkata (far from every other test): the ambulance belongs to the hospital,
     * the crew asks for transport with STROKE, the trip answers with the hospital and needs, a repeat is a 200, and
     * complete closes the case. A trip that has not arrived is a 409.
     */
    @Test
    void aPatientIsTakenToHospitalOverHttp() throws Exception {
        http.perform(post("/api/facilities").contentType(MediaType.APPLICATION_JSON)
                        .content(hospital("kol-h1", 22.5800, 88.3700, "[\"STROKE\"]")))
                .andExpect(status().isCreated());
        http.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON)
                        .content(patient("kol-p1", 22.5726, 88.3639)))
                .andExpect(status().isCreated());
        http.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "kol-u1", "type": "AMBULANCE_ALS",
                                 "position": {"latitude": 22.5750, "longitude": 88.3650}, "baseId": "kol-h1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baseId").value("kol-h1"));

        String readingAnswer = http.perform(post("/api/readings").contentType(MediaType.APPLICATION_JSON)
                        .content(criticalReading("kol-p1")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String caseId = JsonPath.read(readingAnswer, "$.openCase.id");
        service.tick();
        String tripId = JsonPath.read(http.perform(get("/api/units/kol-u1/trip"))
                .andReturn().getResponse().getContentAsString(), "$.id");

        http.perform(post("/api/trips/" + tripId + "/transport")).andExpect(status().isConflict());

        http.perform(post("/api/trips/" + tripId + "/accept")).andExpect(status().isOk());
        http.perform(post("/api/trips/" + tripId + "/arrive")).andExpect(status().isOk());
        for (int i = 0; i < 2; i++) {
            http.perform(post("/api/trips/" + tripId + "/transport").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"needs": ["STROKE"]}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("TRANSPORTING"))
                    .andExpect(jsonPath("$.hospitalId").value("kol-h1"))
                    .andExpect(jsonPath("$.needs[0]").value("STROKE"));
        }
        http.perform(get("/api/cases/" + caseId))
                .andExpect(jsonPath("$.status").value("TRANSPORTING"));

        http.perform(post("/api/trips/" + tripId + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
        http.perform(get("/api/cases/" + caseId))
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }
}
