package com.medidispatch.sim;

import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class BangaloreCityTest {

    private static final LiveConfig CONFIG = LiveConfig.bangalore();

    @Test
    void everyFacilityAndPatientIsInsideTheCity() {
        BangaloreCity.Plan plan = BangaloreCity.plan(CONFIG);

        for (Facility f : plan.facilities()) {
            assertTrue(BangaloreCity.inside(f.location()), f.id());
        }
        for (Patient p : plan.patients()) {
            assertTrue(BangaloreCity.inside(p.location()), p.id());
        }
        assertEquals(CONFIG.patientCount(), plan.patients().size());
    }

    /** Ambulances belong to hospitals, nurses and doctors to clinics; each unit starts at its base, with a driver. */
    @Test
    void everyUnitStartsAtItsOwnBaseWithADriver() {
        BangaloreCity.Plan plan = BangaloreCity.plan(CONFIG);
        Map<String, Facility> byId = new HashMap<>();
        for (Facility f : plan.facilities()) {
            byId.put(f.id(), f);
        }

        for (Resource u : plan.units()) {
            Facility base = byId.get(u.baseId().orElseThrow());
            assertNotNull(base, u.id());
            assertEquals(base.location(), u.location(), u.id());
            assertTrue(u.driver().isPresent(), u.id());
            boolean ambulance = u.type() == ResourceType.AMBULANCE_ALS || u.type() == ResourceType.AMBULANCE_BLS;
            assertEquals(ambulance, base.isHospital(), u.id());
        }
        assertEquals(CONFIG.alsCount() + CONFIG.blsCount() + CONFIG.nurseCount() + CONFIG.doctorCount(),
                plan.units().size());
    }

    /** What the API requires: every patient has details, every facility a phone. */
    @Test
    void everythingTheApiRequiresIsThere() {
        BangaloreCity.Plan plan = BangaloreCity.plan(CONFIG);

        for (Patient p : plan.patients()) {
            assertTrue(p.details().isPresent(), p.id());
        }
        for (Facility f : plan.facilities()) {
            assertNotNull(f.phone(), f.id());
        }
    }

    @Test
    void theSameSeedGivesTheSameCity() {
        BangaloreCity.Plan a = BangaloreCity.plan(CONFIG);
        BangaloreCity.Plan b = BangaloreCity.plan(CONFIG);

        for (int i = 0; i < a.patients().size(); i++) {
            assertEquals(a.patients().get(i).location(), b.patients().get(i).location());
            assertEquals(a.patients().get(i).details(), b.patients().get(i).details());
        }
        for (int i = 0; i < a.units().size(); i++) {
            assertEquals(a.units().get(i).driver(), b.units().get(i).driver());
        }
    }
}
