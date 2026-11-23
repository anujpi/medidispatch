package com.medidispatch.service;

import com.medidispatch.core.engine.AssignmentManager;
import com.medidispatch.core.engine.CaseManager;
import com.medidispatch.core.engine.DestinationChooser;
import com.medidispatch.core.engine.DispatchCoordinator;
import com.medidispatch.core.engine.Dispatcher;
import com.medidispatch.core.engine.Preemptor;
import com.medidispatch.core.engine.ResponseRecovery;
import com.medidispatch.core.engine.ResponseUpgrader;
import com.medidispatch.core.engine.StraightLineTravelTime;
import com.medidispatch.core.engine.SupportDispatcher;
import com.medidispatch.core.engine.TimeoutMonitor;
import com.medidispatch.core.engine.TransportCoordinator;
import com.medidispatch.core.engine.TripMonitor;
import com.medidispatch.core.engine.UnitTracker;
import com.medidispatch.core.engine.VitalsProcessor;
import com.medidispatch.core.memory.InMemoryEventPublisher;
import com.medidispatch.core.port.AssignmentRepository;
import com.medidispatch.core.port.CaseQueue;
import com.medidispatch.core.port.CaseRepository;
import com.medidispatch.core.port.EventPublisher;
import com.medidispatch.core.port.FacilityRepository;
import com.medidispatch.core.port.PatientRepository;
import com.medidispatch.core.port.ResourceClaimer;
import com.medidispatch.core.port.ResourceRepository;
import com.medidispatch.core.port.TravelTime;
import com.medidispatch.core.port.VitalsRepository;
import com.medidispatch.core.response.LadderResponseDecider;
import com.medidispatch.core.response.ResponseDecider;
import com.medidispatch.core.scoring.RiskScorer;
import com.medidispatch.core.scoring.SimpleScorer;
import com.medidispatch.service.persistence.JdbcCaseQueue;
import com.medidispatch.service.persistence.JdbcCaseRepository;
import com.medidispatch.service.persistence.JdbcFacilityRepository;
import com.medidispatch.service.persistence.JdbcPatientRepository;
import com.medidispatch.service.persistence.JdbcResourceClaimer;
import com.medidispatch.service.persistence.JdbcResourceRepository;
import com.medidispatch.service.persistence.JdbcVitalsRepository;
import com.medidispatch.service.persistence.JpaAssignmentRepository;
import com.medidispatch.routing.RoadTravelTime;
import com.medidispatch.routing.TrafficModel;
import com.medidispatch.routing.TrafficProfile;
import com.medidispatch.service.persistence.TripJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Builds the dispatch engine and hands it to Spring.
 *
 * <p>The engine ({@code dispatch-core}) is plain Java with no Spring annotations, so Spring cannot find its classes
 * by scanning. Instead each {@code @Bean} method below builds one part with {@code new}. Spring calls each method
 * once, keeps the result (a "bean"), and passes it to any other {@code @Bean} method or constructor that asks for
 * that type as a parameter. That is how the parts get wired together, and why every part exists exactly once.
 *
 * <p>This is the only class that knows which adapters are used: the port beans below are the Postgres adapters
 * (the in-memory ones in dispatch-core stay for core tests and the simulator). Swapping them was a change to those
 * beans and nothing else.
 */
@Configuration
public class EngineConfig {

    private static final Logger log = LoggerFactory.getLogger(EngineConfig.class);

    /** How long a crew has to accept a new trip before it is taken back and the unit marked OFFLINE. */
    static final Duration ACK_TIMEOUT = Duration.ofSeconds(60);
    /** How long a unit on a trip may go without sending its position before the trip is taken back. */
    static final Duration SIGNAL_TIMEOUT = Duration.ofSeconds(30);
    /** How long a unit on a trip may report the same position (not moving) before the trip is taken back. */
    static final Duration STALL_TIMEOUT = Duration.ofSeconds(120);
    /** Vitals readings older than this are ignored: they describe a patient who may have changed since. */
    static final Duration MAX_READING_AGE = Duration.ofMinutes(10);
    /** How far from the patient the engine looks for a free unit. */
    static final double SEARCH_RADIUS_KM = 20;

    /**
     * The one clock every part shares, so all timestamps and timeouts agree. Tests in dispatch-core swap in a
     * {@code MutableClock} to move time by hand.
     *
     * @return the system clock in UTC
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    // ---- Ports: storage, queue, claims and events. The Postgres adapters (package service.persistence): JDBC for
    // everything except trips, which use JPA. JdbcClient and TripJpaRepository are created by Boot / Spring Data;
    // asking for them as parameters is all the wiring needed. The engine parts below only ever see the port types.

    /**
     * @param jdbc Boot's JdbcClient over the application's connection pool
     * @return where cases are stored
     */
    @Bean
    CaseRepository caseRepository(JdbcClient jdbc) {
        return new JdbcCaseRepository(jdbc);
    }

    /**
     * @param jdbc Boot's JdbcClient over the application's connection pool
     * @return where patients are stored
     */
    @Bean
    PatientRepository patientRepository(JdbcClient jdbc) {
        return new JdbcPatientRepository(jdbc);
    }

    /**
     * @param jdbc Boot's JdbcClient over the application's connection pool
     * @return where units are stored, with the "units near X" searches
     */
    @Bean
    ResourceRepository resourceRepository(JdbcClient jdbc) {
        return new JdbcResourceRepository(jdbc);
    }

    /**
     * @param trips the Spring Data repository Spring builds from TripJpaRepository
     * @return where trips (assignments) are stored, through JPA
     */
    @Bean
    AssignmentRepository assignmentRepository(TripJpaRepository trips) {
        return new JpaAssignmentRepository(trips);
    }

    /**
     * @param jdbc Boot's JdbcClient over the application's connection pool
     * @return where vitals readings are stored (every reading, in the TimescaleDB hypertable)
     */
    /**
     * @param jdbc Spring's fluent JDBC API, created by Boot
     * @return the hospital and clinic registry on Postgres
     */
    @Bean
    FacilityRepository facilityRepository(JdbcClient jdbc) {
        return new JdbcFacilityRepository(jdbc);
    }

    @Bean
    VitalsRepository vitalsRepository(JdbcClient jdbc) {
        return new JdbcVitalsRepository(jdbc);
    }

    /**
     * @param jdbc Boot's JdbcClient over the application's connection pool
     * @return the queue of cases waiting for a unit, most urgent first (a flag on the case rows)
     */
    @Bean
    CaseQueue caseQueue(JdbcClient jdbc) {
        return new JdbcCaseQueue(jdbc);
    }

    /**
     * What stops two dispatches from claiming the same unit: one conditional UPDATE, refereed by Postgres' row lock,
     * so it holds even across several copies of the service.
     *
     * @param jdbc Boot's JdbcClient over the application's connection pool
     * @return the record of which unit is claimed by which case
     */
    @Bean
    ResourceClaimer resourceClaimer(JdbcClient jdbc) {
        return new JdbcResourceClaimer(jdbc);
    }

    /**
     * Stays in memory: nothing outside the process listens to the engine's events yet.
     *
     * @return where the engine announces what happened
     */
    // ponytail: an event recorded inside a transaction that then rolls back stays here as a "ghost". Nothing reads
    // these yet; the outbox table (events written in the same transaction) fixes it when something does.
    @Bean
    EventPublisher eventPublisher() {
        return new InMemoryEventPublisher();
    }

    // ---- Policies: the rules that can change without touching the engine.

    /** @return which unit types to send for each risk band (a placeholder table, not clinical guidance) */
    @Bean
    ResponseDecider responseDecider() {
        return new LadderResponseDecider();
    }

    /**
     * @param clock the shared clock
     * @return how a vitals reading becomes a risk band (a placeholder until NEWS2)
     */
    @Bean
    RiskScorer riskScorer(Clock clock) {
        return new SimpleScorer(clock);
    }

    // ---- Engine parts. The parameter lists are what Spring fills in from the beans above.

    /**
     * @param cases  case storage
     * @param queue  the waiting queue
     * @param events where changes are announced
     * @param clock  the shared clock
     * @return the part that moves cases through their life cycle
     */
    @Bean
    CaseManager caseManager(CaseRepository cases, CaseQueue queue, EventPublisher events, Clock clock) {
        return new CaseManager(cases, queue, events, clock);
    }

    /**
     * @param assignments trip storage
     * @param resources   unit storage
     * @param claimer     unit claims, released when a trip ends
     * @param events      where changes are announced
     * @param clock       the shared clock
     * @return the part that moves trips through their life cycle and frees units afterwards
     */
    @Bean
    AssignmentManager assignmentManager(AssignmentRepository assignments, ResourceRepository resources,
                                        ResourceClaimer claimer, EventPublisher events, Clock clock) {
        return new AssignmentManager(assignments, resources, claimer, events, clock);
    }

    /**
     * @param assignmentManager ends the lost trip
     * @param caseManager       puts the case back in the queue
     * @return the single entry point for "this unit is lost or taken"
     */
    @Bean
    ResponseRecovery responseRecovery(AssignmentManager assignmentManager, CaseManager caseManager) {
        return new ResponseRecovery(assignmentManager, caseManager);
    }

    /**
     * @param queue       the waiting queue
     * @param patients    patient storage (to know where the patient is)
     * @param resources   unit storage
     * @param claimer     unit claims
     * @param assignments trip storage
     * @param decider     which unit types to send
     * @param events      where changes are announced
     * @param clock       the shared clock
     * @param travel      travel times (by road when the graph is there)
     * @return the part that sends the suitable free unit that gets there soonest to the most urgent case
     */
    @Bean
    Dispatcher dispatcher(CaseQueue queue, PatientRepository patients, ResourceRepository resources,
                          ResourceClaimer claimer, AssignmentRepository assignments, ResponseDecider decider,
                          EventPublisher events, Clock clock, TravelTime travel) {
        return new Dispatcher(queue, patients, resources, claimer, assignments, decider, events, clock,
                SEARCH_RADIUS_KM, travel);
    }

    /**
     * @param assignments trip storage
     * @param cases       case storage (to compare bands)
     * @param resources   unit storage
     * @param patients    patient storage
     * @param decider     which unit types are allowed
     * @param recovery    takes the unit off its old trip
     * @return the part that frees a unit from a less urgent case when no unit is free
     */
    @Bean
    Preemptor preemptor(AssignmentRepository assignments, CaseRepository cases, ResourceRepository resources,
                        PatientRepository patients, ResponseDecider decider, ResponseRecovery recovery) {
        return new Preemptor(assignments, cases, resources, patients, decider, recovery, SEARCH_RADIUS_KM);
    }

    /**
     * @param cases       case storage
     * @param patients    patient storage
     * @param resources   unit storage
     * @param claimer     unit claims
     * @param assignments trip storage
     * @param decider     which responder types to send
     * @param events      where changes are announced
     * @param clock       the shared clock
     * @param travel      travel times (by road when the graph is there)
     * @return the part that sends a doctor or nurse alongside the ambulance when one gets there sooner
     */
    @Bean
    SupportDispatcher supportDispatcher(CaseRepository cases, PatientRepository patients,
                                        ResourceRepository resources, ResourceClaimer claimer,
                                        AssignmentRepository assignments, ResponseDecider decider,
                                        EventPublisher events, Clock clock, TravelTime travel) {
        return new SupportDispatcher(cases, patients, resources, claimer, assignments, decider, events, clock, travel);
    }

    /**
     * @param dispatcher sends a free unit
     * @param preemptor  frees a busy unit if needed
     * @param support    adds a first responder
     * @param queue      the waiting queue
     * @return one step of the dispatch loop: free unit, else preempt and retry, then a first responder
     */
    @Bean
    DispatchCoordinator dispatchCoordinator(Dispatcher dispatcher, Preemptor preemptor,
                                            SupportDispatcher support, CaseQueue queue) {
        return new DispatchCoordinator(dispatcher, preemptor, support, queue);
    }

    /**
     * @param facilities the hospital and clinic registry
     * @param travel     travel times (by road when the graph is there)
     * @return the destination rules: which hospital a patient is taken to
     */
    @Bean
    DestinationChooser destinationChooser(FacilityRepository facilities, TravelTime travel) {
        return new DestinationChooser(facilities, travel);
    }

    /**
     * @return the traffic on the roads: Bangalore's weekday rush hours; incidents are added while running
     */
    @Bean
    TrafficModel trafficModel() {
        return new TrafficModel(TrafficProfile.bangaloreWeekday());
    }

    /**
     * Real roads if the graph file is there, straight lines if not. One bean serves every "who gets there soonest"
     * question: the dispatcher, the support dispatcher, the hospital choice and the pre-alert ETA.
     *
     * @param graphFile where the road graph is ({@code medidispatch.routing.graph-file}); blank means "off"
     * @param traffic   rush hours and incidents
     * @param clock     the shared clock (the traffic is taken at "now")
     * @return the travel times to use
     * @throws IOException if the graph file is there but cannot be read
     */
    @Bean
    TravelTime travelTime(@Value("${medidispatch.routing.graph-file:}") String graphFile, TrafficModel traffic,
                          Clock clock) throws IOException {
        Optional<Path> file = findGraph(graphFile);
        if (file.isEmpty()) {
            log.warn("No road graph at '{}': routing uses straight lines at {} km/h", graphFile,
                    StraightLineTravelTime.SPEED_KMH);
            return new StraightLineTravelTime();
        }
        RoadTravelTime roads = RoadTravelTime.load(file.get(), traffic, clock);
        log.info("Road routing on: {} ({} nodes, {} edges)", file.get().toAbsolutePath(),
                roads.graph().nodeCount(), roads.graph().edgeCount());
        return roads;
    }

    /**
     * @param graphFile the configured path
     * @return the file as given, else (for a relative path) the same path one folder up, else empty
     */
    static Optional<Path> findGraph(String graphFile) {
        if (graphFile == null || graphFile.isBlank()) {
            return Optional.empty();
        }
        Path given = Path.of(graphFile);
        if (Files.isRegularFile(given)) {
            return Optional.of(given);
        }
        Path oneUp = Path.of("..").resolve(graphFile);
        if (!given.isAbsolute() && Files.isRegularFile(oneUp)) {
            return Optional.of(oneUp);
        }
        return Optional.empty();
    }

    /**
     * @param assignments       where trips are stored
     * @param resources         where units are stored
     * @param chooser           picks the hospital
     * @param assignmentManager moves the trip
     * @param caseManager       moves the case
     * @return the one call that starts a transport (scene to hospital)
     */
    @Bean
    TransportCoordinator transportCoordinator(AssignmentRepository assignments, ResourceRepository resources,
                                              DestinationChooser chooser, AssignmentManager assignmentManager,
                                              CaseManager caseManager) {
        return new TransportCoordinator(assignments, resources, chooser, assignmentManager, caseManager);
    }

    /**
     * @param resources unit storage
     * @param clock     the shared clock
     * @return the part that records unit positions and puts units back in service
     */
    @Bean
    UnitTracker unitTracker(ResourceRepository resources, Clock clock) {
        return new UnitTracker(resources, clock);
    }

    /**
     * @param assignments trip storage
     * @param recovery    takes the trip back
     * @param clock       the shared clock
     * @return the check for crews that never accepted their trip within {@link #ACK_TIMEOUT}
     */
    @Bean
    TimeoutMonitor timeoutMonitor(AssignmentRepository assignments, ResponseRecovery recovery, Clock clock) {
        return new TimeoutMonitor(assignments, recovery, clock, ACK_TIMEOUT);
    }

    /**
     * @param assignments trip storage
     * @param resources   unit storage (last seen and last moved times)
     * @param recovery    takes the trip back
     * @param clock       the shared clock
     * @return the check for units on a trip that went silent or stopped moving
     */
    @Bean
    TripMonitor tripMonitor(AssignmentRepository assignments, ResourceRepository resources,
                            ResponseRecovery recovery, Clock clock) {
        return new TripMonitor(assignments, resources, recovery, clock, SIGNAL_TIMEOUT, STALL_TIMEOUT);
    }

    /**
     * @param assignments       trip storage
     * @param resources         unit storage
     * @param patients          patient storage
     * @param decider           which unit types the new band needs
     * @param assignmentManager demotes the old unit to first responder
     * @param caseManager       puts the case back in the queue
     * @return the part that sends a stronger unit when a patient gets worse while help is on the way
     */
    @Bean
    ResponseUpgrader responseUpgrader(AssignmentRepository assignments, ResourceRepository resources,
                                      PatientRepository patients, ResponseDecider decider,
                                      AssignmentManager assignmentManager, CaseManager caseManager) {
        return new ResponseUpgrader(assignments, resources, patients, decider, assignmentManager, caseManager);
    }

    /**
     * @param patients    patient storage
     * @param cases       case storage
     * @param vitals      reading storage
     * @param scorer      turns a reading into a risk band
     * @param caseManager opens or updates the case
     * @param upgrader    upgrades the response if the patient got worse
     * @param clock       the shared clock
     * @return the front door for vitals readings
     */
    @Bean
    VitalsProcessor vitalsProcessor(PatientRepository patients, CaseRepository cases, VitalsRepository vitals,
                                    RiskScorer scorer, CaseManager caseManager, ResponseUpgrader upgrader,
                                    Clock clock) {
        return new VitalsProcessor(patients, cases, vitals, scorer, caseManager, upgrader, clock,
                MAX_READING_AGE);
    }
}
