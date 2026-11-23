package com.medidispatch.service.persistence;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Points a test that starts the WHOLE application at the {@code app} database of the shared test container
 * ({@link TestDatabase}): add {@code @Import(TestDatabaseConfig.class)} to every such test. (Adapter tests use the
 * {@code test} database instead; see JpaAssignmentRepositoryTest.)
 *
 * <p>The container's port is random, so the address can only be known at runtime. A {@link DynamicPropertyRegistrar}
 * bean (Spring 6.2+) supplies such values; they override {@code spring.datasource.*} from application.properties.
 * The values are suppliers, read when Spring creates the DataSource.
 *
 * <p>Why not Boot's {@code @ServiceConnection}: it would start a second container. This way the plain JDBC tests and
 * the Spring tests share one. Spring's Flyway runs again at context start, finds V1 already applied and does nothing.
 *
 * <p>{@code @TestConfiguration}: beans for tests only. {@code proxyBeanMethods = false}: no proxy around this class
 * (only needed when one {@code @Bean} method calls another).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabaseConfig {

    /**
     * @return the registrar that sets the DataSource URL, user and password to the {@code app} database's, and
     *         turns road routing off (straight lines)
     */
    @Bean
    DynamicPropertyRegistrar testDatabaseProperties() {
        return registry -> {
            registry.add("spring.datasource.url", () -> TestDatabase.appUrlStarted());
            registry.add("spring.datasource.username", () -> TestDatabase.username());
            registry.add("spring.datasource.password", () -> TestDatabase.password());
            // routing off: tests run inside dispatch-service/, and one folder up is the real Bangalore graph, while
            // the test patients live elsewhere; straight lines keep every test's distances and ETAs as written
            registry.add("medidispatch.routing.graph-file", () -> "");
        };
    }
}
