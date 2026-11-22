package com.medidispatch.service;

import com.medidispatch.core.engine.DispatchCoordinator;
import com.medidispatch.core.engine.VitalsProcessor;
import com.medidispatch.core.port.ResourceClaimer;
import com.medidispatch.service.persistence.TestDatabaseConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that Spring can build the whole engine from {@link EngineConfig}: every part, with every dependency
 * filled in, and each part built only once.
 *
 * <p>{@code @SpringBootTest} starts the full application (as {@code main} would) for the test, and
 * {@code @Autowired} asks Spring to fill in these fields from the beans it built.
 */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class EngineConfigTest {

    @Autowired
    private VitalsProcessor processor;

    @Autowired
    private DispatchCoordinator coordinator;

    @Autowired
    private ResourceClaimer claimer;

    // Asked for a second time on purpose, to check that Spring hands out the same object again.
    @Autowired
    private ResourceClaimer claimerAgain;

    /** The two parts at the top of the wiring exist, so everything they depend on was built too. */
    @Test
    void theEngineIsBuilt() {
        assertNotNull(processor);
        assertNotNull(coordinator);
    }

    /**
     * Beans are singletons: if the dispatcher and the assignment manager each had their own claimer, two
     * dispatches could claim the same unit.
     */
    @Test
    void everyPartIsBuiltOnceAndShared() {
        assertSame(claimer, claimerAgain);
    }
}
