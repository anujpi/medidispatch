package com.medidispatch.service.api;

import com.jayway.jsonpath.JsonPath;
import com.medidispatch.service.DispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
}
