package com.medidispatch.service.api;

import com.jayway.jsonpath.JsonPath;
import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.Location;
import com.medidispatch.service.DispatchService;
import com.medidispatch.service.persistence.TestDatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    /** The clinic every test unit belongs to (a unit needs an owner). Far out at sea, so no test ever picks it. */
    private static final String BASE = "api-base";

    /**
     * Makes sure the shared base exists. Not once per class: other test classes empty the same database between
     * this class's tests, and a clinic is never a hospital destination, so it never changes a test's outcome.
     */
    @BeforeEach
    void baseClinic() {
        if (service.findFacility(BASE).isEmpty()) {
            service.registerFacility(Facility.clinic(BASE, "Test Base", new Location(0, 0)).withPhone("+91 80 1111 2222"));
        }
    }

    // Text blocks (""" ... """) are multi-line strings; formatted(...) fills in each %s / %d in order.
    private static String patient(String id, double latitude, double longitude) {
        return """
                {"id": "%s", "home": {"latitude": %s, "longitude": %s}, "name": "Patient %s", "age": 68,
                 "sex": "FEMALE"}
                """.formatted(id, latitude, longitude, id);
    }

    private static String unit(String id, String type, double latitude, double longitude) {
        return """
                {"id": "%s", "type": "%s", "position": {"latitude": %s, "longitude": %s}, "baseId": "%s",
                 "driver": {"name": "Ravi Kumar", "phone": "+91 98450 12345"}}
                """.formatted(id, type, latitude, longitude, BASE);
    }

    private static String hospital(String id, double latitude, double longitude, String capabilities) {
        return """
                {"id": "%s", "name": "Hospital %s", "kind": "HOSPITAL",
                 "position": {"latitude": %s, "longitude": %s}, "capabilities": %s, "phone": "+91 80 2345 6789"}
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
                                 "baseId": "api-nowhere", "driver": {"name": "Ravi Kumar", "phone": "+91 98450 12345"}}
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
                                 "position": {"latitude": 22.5750, "longitude": 88.3650}, "baseId": "kol-h1", "driver": {"name": "Ravi Kumar", "phone": "+91 98450 12345"}}
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

    // ---- the hospital's own views (H3c) ----

    /**
     * In Pune (far from every other test): the hospital's fleet, then the pre-alert while its ambulance brings a
     * patient in (band, needs, ETA, newest vitals), and an empty pre-alert after the handover. Unknown ids are 404.
     */
    @Test
    void aHospitalSeesItsFleetAndThePatientsComingOverHttp() throws Exception {
        http.perform(post("/api/facilities").contentType(MediaType.APPLICATION_JSON)
                        .content(hospital("pune-h1", 18.5300, 73.8600, "[\"CARDIAC\"]")))
                .andExpect(status().isCreated());
        http.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON)
                        .content(patient("pune-p1", 18.5204, 73.8567)))
                .andExpect(status().isCreated());
        http.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "pune-u1", "type": "AMBULANCE_ALS",
                                 "position": {"latitude": 18.5210, "longitude": 73.8570}, "baseId": "pune-h1", "driver": {"name": "Ravi Kumar", "phone": "+91 98450 12345"}}
                                """))
                .andExpect(status().isCreated());

        http.perform(get("/api/facilities/pune-h1/units"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("pune-u1"));
        http.perform(get("/api/facilities/pune-h1/inbound"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        http.perform(post("/api/readings").contentType(MediaType.APPLICATION_JSON)
                        .content(criticalReading("pune-p1")))
                .andExpect(status().isOk());
        service.tick();
        String tripId = JsonPath.read(http.perform(get("/api/units/pune-u1/trip"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        http.perform(post("/api/trips/" + tripId + "/accept")).andExpect(status().isOk());
        http.perform(post("/api/trips/" + tripId + "/arrive")).andExpect(status().isOk());
        http.perform(post("/api/trips/" + tripId + "/transport").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"needs": ["CARDIAC"]}
                                """))
                .andExpect(status().isOk());

        http.perform(get("/api/facilities/pune-h1/inbound"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].patientId").value("pune-p1"))
                .andExpect(jsonPath("$[0].band").value("CRITICAL"))
                .andExpect(jsonPath("$[0].needs[0]").value("CARDIAC"))
                .andExpect(jsonPath("$[0].unitId").value("pune-u1"))
                .andExpect(jsonPath("$[0].etaMinutes").value(2))             // 1.05 km at 40 km/h = 1.6 min, rounded up
                .andExpect(jsonPath("$[0].latestVitals.heartRate").value(150));

        http.perform(post("/api/trips/" + tripId + "/complete")).andExpect(status().isOk());
        http.perform(get("/api/facilities/pune-h1/inbound"))
                .andExpect(jsonPath("$.length()").value(0));

        http.perform(get("/api/facilities/pune-nowhere/units")).andExpect(status().isNotFound());
        http.perform(get("/api/facilities/pune-nowhere/inbound")).andExpect(status().isNotFound());
    }

    // ---- people (B1) ----

    /** The full patient record over HTTP, emergency contact included; an unknown patient is 404. */
    @Test
    void aPatientsRecordOverHttp() throws Exception {
        http.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "rec-p1", "home": {"latitude": 12.93, "longitude": 77.62}, "name": "Asha Rao",
                                 "age": 68, "sex": "FEMALE",
                                 "emergencyContact": {"name": "Vikram Rao", "phone": "+91 98450 12345"}}
                                """))
                .andExpect(status().isCreated());

        http.perform(get("/api/patients/rec-p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Asha Rao"))
                .andExpect(jsonPath("$.age").value(68))
                .andExpect(jsonPath("$.sex").value("FEMALE"))
                .andExpect(jsonPath("$.emergencyContact.phone").value("+91 98450 12345"));
        http.perform(get("/api/patients/rec-nobody")).andExpect(status().isNotFound());
    }

    /** What the API requires: a patient's name, age and sex; a unit's base and driver; a facility's phone. */
    @Test
    void missingPeopleDataIsRejected() throws Exception {
        http.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "req-p1", "home": {"latitude": 12.93, "longitude": 77.62}, "age": 68, "sex": "FEMALE"}
                                """))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "req-p2", "home": {"latitude": 12.93, "longitude": 77.62}, "name": "Asha",
                                 "age": 150, "sex": "FEMALE"}
                                """))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "req-u1", "type": "NURSE", "position": {"latitude": 12.93, "longitude": 77.62},
                                 "baseId": "api-base"}
                                """))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "req-u2", "type": "NURSE", "position": {"latitude": 12.93, "longitude": 77.62},
                                 "driver": {"name": "Ravi Kumar", "phone": "+91 98450 12345"}}
                                """))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "req-u3", "type": "NURSE", "position": {"latitude": 12.93, "longitude": 77.62},
                                 "baseId": "api-base", "driver": {"name": "Ravi Kumar", "phone": "not a phone"}}
                                """))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/facilities").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "req-h1", "name": "No Phone", "kind": "HOSPITAL",
                                 "position": {"latitude": 12.93, "longitude": 77.62}}
                                """))
                .andExpect(status().isBadRequest());

        http.perform(get("/api/units/req-u1")).andExpect(status().isNotFound());
        http.perform(get("/api/facilities/req-h1")).andExpect(status().isNotFound());
    }

    /** A complete unit shows its owner and driver. */
    @Test
    void aUnitShowsItsDriver() throws Exception {
        http.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON)
                        .content(unit("drv-u1", "AMBULANCE_BLS", 12.93, 77.62)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baseId").value(BASE))
                .andExpect(jsonPath("$.driver.name").value("Ravi Kumar"))
                .andExpect(jsonPath("$.driver.phone").value("+91 98450 12345"));
    }

    // ---- the live map page (M1) ----

    /** Spring Boot serves static/map.html as it is; the page listens to the live feed. */
    @Test
    void theLiveMapPageIsServed() throws Exception {
        String page = http.perform(get("/map.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(page.contains("new EventSource('/api/live')"), "the page must listen to the live feed");
        assertTrue(page.contains("tile.openstreetmap.org"), "the page must draw OpenStreetMap streets");
        assertTrue(page.contains("fetch('/api/route?'"), "trips are drawn along the roads");
        assertTrue(page.contains("fetch('/api/incidents')"), "incidents are drawn");
    }

    /** The tests run without the road graph: the route is the straight line at 40 km/h, start and end only. */
    @Test
    void aRouteOverHttpIsTheStraightLineWithoutTheRoadGraph() throws Exception {
        Location from = new Location(12.97, 77.59);
        Location to = new Location(13.07, 77.59);

        String body = http.perform(get("/api/route").param("fromLat", "12.97").param("fromLon", "77.59")
                        .param("toLat", "13.07").param("toLon", "77.59").param("emergency", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(2))
                .andExpect(jsonPath("$.points[0][0]").value(12.97))
                .andExpect(jsonPath("$.points[1][0]").value(13.07))
                .andReturn().getResponse().getContentAsString();

        double km = from.distanceKmTo(to);
        assertEquals(km * 1000, ((Number) JsonPath.read(body, "$.metres")).doubleValue(), 1e-6);
        assertEquals(km / 40 * 3600, ((Number) JsonPath.read(body, "$.seconds")).doubleValue(), 1e-6);
    }

    @Test
    void aBadRouteQuestionIs400() throws Exception {
        http.perform(get("/api/route").param("fromLat", "12.97").param("fromLon", "77.59").param("toLat", "13.07"))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/route").param("fromLat", "95").param("fromLon", "77.59")
                        .param("toLat", "13.07").param("toLon", "77.59"))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/route").param("fromLat", "abc").param("fromLon", "77.59")
                        .param("toLat", "13.07").param("toLon", "77.59"))
                .andExpect(status().isBadRequest());
    }

    /** Add, replace, list, remove (twice: safe to repeat). */
    @Test
    void incidentsCanBeAddedListedAndRemoved() throws Exception {
        String jam = """
                {"id": "api-silk-board", "position": {"latitude": 12.917, "longitude": 77.623},
                 "radiusMetres": 300, "speedFactor": 0.2}""";
        String closed = jam.replace("0.2", "0");
        http.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON).content(jam))
                .andExpect(status().isCreated());
        http.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON).content(closed))
                .andExpect(status().isCreated());

        http.perform(get("/api/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'api-silk-board')].speedFactor").value(0.0))
                .andExpect(jsonPath("$[?(@.id == 'api-silk-board')].centre.latitude").value(12.917))
                .andExpect(jsonPath("$[?(@.id == 'api-silk-board')].radiusMetres").value(300.0));

        http.perform(delete("/api/incidents/api-silk-board")).andExpect(status().isNoContent());
        http.perform(delete("/api/incidents/api-silk-board")).andExpect(status().isNoContent());
        http.perform(get("/api/incidents"))
                .andExpect(jsonPath("$[?(@.id == 'api-silk-board')]").isEmpty());
    }

    @Test
    void aBadIncidentIs400() throws Exception {
        String[] bad = {
                "{\"id\": \"x\", \"radiusMetres\": 300, \"speedFactor\": 0.2}",
                "{\"id\": \"x\", \"position\": {\"latitude\": 12.9, \"longitude\": 77.6}, \"radiusMetres\": 0, \"speedFactor\": 0.2}",
                "{\"id\": \"x\", \"position\": {\"latitude\": 12.9, \"longitude\": 77.6}, \"radiusMetres\": 300, \"speedFactor\": 1}",
                "{\"id\": \" \", \"position\": {\"latitude\": 12.9, \"longitude\": 77.6}, \"radiusMetres\": 300, \"speedFactor\": 0.2}"
        };
        for (String body : bad) {
            http.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }
}
