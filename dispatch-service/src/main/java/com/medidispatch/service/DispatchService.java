package com.medidispatch.service;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.VitalsReading;
import com.medidispatch.core.engine.AssignmentManager;
import com.medidispatch.core.engine.CaseManager;
import com.medidispatch.core.engine.DispatchCoordinator;
import com.medidispatch.core.engine.ProcessingResult;
import com.medidispatch.core.engine.TimeoutMonitor;
import com.medidispatch.core.engine.TripMonitor;
import com.medidispatch.core.engine.UnitTracker;
import com.medidispatch.core.engine.VitalsProcessor;
import com.medidispatch.core.port.AssignmentRepository;
import com.medidispatch.core.port.CaseRepository;
import com.medidispatch.core.port.PatientRepository;
import com.medidispatch.core.port.ResourceRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The single front door to the dispatch engine. Everything outside the engine (REST controllers, the live feed,
 * the simulator) talks to this class and nothing else.
 *
 * <p>The engine parts are not thread-safe on their own: a web server handles many requests at once on many
 * threads, and the scheduled {@link #tick()} runs on yet another thread. So every public method is
 * {@code synchronized} on this one object, which means only one of them runs at any moment. Reads take the lock
 * too, because a read in the middle of another thread's write could see half-changed state.
 *
 * <p>Methods return small immutable records ({@link CaseInfo}, {@link TripInfo}, {@link UnitInfo}) built while
 * the lock is held, never the engine's own mutable objects. A caller can keep them, send them as JSON or pass
 * them to another thread without ever touching live engine state.
 */
// ponytail: one global lock, fine for the in-memory engine at demo scale. Revisit when Postgres arrives: the
// database claim (UPDATE ... WHERE status='AVAILABLE') and transactions take over most of this job.
@Service
public class DispatchService {

    private final PatientRepository patients;
    private final ResourceRepository resources;
    private final CaseRepository cases;
    private final AssignmentRepository assignments;
    private final VitalsProcessor processor;
    private final CaseManager caseManager;
    private final AssignmentManager assignmentManager;
    private final UnitTracker unitTracker;
    private final DispatchCoordinator coordinator;
    private final TimeoutMonitor timeoutMonitor;
    private final TripMonitor tripMonitor;

    /**
     * Spring calls this constructor and passes in the engine parts built by {@link EngineConfig}.
     *
     * @param patients          where patients are stored
     * @param resources         where units are stored
     * @param cases             where cases are stored
     * @param assignments       where trips are stored
     * @param processor         takes in vitals readings and opens or updates cases
     * @param caseManager       moves cases through their life cycle
     * @param assignmentManager moves trips through their life cycle
     * @param unitTracker       records unit positions and puts units back in service
     * @param coordinator       sends units to waiting cases
     * @param timeoutMonitor    finds crews that never acknowledged a trip
     * @param tripMonitor       finds trips whose unit went silent or stopped moving
     */
    public DispatchService(PatientRepository patients, ResourceRepository resources, CaseRepository cases,
                           AssignmentRepository assignments, VitalsProcessor processor, CaseManager caseManager,
                           AssignmentManager assignmentManager, UnitTracker unitTracker,
                           DispatchCoordinator coordinator, TimeoutMonitor timeoutMonitor, TripMonitor tripMonitor) {
        this.patients = patients;
        this.resources = resources;
        this.cases = cases;
        this.assignments = assignments;
        this.processor = processor;
        this.caseManager = caseManager;
        this.assignmentManager = assignmentManager;
        this.unitTracker = unitTracker;
        this.coordinator = coordinator;
        this.timeoutMonitor = timeoutMonitor;
        this.tripMonitor = tripMonitor;
    }

    /**
     * Adds a new patient at a home location.
     *
     * @param id   the patient id, unique
     * @param home where the patient is
     * @throws IllegalStateException if a patient with this id already exists
     */
    public synchronized void registerPatient(String id, Location home) {
        // Without this check, save() would silently replace the existing patient.
        if (patients.findById(id).isPresent()) {
            throw new IllegalStateException("Patient " + id + " already exists");
        }
        patients.save(new Patient(id, home));
    }

    /**
     * Adds a new unit (ambulance, nurse, doctor and so on). It starts out AVAILABLE.
     *
     * @param id    the unit id, unique
     * @param type  what kind of unit it is
     * @param where where the unit is now
     * @return the new unit
     * @throws IllegalStateException if a unit with this id already exists
     */
    public synchronized UnitInfo registerUnit(String id, ResourceType type, Location where) {
        if (resources.findById(id).isPresent()) {
            throw new IllegalStateException("Unit " + id + " already exists");
        }
        Resource unit = new Resource(id, type, where);
        resources.save(unit);
        return UnitInfo.from(unit);
    }

    /**
     * Takes in one vitals reading. The engine scores it and may open, update or escalate a case.
     * No unit is sent here: that happens on the next {@link #tick()}.
     *
     * @param reading the reading from the patient's device
     * @return what happened to the reading, and the patient's open case if there is one
     */
    public synchronized ReadingResult submitReading(VitalsReading reading) {
        ProcessingResult result = processor.process(reading);
        // Optional.map runs the lambda only if a case is present; orElse(null) turns "no case" into null.
        CaseInfo openCase = result.currentCase().map(c -> CaseInfo.from(c)).orElse(null);
        return new ReadingResult(result.outcome(), openCase);
    }

    /**
     * The crew accepted the trip and is on the way: ASSIGNED to EN_ROUTE.
     *
     * @param tripId the trip
     * @return the updated trip
     * @throws NotFoundException     if there is no such trip
     * @throws IllegalStateException if the trip is not ASSIGNED
     */
    public synchronized TripInfo acceptTrip(String tripId) {
        requireTrip(tripId);
        return TripInfo.from(assignmentManager.onEnRoute(tripId));
    }

    /**
     * A unit sends its current position (from the crew's phone GPS).
     *
     * @param unitId the unit
     * @param where  where it is now
     * @throws NotFoundException if there is no such unit
     */
    public synchronized void reportPosition(String unitId, Location where) {
        requireUnit(unitId);
        unitTracker.onLocationReported(unitId, where);
    }

    /**
     * The crew reached the patient: EN_ROUTE to ARRIVED. If this is the main (PRIMARY) unit, any support units
     * still on the way are stood down by the engine.
     *
     * @param tripId the trip
     * @return the updated trip
     * @throws NotFoundException     if there is no such trip
     * @throws IllegalStateException if the trip is not EN_ROUTE
     */
    public synchronized TripInfo arrive(String tripId) {
        requireTrip(tripId);
        return TripInfo.from(assignmentManager.onArrived(tripId));
    }

    /**
     * The crew finished on scene: the trip becomes DONE and the unit is free again. When the main (PRIMARY) unit
     * finishes, the patient is treated as stabilised and the case is closed.
     *
     * @param tripId the trip
     * @return the updated trip
     * @throws NotFoundException     if there is no such trip
     * @throws IllegalStateException if the trip cannot be completed from its current status
     */
    public synchronized TripInfo completeTrip(String tripId) {
        requireTrip(tripId);
        Assignment trip = assignmentManager.complete(tripId);
        // A support unit leaving does not end the case; only the main unit finishing does.
        if (trip.role() == AssignmentRole.PRIMARY) {
            caseManager.onStabilised(trip.caseId());
            caseManager.close(trip.caseId());
        }
        return TripInfo.from(trip);
    }

    /**
     * An OFFLINE unit (for example, one that timed out) is ready for work again: OFFLINE to AVAILABLE.
     *
     * @param unitId the unit
     * @return the updated unit
     * @throws NotFoundException     if there is no such unit
     * @throws IllegalStateException if the unit is not OFFLINE
     */
    public synchronized UnitInfo backInService(String unitId) {
        requireUnit(unitId);
        unitTracker.onBackInService(unitId);
        return UnitInfo.from(resources.findById(unitId).orElseThrow());
    }

    /**
     * Looks up a case by id.
     *
     * @param caseId the case
     * @return the case, or empty if there is none with this id
     */
    public synchronized Optional<CaseInfo> findCase(String caseId) {
        return cases.findById(caseId).map(c -> CaseInfo.from(c));
    }

    /**
     * Looks up the case a patient has open right now (not closed or cancelled).
     *
     * @param patientId the patient
     * @return the patient's open case, or empty if they have none
     */
    public synchronized Optional<CaseInfo> openCaseOf(String patientId) {
        return cases.findOpenByPatientId(patientId).map(c -> CaseInfo.from(c));
    }

    /**
     * Looks up a unit by id.
     *
     * @param unitId the unit
     * @return the unit, or empty if there is none with this id
     */
    public synchronized Optional<UnitInfo> findUnit(String unitId) {
        return resources.findById(unitId).map(r -> UnitInfo.from(r));
    }

    /**
     * Looks up a trip by id.
     *
     * @param tripId the trip
     * @return the trip, or empty if there is none with this id
     */
    public synchronized Optional<TripInfo> findTrip(String tripId) {
        return assignments.findById(tripId).map(a -> TripInfo.from(a));
    }

    /**
     * Lets a crew app ask "do I have a job right now?".
     *
     * @param unitId the unit
     * @return the unit's current trip, or empty if it has none
     */
    public synchronized Optional<TripInfo> activeTripOf(String unitId) {
        return assignments.findActiveByResourceId(unitId).map(a -> TripInfo.from(a));
    }

    /**
     * The heartbeat. Spring calls this once a second, counted from the end of the previous run ({@code fixedDelay}),
     * so two ticks never overlap. Each tick:
     * <ol>
     *   <li>sends units to every waiting case it can serve,</li>
     *   <li>takes back trips whose crew never acknowledged,</li>
     *   <li>takes back trips whose unit went silent or stopped moving.</li>
     * </ol>
     * If a tick throws, Spring logs the error and still runs the next one.
     */
    @Scheduled(fixedDelay = 1000)
    public synchronized void tick() {
        dispatchAll();
        timeoutMonitor.checkTimeouts();
        tripMonitor.checkTrips();
    }

    /**
     * Keeps dispatching until no more waiting cases can be served (the queue is empty, or no unit can be found for
     * the next one). The coordinator only sends the unit; marking the case RESPONDING is done here, for the
     * main unit only.
     */
    private void dispatchAll() {
        while (true) {
            Optional<Assignment> sent = coordinator.dispatchNext();
            if (sent.isEmpty()) {
                return;
            }
            caseManager.onAssigned(sent.get());
        }
    }

    /**
     * Fails early with {@link NotFoundException} for an unknown trip. The engine would throw
     * {@link IllegalStateException}, which the web layer reports as "409 conflict"; a missing trip should be
     * "404 not found".
     *
     * @param tripId the trip that must exist
     * @throws NotFoundException if it does not
     */
    private void requireTrip(String tripId) {
        if (assignments.findById(tripId).isEmpty()) {
            throw new NotFoundException("Trip " + tripId + " not found");
        }
    }

    /**
     * Same as {@link #requireTrip(String)}, for units.
     *
     * @param unitId the unit that must exist
     * @throws NotFoundException if it does not
     */
    private void requireUnit(String unitId) {
        if (resources.findById(unitId).isEmpty()) {
            throw new NotFoundException("Unit " + unitId + " not found");
        }
    }
}
