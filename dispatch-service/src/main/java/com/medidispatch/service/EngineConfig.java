package com.medidispatch.service;

import com.medidispatch.core.engine.AssignmentManager;
import com.medidispatch.core.engine.CaseManager;
import com.medidispatch.core.engine.DispatchCoordinator;
import com.medidispatch.core.engine.Dispatcher;
import com.medidispatch.core.engine.Preemptor;
import com.medidispatch.core.engine.ResponseRecovery;
import com.medidispatch.core.engine.ResponseUpgrader;
import com.medidispatch.core.engine.SupportDispatcher;
import com.medidispatch.core.engine.TimeoutMonitor;
import com.medidispatch.core.engine.TripMonitor;
import com.medidispatch.core.engine.UnitTracker;
import com.medidispatch.core.engine.VitalsProcessor;
import com.medidispatch.core.memory.InMemoryAssignmentRepository;
import com.medidispatch.core.memory.InMemoryCaseQueue;
import com.medidispatch.core.memory.InMemoryCaseRepository;
import com.medidispatch.core.memory.InMemoryEventPublisher;
import com.medidispatch.core.memory.InMemoryPatientRepository;
import com.medidispatch.core.memory.InMemoryResourceClaimer;
import com.medidispatch.core.memory.InMemoryResourceRepository;
import com.medidispatch.core.memory.InMemoryVitalsRepository;
import com.medidispatch.core.port.AssignmentRepository;
import com.medidispatch.core.port.CaseQueue;
import com.medidispatch.core.port.CaseRepository;
import com.medidispatch.core.port.EventPublisher;
import com.medidispatch.core.port.PatientRepository;
import com.medidispatch.core.port.ResourceClaimer;
import com.medidispatch.core.port.ResourceRepository;
import com.medidispatch.core.port.VitalsRepository;
import com.medidispatch.core.response.LadderResponseDecider;
import com.medidispatch.core.response.ResponseDecider;
import com.medidispatch.core.scoring.RiskScorer;
import com.medidispatch.core.scoring.SimpleScorer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Builds the dispatch engine and hands it to Spring.
 *
 * <p>The engine ({@code dispatch-core}) is plain Java with no Spring annotations, so Spring cannot find its classes
 * by scanning. Instead each {@code @Bean} method below builds one part with {@code new}. Spring calls each method
 * once, keeps the result (a "bean"), and passes it to any other {@code @Bean} method or constructor that asks for
 * that type as a parameter. That is how the parts get wired together, and why every part exists exactly once.
 *
 * <p>This is the only class that knows which adapters are used. Swapping the in-memory repositories for Postgres
 * ones later means changing the port beans here and nothing else.
 */
@Configuration
public class EngineConfig {

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

    // ---- Ports: storage, queue, claims and events.
    // ponytail: in-memory adapters lose everything on restart; the Postgres adapters in week 1 replace these beans.

    /** @return where cases are stored */
    @Bean
    CaseRepository caseRepository() {
        return new InMemoryCaseRepository();
    }

    /** @return where patients are stored */
    @Bean
    PatientRepository patientRepository() {
        return new InMemoryPatientRepository();
    }

    /** @return where units are stored */
    @Bean
    ResourceRepository resourceRepository() {
        return new InMemoryResourceRepository();
    }

    /** @return where trips (assignments) are stored */
    @Bean
    AssignmentRepository assignmentRepository() {
        return new InMemoryAssignmentRepository();
    }

    /** @return where vitals readings are stored (in memory: only the latest per patient) */
    @Bean
    VitalsRepository vitalsRepository() {
        return new InMemoryVitalsRepository();
    }

    /** @return the queue of cases waiting for a unit, most urgent first */
    @Bean
    CaseQueue caseQueue() {
        return new InMemoryCaseQueue();
    }

    /**
     * Must be a single shared object: it is what stops two dispatches from claiming the same unit.
     *
     * @return the record of which unit is claimed by which case
     */
    @Bean
    ResourceClaimer resourceClaimer() {
        return new InMemoryResourceClaimer();
    }

    /** @return where the engine announces what happened (later read by the live feed) */
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
     * @return the part that sends the nearest suitable free unit to the most urgent case
     */
    @Bean
    Dispatcher dispatcher(CaseQueue queue, PatientRepository patients, ResourceRepository resources,
                          ResourceClaimer claimer, AssignmentRepository assignments, ResponseDecider decider,
                          EventPublisher events, Clock clock) {
        return new Dispatcher(queue, patients, resources, claimer, assignments, decider, events, clock,
                SEARCH_RADIUS_KM);
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
     * @return the part that sends a doctor or nurse alongside the ambulance when one is closer
     */
    @Bean
    SupportDispatcher supportDispatcher(CaseRepository cases, PatientRepository patients,
                                        ResourceRepository resources, ResourceClaimer claimer,
                                        AssignmentRepository assignments, ResponseDecider decider,
                                        EventPublisher events, Clock clock) {
        return new SupportDispatcher(cases, patients, resources, claimer, assignments, decider, events, clock);
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
