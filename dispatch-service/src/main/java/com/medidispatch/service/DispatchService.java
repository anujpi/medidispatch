package com.medidispatch.service;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.Contact;
import com.medidispatch.core.domain.Facility;
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
import com.medidispatch.core.engine.TransportCoordinator;
import com.medidispatch.core.engine.TripMonitor;
import com.medidispatch.core.engine.UnitTracker;
import com.medidispatch.core.engine.VitalsProcessor;
import com.medidispatch.core.port.AssignmentRepository;
import com.medidispatch.core.port.CaseRepository;
import com.medidispatch.core.port.FacilityRepository;
import com.medidispatch.core.port.PatientRepository;
import com.medidispatch.core.port.ResourceRepository;
import com.medidispatch.core.port.VitalsRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The single front door to the dispatch engine. Everything outside the engine (REST controllers, the live feed,
 * the simulator) talks to this class and nothing else.
 *
 * <p>The engine parts are not thread-safe on their own: a web server handles many requests at once on many
 * threads, and the scheduled {@link #tick()} runs on yet another thread. So every public method is
 * {@code synchronized} on this one object, which means only one of them runs at any moment. Reads take the lock
 * too, because a read in the middle of another thread's write could see half-changed state.
 *
 * <p>Every call is also ONE database transaction (all its writes happen, or none do), and the transaction lives
 * INSIDE the lock: lock, BEGIN, work, COMMIT, unlock. {@code @Transactional} on the methods would get this backwards:
 * Spring adds it with a proxy AROUND this object, so the lock would be released before the commit, and the next thread
 * could read rows from before it (a unit that looks free, dispatched twice). {@link TransactionTemplate} opens and
 * commits the transaction right here instead; if the work throws, it rolls back and re-throws. One transaction covers
 * the JDBC and the JPA adapters alike.
 *
 * <p>Methods return small immutable records ({@link CaseInfo}, {@link TripInfo}, {@link UnitInfo}) built while
 * the lock is held, never the engine's own mutable objects. A caller can keep them, send them as JSON or pass
 * them to another thread without ever touching live engine state.
 */
// ponytail: one global lock around every call, so requests never run in parallel. Fine at demo scale on one
// instance. For several instances or more throughput: drop the lock, keep the transactions, and add optimistic
// locking (version columns), proven by a two-thread test that loses an update first.
@Service
public class DispatchService {

    /**
     * Ambulance speed for the pre-alert ETA, on a straight line to the hospital.
     */
    // ponytail: straight line at a fixed 40 km/h, no roads or traffic; a routing service later (BACKLOG: ETA instead
    // of straight-line distance).
    public static final double TRANSPORT_SPEED_KMH = 40;

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
    private final FacilityRepository facilities;
    private final TransportCoordinator transport;
    private final VitalsRepository vitals;
    private final Clock clock;
    private final TransactionTemplate tx;

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
     * @param facilities        where hospitals and clinics are stored
     * @param transport         starts the second leg of a trip, scene to hospital
     * @param vitals            the patients' readings (the pre-alert shows the newest)
     * @param clock             the engine's clock, used to timestamp snapshots
     * @param tx                runs a piece of work as one database transaction (created by Boot)
     */
    public DispatchService(PatientRepository patients, ResourceRepository resources, CaseRepository cases,
                           AssignmentRepository assignments, VitalsProcessor processor, CaseManager caseManager,
                           AssignmentManager assignmentManager, UnitTracker unitTracker,
                           DispatchCoordinator coordinator, TimeoutMonitor timeoutMonitor, TripMonitor tripMonitor,
                           FacilityRepository facilities, TransportCoordinator transport, VitalsRepository vitals,
                           Clock clock, TransactionTemplate tx) {
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
        this.facilities = facilities;
        this.transport = transport;
        this.vitals = vitals;
        this.clock = clock;
        this.tx = tx;
    }

    /**
     * Adds a new patient. Whether their details are required is the API's rule (the request records), so the
     * service itself also accepts an anonymous patient.
     *
     * @param patient the new patient, with their details and emergency contact if known
     * @throws IllegalStateException if a patient with this id already exists
     */
    public synchronized void registerPatient(Patient patient) {
        // executeWithoutResult: a transaction for work that returns nothing.
        tx.executeWithoutResult(status -> {
            // Without this check, save() would silently replace the existing patient.
            if (patients.findById(patient.id()).isPresent()) {
                throw new IllegalStateException("Patient " + patient.id() + " already exists");
            }
            patients.save(patient);
        });
    }

    /**
     * Looks up a patient's full record, including the emergency contact (which the live feed never carries).
     *
     * @param patientId the patient
     * @return the patient, or empty if there is none with that id
     */
    public synchronized Optional<PatientInfo> findPatient(String patientId) {
        return tx.execute(status -> patients.findById(patientId).map(p -> PatientInfo.from(p)));
    }

    /**
     * Adds a new unit (ambulance, nurse, doctor and so on). It starts out AVAILABLE.
     *
     * @param id    the unit id, unique
     * @param type  what kind of unit it is
     * @param where  where the unit is now
     * @param baseId the hospital or clinic the unit belongs to; {@code null} for none
     * @param driver who drives the unit and how to reach them; {@code null} if not on file
     * @return the new unit
     * @throws IllegalStateException    if a unit with this id already exists
     * @throws IllegalArgumentException if the base does not exist (a wrong value in the request, so 400, not 404)
     */
    public synchronized UnitInfo registerUnit(String id, ResourceType type, Location where, String baseId,
                                              Contact driver) {
        // execute: a transaction whose result (the lambda's return value) is handed back after the commit.
        return tx.execute(status -> {
            if (resources.findById(id).isPresent()) {
                throw new IllegalStateException("Unit " + id + " already exists");
            }
            // Checked here for a clear message; the units.base_id foreign key would refuse it anyway.
            if (baseId != null && facilities.findById(baseId).isEmpty()) {
                throw new IllegalArgumentException("Base " + baseId + " does not exist");
            }
            Resource unit = new Resource(id, type, where, baseId, driver);
            resources.save(unit);
            return UnitInfo.from(unit);
        });
    }

    /**
     * Takes in one vitals reading. The engine scores it and may open, update or escalate a case.
     * No unit is sent here: that happens on the next {@link #tick()}.
     *
     * @param reading the reading from the patient's device
     * @return what happened to the reading, and the patient's open case if there is one
     */
    public synchronized ReadingResult submitReading(VitalsReading reading) {
        return tx.execute(status -> {
            ProcessingResult result = processor.process(reading);
            // Optional.map runs the lambda only if a case is present; orElse(null) turns "no case" into null.
            CaseInfo openCase = result.currentCase().map(c -> caseInfo(c)).orElse(null);
            return new ReadingResult(result.outcome(), openCase);
        });
    }

    /**
     * The crew accepted the trip and is on the way: ASSIGNED to EN_ROUTE.
     *
     * <p>Safe to repeat: if the trip is already EN_ROUTE, this is a retry of an accept that worked (its response
     * was lost on a weak network), so the trip is returned unchanged instead of failing.
     *
     * @param tripId the trip
     * @return the updated trip
     * @throws NotFoundException     if there is no such trip
     * @throws IllegalStateException if the trip is neither ASSIGNED nor already EN_ROUTE
     */
    public synchronized TripInfo acceptTrip(String tripId) {
        return tx.execute(status -> {
            Assignment trip = requireTrip(tripId);
            // Only the exact target state counts as "already done". Anything else (DONE, CANCELLED, TIMED_OUT...)
            // goes to the engine, which refuses it.
            if (trip.status() == AssignmentStatus.EN_ROUTE) {
                return TripInfo.from(trip);
            }
            return TripInfo.from(assignmentManager.onEnRoute(tripId));
        });
    }

    /**
     * A unit sends its current position (from the crew's phone GPS).
     *
     * @param unitId the unit
     * @param where  where it is now
     * @throws NotFoundException if there is no such unit
     */
    public synchronized void reportPosition(String unitId, Location where) {
        tx.executeWithoutResult(status -> {
            requireUnit(unitId);
            unitTracker.onLocationReported(unitId, where);
        });
    }

    /**
     * The crew reached the patient: EN_ROUTE to ARRIVED. If this is the main (PRIMARY) unit, any support units
     * still on the way are stood down by the engine.
     *
     * <p>Safe to repeat: if the trip is already ARRIVED, it is returned unchanged.
     *
     * @param tripId the trip
     * @return the updated trip
     * @throws NotFoundException     if there is no such trip
     * @throws IllegalStateException if the trip is neither EN_ROUTE nor already ARRIVED
     */
    public synchronized TripInfo arrive(String tripId) {
        return tx.execute(status -> {
            Assignment trip = requireTrip(tripId);
            if (trip.status() == AssignmentStatus.ARRIVED) {
                return TripInfo.from(trip);
            }
            return TripInfo.from(assignmentManager.onArrived(tripId));
        });
    }

    /**
     * The crew finished, on scene or after handing the patient over at the hospital: the trip becomes DONE and the
     * unit is free again. When the main (PRIMARY) unit finishes, the patient is treated as stabilised and the case is
     * closed. All of it is one transaction: the trip
     * is never DONE while its case stays open.
     *
     * <p>Safe to repeat: if the trip is already DONE, it is returned unchanged and the case is not touched again.
     *
     * @param tripId the trip
     * @return the updated trip
     * @throws NotFoundException     if there is no such trip
     * @throws IllegalStateException if the trip cannot be completed from its current status
     */
    public synchronized TripInfo completeTrip(String tripId) {
        return tx.execute(status -> {
            Assignment trip = requireTrip(tripId);
            // Returning here matters: running on would close an already closed case and throw.
            if (trip.status() == AssignmentStatus.DONE) {
                return TripInfo.from(trip);
            }
            Assignment done = assignmentManager.complete(tripId);
            // A support unit leaving does not end the case; only the main unit finishing does.
            if (done.role() == AssignmentRole.PRIMARY) {
                caseManager.onStabilised(done.caseId());
                caseManager.close(done.caseId());
            }
            return TripInfo.from(done);
        });
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
        return tx.execute(status -> {
            requireUnit(unitId);
            unitTracker.onBackInService(unitId);
            return UnitInfo.from(resources.findById(unitId).orElseThrow());
        });
    }

    /**
     * Adds a hospital or clinic (done by the operator; hospitals do not sign themselves up).
     *
     * @param facility the new facility
     * @return the stored facility
     * @throws IllegalStateException if a facility with this id already exists
     */
    public synchronized FacilityInfo registerFacility(Facility facility) {
        return tx.execute(status -> {
            // Without this check, save() would silently replace the existing facility.
            if (facilities.findById(facility.id()).isPresent()) {
                throw new IllegalStateException("Facility " + facility.id() + " already exists");
            }
            facilities.save(facility);
            return FacilityInfo.from(facility);
        });
    }

    /**
     * A hospital declares diversion ({@code false}: full, send patients elsewhere when possible) or ends it. Setting
     * the value it already has changes nothing, so a repeat is harmless.
     *
     * @param facilityId the facility
     * @param accepting  whether its emergency department takes new patients
     * @return the updated facility
     * @throws NotFoundException        if there is no such facility
     * @throws IllegalArgumentException if it is a clinic and {@code accepting} is {@code true}
     */
    public synchronized FacilityInfo setAccepting(String facilityId, boolean accepting) {
        return tx.execute(status -> {
            Facility updated = requireFacility(facilityId).withAccepting(accepting);
            facilities.save(updated);
            return FacilityInfo.from(updated);
        });
    }

    /**
     * The crew takes the patient to hospital: the engine picks the hospital from where the unit is and what the
     * patient needs, and the trip and the case move to TRANSPORTING together.
     *
     * <p>Safe to repeat: if the trip is already TRANSPORTING, it is returned unchanged (the hospital already chosen
     * stands, and the {@code needs} of the repeat are ignored).
     *
     * @param tripId the main trip of the case; must be ARRIVED
     * @param needs  the specialist services the patient needs; empty means nothing special
     * @return the updated trip, with its hospital
     * @throws NotFoundException     if there is no such trip
     * @throws IllegalStateException if the trip cannot transport (not the main trip, not arrived, not an ambulance)
     *                               or no hospital is registered; nothing is changed
     */
    public synchronized TripInfo startTransport(String tripId, Set<Capability> needs) {
        return tx.execute(status -> {
            Assignment trip = requireTrip(tripId);
            if (trip.status() == AssignmentStatus.TRANSPORTING) {
                return TripInfo.from(trip);
            }
            return TripInfo.from(transport.start(tripId, needs));
        });
    }

    /**
     * Looks up a case by id.
     *
     * @param caseId the case
     * @return the case, or empty if there is none with this id
     */
    public synchronized Optional<CaseInfo> findCase(String caseId) {
        return tx.execute(status -> cases.findById(caseId).map(c -> caseInfo(c)));
    }

    /**
     * Looks up the case a patient has open right now (not closed or cancelled).
     *
     * @param patientId the patient
     * @return the patient's open case, or empty if they have none
     */
    public synchronized Optional<CaseInfo> openCaseOf(String patientId) {
        return tx.execute(status -> cases.findOpenByPatientId(patientId).map(c -> caseInfo(c)));
    }

    /**
     * Everything a live screen shows, at this moment: every unit, every open case, every trip under way.
     *
     * <p>One transaction, so the three lists come from the same moment. Only copies state into immutable records,
     * with no network I/O, so the lock is held only for the reads. The caller (the live feed) sends the result to
     * screens AFTER the lock is released: a slow screen must never hold up dispatch.
     *
     * @return the frozen picture, safe to read from any thread
     */
    public synchronized LiveSnapshot snapshot() {
        return tx.execute(status -> {
            List<UnitInfo> units = new ArrayList<>();
            for (Resource r : resources.findAll()) {
                units.add(UnitInfo.from(r));
            }
            List<CaseInfo> openCases = new ArrayList<>();
            for (Case c : cases.findOpen()) {
                openCases.add(caseInfo(c));
            }
            List<TripInfo> trips = new ArrayList<>();
            for (Assignment a : assignments.findActive()) {
                trips.add(TripInfo.from(a));
            }
            return new LiveSnapshot(clock.instant(), units, openCases, trips, facilityInfos());
        });
    }

    /**
     * Looks up a unit by id.
     *
     * @param unitId the unit
     * @return the unit, or empty if there is none with this id
     */
    /**
     * Looks up a hospital or clinic by id.
     *
     * @param facilityId the facility
     * @return the facility, or empty if there is none with that id
     */
    public synchronized Optional<FacilityInfo> findFacility(String facilityId) {
        return tx.execute(status -> facilities.findById(facilityId).map(f -> FacilityInfo.from(f)));
    }

    /**
     * @return every hospital and clinic, sorted by id
     */
    public synchronized List<FacilityInfo> facilities() {
        return tx.execute(status -> facilityInfos());
    }

    /**
     * A facility's own fleet: every unit based there, whatever it is doing now.
     *
     * @param facilityId the hospital or clinic
     * @return its units, sorted by id
     * @throws NotFoundException if there is no such facility
     */
    // ponytail: filters every unit in Java (hundreds at most, and the live snapshot reads them all every second
    // anyway). A findByBaseId query with an index on units.base_id if a city ever has thousands.
    public synchronized List<UnitInfo> fleetOf(String facilityId) {
        return tx.execute(status -> {
            requireFacility(facilityId);
            List<UnitInfo> fleet = new ArrayList<>();
            for (Resource r : resources.findAll()) {
                if (r.baseId().isPresent() && r.baseId().get().equals(facilityId)) {
                    fleet.add(UnitInfo.from(r));
                }
            }
            return fleet;
        });
    }

    /**
     * A hospital's pre-alert: every patient being taken there right now, soonest first (then by trip id), with how
     * urgent they are, what they need, where the ambulance is, the ETA and the newest vitals reading.
     *
     * <p>The ETA is the straight-line distance from the ambulance's last reported position at
     * {@link #TRANSPORT_SPEED_KMH}, rounded UP to whole minutes, so it never says 0 while the ambulance is still on
     * its way. It drops as the ambulance reports positions closer in. Screens poll this every few seconds.
     *
     * @param facilityId the hospital
     * @return the patients on their way in; empty for a clinic or when nobody is coming
     * @throws NotFoundException if there is no such facility
     */
    public synchronized List<InboundPatient> inboundTo(String facilityId) {
        return tx.execute(status -> {
            Facility hospital = requireFacility(facilityId);
            List<InboundPatient> inbound = new ArrayList<>();
            // ponytail: reads every active trip (tens) and keeps this hospital's; an index on
            // (hospital_id) WHERE status = 'TRANSPORTING' if that ever gets slow.
            for (Assignment trip : assignments.findActive()) {
                boolean comingHere = trip.status() == AssignmentStatus.TRANSPORTING
                        && trip.hospitalId().isPresent() && trip.hospitalId().get().equals(facilityId);
                if (!comingHere) {
                    continue;
                }
                // Safe: a trip always has its case and unit (foreign keys).
                Case c = cases.findById(trip.caseId()).orElseThrow();
                Patient patient = patients.findById(c.patientId()).orElseThrow();
                Resource unit = resources.findById(trip.resourceId()).orElseThrow();
                double km = unit.location().distanceKmTo(hospital.location());
                long etaMinutes = (long) Math.ceil(km / TRANSPORT_SPEED_KMH * 60);
                VitalsReading latest = vitals.latestFor(c.patientId()).orElse(null);
                inbound.add(InboundPatient.from(trip, c, patient, unit, km, etaMinutes, latest));
            }
            inbound.sort((a, b) -> {
                int byEta = Long.compare(a.etaMinutes(), b.etaMinutes());
                return byEta != 0 ? byEta : a.tripId().compareTo(b.tripId());
            });
            return inbound;
        });
    }

    public synchronized Optional<UnitInfo> findUnit(String unitId) {
        return tx.execute(status -> resources.findById(unitId).map(r -> UnitInfo.from(r)));
    }

    /**
     * Looks up a trip by id.
     *
     * @param tripId the trip
     * @return the trip, or empty if there is none with this id
     */
    public synchronized Optional<TripInfo> findTrip(String tripId) {
        return tx.execute(status -> assignments.findById(tripId).map(a -> TripInfo.from(a)));
    }

    /**
     * Lets a crew app ask "do I have a job right now?".
     *
     * @param unitId the unit
     * @return the unit's current trip, or empty if it has none
     */
    public synchronized Optional<TripInfo> activeTripOf(String unitId) {
        return tx.execute(status -> assignments.findActiveByResourceId(unitId).map(a -> TripInfo.from(a)));
    }

    /**
     * The heartbeat. Spring calls this once a second, counted from the end of the previous run ({@code fixedDelay}),
     * so two ticks never overlap. Each tick:
     * <ol>
     *   <li>sends units to every waiting case it can serve, each case in its own transaction,</li>
     *   <li>takes back trips whose crew never acknowledged (one transaction),</li>
     *   <li>takes back trips whose unit went silent or stopped moving (one transaction).</li>
     * </ol>
     * Small transactions on purpose: if dispatching case #3 fails, cases #1 and #2 stay dispatched; #3 rolls back
     * and is tried again on the next tick. If a tick throws, Spring logs the error and still runs the next one.
     */
    @Scheduled(fixedDelay = 1000)
    public synchronized void tick() {
        boolean sent = true;
        while (sent) {
            sent = dispatchOne();
        }
        tx.executeWithoutResult(status -> timeoutMonitor.checkTimeouts());
        tx.executeWithoutResult(status -> tripMonitor.checkTrips());
    }

    /**
     * Sends a unit to the next waiting case, as one transaction: take the case off the queue, claim the unit, save
     * the trip, mark the case RESPONDING (main unit only). The coordinator only sends the unit; marking the case is
     * done here.
     *
     * @return {@code true} if a unit was sent (so try the next case); {@code false} if the queue is empty or no unit
     *         can be found for the next case
     */
    private boolean dispatchOne() {
        Boolean sent = tx.execute(status -> {
            Optional<Assignment> next = coordinator.dispatchNext();
            if (next.isEmpty()) {
                return false;
            }
            caseManager.onAssigned(next.get());
            return true;
        });
        // execute is declared as "may return null"; comparing this way avoids unboxing a null.
        return Boolean.TRUE.equals(sent);
    }

    /**
     * Looks up a trip, failing early with {@link NotFoundException} if it is unknown. The engine would throw
     * {@link IllegalStateException}, which the web layer reports as "409 conflict"; a missing trip should be
     * "404 not found".
     *
     * @param tripId the trip that must exist
     * @return the trip, so callers can check its status without a second lookup
     * @throws NotFoundException if it does not exist
     */
    private List<FacilityInfo> facilityInfos() {
        List<FacilityInfo> all = new ArrayList<>();
        for (Facility f : facilities.findAll()) {
            all.add(FacilityInfo.from(f));
        }
        return all;
    }

    private Facility requireFacility(String facilityId) {
        Optional<Facility> facility = facilities.findById(facilityId);
        if (facility.isEmpty()) {
            throw new NotFoundException("Facility " + facilityId + " not found");
        }
        return facility.get();
    }

    private Assignment requireTrip(String tripId) {
        Optional<Assignment> trip = assignments.findById(tripId);
        if (trip.isEmpty()) {
            throw new NotFoundException("Trip " + tripId + " not found");
        }
        return trip.get();
    }

    /**
     * Builds a {@link CaseInfo}, looking up the patient for the position. Every CaseInfo is made here, so none can
     * be missing its position.
     *
     * @param c the engine's case
     * @return the snapshot
     */
    private CaseInfo caseInfo(Case c) {
        // Safe: a case only exists for a registered patient (VitalsProcessor rejects unknown patients first).
        Patient patient = patients.findById(c.patientId()).orElseThrow();
        return CaseInfo.from(c, patient);
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
