# MediDispatch

Vitals-driven emergency dispatch (simulation only, synthetic data). Full plan: PLAN.md. Public overview: README.md.

## How to work with me
- I'm learning Java and Spring (LLD, HLD, scalable systems) by building this. Act as a tutor.
- Work in SMALL pieces (the pom, then one class or layer at a time), never a whole step's worth of files at once.
- Two speeds (PLAN.md section 10 tags every item):
  - (L) learn-deep, the default:
    1. Talk about what we build next and why, with a concrete example.
    2. Spec: every class needed, its methods, and which package it goes in.
    3. LLD lesson (principle, what goes wrong otherwise, a check-yourself question), plus **Decisions for me**.
    4. STOP. When I ask for code, show it in chat WITHOUT comments or Javadoc, then explain what it does.
       Showing code means no tool use at all (no drafting files, no compiling).
    5. When I say "write it", Claude writes the files WITH proper comments and Javadoc, writes/updates the tests
       and runs the suite. "Go ahead" after a setup step is NOT "write it".
    6. When all tests pass, I commit, then we go to the next piece.
  - (F) fast, only for items tagged (F): say so in one line and wait for my ok, then Claude writes the files and
    tests, runs them and walks me through them.
- Every main class gets Javadoc (class, constructors, public methods; @Bean methods too).
- Never commit; I commit. I create Maven modules myself (give me step-by-step module instructions).
- Explain every new Java/Spring/React feature in plain words. I'm not comfortable with `::` method references or
  streams yet, so use explicit lambdas and plain loops.
- Review my code honestly: point out real bugs first, then style.

## Decisions
- dispatch-core is pure Java: no Spring, JPA, Redis or Kafka. Hexagonal (ports and adapters).
- Priority comes from a RiskScorer (interface). SimpleScorer is a labelled placeholder, replace with NEWS2 later.
- Java 21, Maven multi-module. JUnit 5 for tests. Accessors are `x()` style, not `getX()`.
- Entities (Case, Resource, Patient) are classes with private state and guarded transitions; values are records.
- Always `save()` an entity after changing it, even though the in-memory adapters don't force it.
- Time comes in through a `java.time.Clock`, never `Instant.now()` inside engine code.
- Deadline: submission in the 2nd week of December 2026; feature work ends Dec 8. Build everything in PLAN.md,
  ordered, nothing cut.
- Application shape (details in PLAN.md section 2): modular monolith (one Spring Boot app, dispatch-service, split by
  package); Postgres + PostGIS + TimescaleDB in one server; Redis only for a measured need; one React + TypeScript
  (Vite) app with four faces (/control, /crew, /patient, /hospital) installable as a PWA; MapLibre + OSM; SSE for
  live screens; OTP behind an OtpSender port, then JWT.

- Events are published AFTER state is saved, collected and sent at the end of an operation. Never publish no-ops
  (same band / same status). The engine must not fail if publishing fails (EventPublisher must not throw);
  the real fix later is the outbox pattern.
- Constructor injection everywhere; engine classes import ports and domain only, never InMemory adapters.

## Built so far (1003 tests: 624 in dispatch-core, 95 in dispatch-sim, 22 in dispatch-sim-fx, 184 in dispatch-service,
78 in dispatch-routing)
Domain: VitalsReading, RiskBand, RiskAssessment, Case/CaseStatus, Resource/ResourceStatus/ResourceType
(AMBULANCE_ALS, AMBULANCE_BLS, NURSE, COURIER, DOCTOR; optional baseId = the facility it belongs to, optional
driver), Facility/FacilityKind/Capability (hospitals and clinics, see "Hospitals" below), Location (haversine),
Patient (optional PersonDetails and emergency Contact, see "People" below), Assignment (entity, with
AssignmentRole PRIMARY/SUPPORT) / AssignmentStatus (ASSIGNED, EN_ROUTE, ARRIVED, TRANSPORTING, DONE, CANCELLED,
TIMED_OUT, FAILED). CaseStatus has TRANSPORTING too (see "Hospitals").
Ports + in-memory adapters: CaseQueue, ResourceRepository, ResourceClaimer, PatientRepository, CaseRepository,
AssignmentRepository (findById, findActiveByCaseId -> List, findActiveByResourceId -> Optional, findActive()),
VitalsRepository (save, latestFor(patientId) = newest by timeStamp), EventPublisher.
In-memory adapters are built for endless runs: InMemoryVitalsRepository keeps ONLY the latest reading per patient
(the port only asks for that); InMemoryCaseRepository indexes patient -> open case id (checked against the live Case
on lookup); InMemoryEventPublisher has publishedSince(from) and size() so readers keep a bookmark instead of
re-reading the whole list. UnitTracker.onBackInService(unitId): OFFLINE -> AVAILABLE (ISE if not OFFLINE).
Events (package event): CaseStatusChanged, CaseBandChanged, UnitAssigned (carries the role), AssignmentStatusChanged,
AssignmentRoleChanged (PRIMARY -> SUPPORT on an upgrade).
Engine: VitalsProcessor + ProcessingResult, ResponseUpgrader, Dispatcher, CaseManager (incl. onAssignmentLost),
AssignmentManager (incl. timeOut, onFailed, demoteToSupport),
TimeoutMonitor, UnitTracker, TripMonitor, ResponseRecovery (incl. onPreempted), Preemptor, SupportDispatcher,
DispatchCoordinator, helpers SearchRings (2, 4, 8, 16 km... capped at the max radius) and UnitAssigner
(package-private: claim + ASSIGNED + save trip + publish UnitAssigned, shared by Dispatcher and SupportDispatcher).
Scoring: RiskScorer, SimpleScorer (placeholder).
Response (package response): ResponseDecider (interface: decide(case, patient) -> ResponsePlan), ResponsePlan (record:
primaryTypes + supportTypes, needsUnit(), wantsSupport(), allowedTypes() = primary only; 1-arg constructor = no
support), LadderResponseDecider (placeholder ladder).
All main classes have Javadoc (class, constructors, public methods; trivial accessors skipped). Keep it up to date
when code changes, and document new classes the same way.
Ports added: CaseQueue.peekNext(), ResourceRepository.findAssignedNear(center, radiusKm, types).
For live screens: ResourceRepository.findAll() (sorted by id, a copy) and CaseRepository.findOpen() (oldest first,
id breaks ties; the in-memory one walks the patient -> open case index, not every case, and re-checks isTerminal()).
Resource also tracks lastSeenAt / lastMovedAt via reportLocation(where, at); movement means more than 20 m from the
last counted position (movedFrom anchor), so GPS jitter is ignored and small steps add up.
MutableClock (a Clock that only moves with advance(Duration)) lives in dispatch-core src/main/.../engine on purpose:
both the tests and the simulator use it.
FullLifecycleTest proves: reading -> score -> case -> dispatch -> trip -> complete -> close, and the freed
ambulance serves the next patient, with the exact ordered event list. TimeoutMonitorTest proves reassignment:
nearest unit goes silent -> timed out, unit OFFLINE, case requeued -> next unit is sent.

Rules baked in: a unit whose trip TIMED_OUT or FAILED goes OFFLINE (not AVAILABLE) so it is never picked again.
Recovery for a lost unit always goes through ResponseRecovery (assignment first, then case) so callers cannot
forget the second step.

Constructor shapes:
- CaseManager(cases, queue, publisher, clock)
- Dispatcher(queue, patients, resources, claimer, assignments, decider, publisher, clock, searchRadiusKm)
- AssignmentManager(assignments, resources, claimer, publisher, clock)
- ResponseRecovery(assignmentManager, caseManager)
- TimeoutMonitor(assignments, recovery, clock, ackTimeout)
- UnitTracker(resources, clock)
- TripMonitor(assignments, resources, recovery, clock, signalTimeout, stallTimeout)
- Preemptor(assignments, cases, resources, patients, decider, recovery, searchRadiusKm)   (no claimer any more)
- SupportDispatcher(cases, patients, resources, claimer, assignments, decider, publisher, clock)
- DispatchCoordinator(dispatcher, preemptor, supportDispatcher, queue)
- Assignment(caseId, resourceId, role, assignedAt); UnitAssigned(assignmentId, caseId, resourceId, role, occurredAt)
- VitalsProcessor(patients, cases, vitals, scorer, caseManager, upgrader, clock, maxAge)
- ResponseUpgrader(assignments, resources, patients, decider, assignmentManager, caseManager)
- Tests build deciders with `new LadderResponseDecider()`.
- AssignmentManager and ResponseRecovery methods take an ASSIGNMENT id (not a case id); AssignmentManager methods
  return the updated Assignment. Tests that only know the case use a per-file helper tripOf(caseId).

## Last step: onFailed + ResponseRecovery (DONE, tested)
FAILED status, AssignmentManager.onFailed, ResponseRecovery (onFailed, onTimedOut), TimeoutMonitor uses it.
Tests: AssignmentStatusTest updated for TIMED_OUT/FAILED, onFailed tests in AssignmentManagerTest, ResponseRecoveryTest
(stages, breakdown scenario, retry-loop guard, priority kept, events, refused calls change nothing, known-limit test).
ResponseRecoveryTest.knownLimit... documents that recovery is two non-atomic steps; rewrite it if recovery becomes atomic.

Known gaps and small cleanups are all listed in BACKLOG.md (keep it up to date; add anything new the simulator finds).

## Running tests
mvn is not on PATH, but the Maven wrapper's download works offline (Spring Boot 4.1.1 is cached in ~/.m2):
`export JAVA_HOME="/c/Program Files/JetBrains/IntelliJ IDEA 2026.2.0.1/jbr"`, then from C:/Projects/medidispatch run
`~/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn -o -q test`
(one module: `-pl dispatch-service -am`; one class: `-Dtest=X -Dsurefire.failIfNoSpecifiedTests=false`), then read
`<module>/target/surefire-reports/*.xml`. The Mockito self-attach warning is harmless.
SchemaTest (and later every database test) needs Docker Desktop running; images timescale/timescaledb-ha:pg17 and
testcontainers/ryuk are cached. A newly added dependency needs one run without -o to download it.
Older way, still works for the non-Spring modules: compile and run the suite with IntelliJ's bundled JDK
("C:/Program Files/JetBrains/IntelliJ IDEA 2026.2.0.1/jbr/bin" javac/java) plus the JUnit 5.11.4 / platform 1.11.4
jars from ~/.m2, building into its scratchpad (nothing written to the project). Classpaths need Windows paths (cygpath -w).
Compile order: core main, core test, sim main (cp core main), sim test, fx main (cp core + sim main + JavaFX 21.0.5
-win jars from ~/.m2/repository/org/openjfx; copy src/main/resources into the output), fx test; run with a small JUnit
launcher over package com.medidispatch. The window can be opened briefly with
`timeout 25 java --enable-native-access=ALL-UNNAMED -cp ... com.medidispatch.sim.fx.SimulatorLauncher` (it pops up on
the user's screen). The user runs things from IntelliJ (Ctrl twice -> mvn test from the root medidispatch project).

## Live trip monitoring (DONE, tested)
UnitTracker records position reports (facts only); TripMonitor judges EN_ROUTE trips: silent for signalTimeout (30 s
default) or not moving for stallTimeout (120 s default) -> recovery.onFailed. Both are measured from the later of the
last report/move and assignedAt, so old data from before the trip is ignored. ASSIGNED trips are TimeoutMonitor's job;
ARRIVED trips may stand still. Failure reason (signal vs stall) is not recorded yet (a small enum could be added for
the simulator). Overdue-arrival checks would need an EtaCalculator (not built).

## Preemption + ring search (DONE, tested)
DispatchCoordinator.dispatchNext(): Dispatcher first (free unit); if none, peekNext() the stuck case, Preemptor.makeRoomFor
frees a unit, then Dispatcher again. Callers still call caseManager.onAssigned(assignment) afterwards.
Preemptor: searches rings around the waiting patient with resources.findAssignedNear (nearest ring wins); victim = trip
ASSIGNED or EN_ROUTE (never ARRIVED), case band STRICTLY lower than the waiting case, unit type allowed for the waiting
band. Inside a ring: lowest band first, then nearest, then trip id. Taken trip is CANCELLED, unit goes back to
AVAILABLE (healthy, not OFFLINE), old case requeued keeping its openedAt. A lower band can hold an ALS unit when the
case improves while responding (CaseManager updates the band only), which is how the tests build that case.
Dispatcher also searches in SearchRings (2, 4, 8, 16 km... up to searchRadiusKm) instead of one big query. The in-memory
repository still scans every unit; the ring logic pays off with real spatial indexes (Redis GEO / PostGIS).
Known limits: between the cancel and the second dispatchNext another thread could take the freed unit (single dispatch
loop for now; real fix = hand the unit over directly inside one transaction). Low-urgency cases can starve.
No failure/cancel reason is recorded (preempted vs cancelled by a person).

## Response ladder (DONE, tested)
Dispatcher asks decider.decide(case, patient) for a ResponsePlan, then tries each unit type IN PLAN ORDER, each with
the full ring search; a later type is only tried if no unit of an earlier type can be claimed within the radius
(type-first, chosen to keep ALS free). Ladder: CRITICAL [ALS] (no BLS fallback, preemption frees an ALS instead);
HIGH [BLS, ALS]; MEDIUM [NURSE]; LOW [] (no unit). A queued case whose plan needs no unit -> case put back +
IllegalStateException. Preemptor uses plan.allowedTypes(). Later option: "use ALS if the BLS is more than X km further".

## First responders (DONE, tested), the user's idea
Phase A (refactor, no behaviour change): trips are addressed by assignment id everywhere (AssignmentManager,
ResponseRecovery, both monitors); Preemptor finds a busy unit's trip via findActiveByResourceId.
Phase B: CRITICAL plan = primary [ALS], support [DOCTOR, NURSE]; other bands no support.
DispatchCoordinator.dispatchNext() sends the primary (free unit, else preempt, retry), then
SupportDispatcher.sendSupportFor(primary), and returns ONLY the primary. Callers call caseManager.onAssigned(primary)
only; a SUPPORT trip never changes the case status. Support rules: plan.wantsSupport(); at most one active SUPPORT
trip per case (a replacement ambulance brings no second responder); responder must be STRICTLY closer (straight
line) than the primary unit; support types tried in plan order, ring search capped at the primary's distance;
best-effort: never queues, never preempts, finding nobody is normal. ResponseRecovery: losing a SUPPORT trip ends
only that trip (case not requeued); losing the PRIMARY requeues the case and the responder keeps going.
AssignmentManager.onArrived(PRIMARY) stands down the case's SUPPORT trip: ARRIVED -> DONE, otherwise CANCELLED;
the unit is freed. Dispatcher alone (dispatchNext) sends the primary only (older tests use it directly).
Known limits: "closer" is straight-line (no EtaCalculator); support is only considered when the primary is sent
(a responder freed a minute later is not sent); first-responder role not recorded in AssignmentStatusChanged.
Open: COMMUNITY_RESPONDER (volunteers) and support for HIGH cases were deferred.

## VitalsProcessor (DONE, tested)
process(reading) checks in order, first failure wins and changes NOTHING (not stored, not scored, no event):
UNKNOWN_PATIENT; FROM_THE_FUTURE (more than FUTURE_TOLERANCE = 1 min ahead of the clock); TOO_OLD (older than maxAge,
10 min in tests); DUPLICATE (same timeStamp as the patient's latest stored reading); OUT_OF_ORDER (older than it).
Both time boundaries count as usable. Accepted: vitals.save -> scorer.assess -> caseManager.handle ->
ProcessingResult.accepted(assessment, case). Rejections are returned (ProcessingResult.Outcome), never thrown; only a
null reading throws. It never dispatches (single dispatch loop = DispatchCoordinator). Out-of-order readings are
dropped, not stored. No VitalsRejected event (the Spring layer logs/counts from the result).
Engine entry points now: VitalsProcessor (readings in), DispatchCoordinator (dispatch loop), UnitTracker and
AssignmentManager (unit reports, by assignment id), TimeoutMonitor and TripMonitor (scheduled checks).

## Simulator: dispatch-sim module (console DONE, tested)
Maven module dispatch-sim (package com.medidispatch.sim), depends on dispatch-core. Simulation is the composition root:
wires the whole engine with in-memory adapters + one MutableClock, builds the city from one Random(seed): 50 patients
(30% fall ill between minute 5 and 40 min before the end; peak MEDIUM 0.53 / HIGH 0.56 / CRITICAL 1.0, tuned to
SimpleScorer and locked by tests; 10-minute ramp; treat() on primary arrival), 4 ALS, 6 BLS, 6 nurses, 2 doctors in a
20x20 km box around Bangalore. Each unit has its own Random for per-trip Behaviour (NORMAL, NEVER_ACKNOWLEDGES,
BREAKS_DOWN halfway (keeps reporting -> stall check), LOSES_SIGNAL halfway (stops reporting -> signal check)).
step() = 10 s: readings every 60 s via VitalsProcessor; dispatchNext() until empty (+ caseManager.onAssigned);
read NEW events (UnitAssigned -> give unit its trip; terminal AssignmentStatusChanged -> free unit); move units
(skip if trip already terminal; ACK -> onEnRoute, position -> UnitTracker, ARRIVED -> onArrived, PRIMARY treats
patient); after 10 min on scene complete + onStabilised + close; then both monitors. run() = step until finished.
MetricsCollector builds SimulationReport from events (+ worst band per case from ProcessingResults).
Engine settings in the sim: ack 60 s, signal 30 s, stall 120 s, radius 20 km, maxAge 10 min.
Known sim limits: crews effectively answer after 10 s (first tick in the same step as the assignment); same seed
gives the same city but engine ids are random UUIDs, so runs can differ slightly (BACKLOG).
Report also counts responses upgraded; firstRespondersSent includes units demoted to first responder; "reassigned"
excludes upgrades (only cases that LOST a unit).

## Response upgrade (DONE, tested), found by the simulator
Problem: a gradually deteriorating patient first hits MEDIUM -> nurse sent; a minute later CRITICAL, but RESPONDING
cases only got a band update, so the nurse stayed the only help. Fix: VitalsProcessor copies the open case's band
BEFORE caseManager.handle (same Case object is changed in place), then calls
ResponseUpgrader.upgradeIfWorse(case, previousBand). It acts only if the band went UP (edge-triggered; a patient who
improves keeps the stronger unit) and the case is RESPONDING, and the active PRIMARY trip's unit type is NOT in the new
plan's primaryTypes: then assignmentManager.demoteToSupport(trip) (Assignment.demoteToSupport: PRIMARY -> SUPPORT once,
not if ended; publishes AssignmentRoleChanged; unit keeps driving) and caseManager.onAssignmentLost(case) (requeued,
keeps its openedAt). The dispatch loop then sends a proper unit; SupportDispatcher sends no extra responder (one SUPPORT
already active); when the new primary arrives, the demoted unit stands down (DONE if arrived, else CANCELLED).
Only RESPONDING cases for now (STABILISED getting worse could reuse it later). In the sim: about 6 upgrades per run,
demoted nurses arrive minutes before the ambulance.

## Simulator window: dispatch-sim-fx module (DONE, tested), package com.medidispatch.sim.fx
Maven module depending on dispatch-sim (dispatch-core comes transitively) + org.openjfx:javafx-controls 21.0.5 +
javafx-maven-plugin 0.0.8 (mainClass SimulatorLauncher). Classpath mode on purpose: NO module-info.java (the user first
created the module with IntelliJ's JavaFX generator; its module-info/FXML/mvnw files were removed).
dispatch-sim side: Simulation.snapshot() -> WorldSnapshot (public record: time, CityBounds, PatientView(id, home,
Optional band of open case), UnitView(id, type, position, UnitState, Optional target, Optional role), waitingCases,
recentEvents (EventLog: readable lines, newest first, last 20, routine events left out), report, finished).
UnitState: FREE, ANSWERING, DRIVING, ON_SCENE, BROKEN_DOWN, NO_SIGNAL, OFFLINE (engine OFFLINE wins).
fx side (now shows the LIVE simulation): SimulatorLauncher (plain main -> Application.launch; run THIS, not
SimulatorApp), SimulatorApp (BorderPane: MapView centre, SidePanel in a ScrollPane right, dark theme by default;
new LiveSimulation(LiveConfig.bigCity()).start(); AnimationTimer on the JavaFX thread only reads latestSnapshot() and
redraws when it is a new object, never steps; Application.stop() -> live.stop()), MapView (resizable Pane + Canvas:
drag to pan, scroll to zoom 1-8x, double-click resets, click selects, critical patients pulse; layers: grid, dashed
trip lines, patients by band colour, units by type shape, selection ring, legend), SidePanel(initialSpeed, start)
(clock "Day N HH:mm:ss", Play/Pause, Report button swapping the event log and the live report TextArea, speed
ToggleGroup 10/60/300x, Light/Dark + Reset view, Selected details, counters; buttons call callbacks set by the app).
Pure logic, unit-tested ("humble object"): MapProjection, Viewport (zoom/pan maths), Selection (describe(snapshot,
id)), Theme (LIGHT/DARK colours, cssClass, other()). simulator.css has .root.light / .root.dark. TickPacer was deleted
(the window no longer steps a simulation).
Harmless warnings when running: "Unsupported JavaFX configuration: unnamed module" (classpath mode) and native access /
sun.misc.Unsafe warnings from inside JavaFX 21 (VM option --enable-native-access=ALL-UNNAMED hides one).

## Live simulator (DONE, tested), dispatch-sim, package com.medidispatch.sim
Endless, random, threaded; the step-by-step Simulation stays for tests and SimulatorMain (both wire the engine through
EngineParts, the shared composition root: package-private final fields queue, cases, patients, resources, assignments,
events, caseManager, assignmentManager, coordinator, unitTracker, timeoutMonitor, tripMonitor, processor; constants
ack 60 s, signal 30 s, stall 120 s, maxAge 10 min). MetricsCollector is incremental: onReading(result), onEvent(e),
report(simulatedDuration). CityMap has randomPoint(random, sizeKm) and bounds(sizeKm).
- LiveConfig (public record): seed (layout only), citySizeKm, patientCount, als/bls/nurse/doctorCount,
  illnessesPerHour, noAck/breakdown/signalLossChance, repairTime, startSpeed. bigCity() = 40 km, 200 patients,
  16/24/20/8, 8 per hour, 0.05 each, 20 min, speed 60, random seed. searchRadiusKm() = max(20, 0.6 x size);
  meanHoursBetweenIllnesses() = patients / illnessesPerHour.
- ScaledClock (extends Clock, all synchronized): anchor (simulated, real nanos) + speed; pause/resume/setSpeed move
  the anchor so time never jumps; LongSupplier realNanos injectable for tests.
- LiveEngine: owns EngineParts on ONE daemon thread "engine" (single-thread ScheduledExecutorService) = thread
  confinement, no locks on the engine. Jobs from other threads: reading, answered, position, arrived, backInService
  (each = execute(job)); stale messages are ignored by checking the trip status first (answered only if ASSIGNED,
  arrived only if EN_ROUTE). Tick every BEAT = 25 ms real: dispatchAll (+ onAssigned), finish crews 10 min on scene
  (complete, onStabilised, close), both monitors, read new events with a bookmark (log + metrics + TripOrders to
  units: UnitAssigned -> START(target, role), AssignmentRoleChanged -> ROLE, terminal AssignmentStatusChanged -> END
  (final status)), publish an immutable WorldSnapshot in a volatile field (unit OFFLINE in the engine wins).
  guarded(work) catches + counts + prints exceptions (a periodic task that throws is silently cancelled otherwise);
  errors() must stay 0 in tests. Rejected jobs after stop are dropped.
- LivePatient (Runnable, synchronized): exponential wait to the next illness (Poisson process), random peak 30/30/40%
  MEDIUM/HIGH/CRITICAL (a SimulatedPatient episode), a reading every 60 simulated s (first ones spread over a minute);
  treat() (called by the engine thread on PRIMARY arrival) -> healthy, next illness >= 2 h + exponential wait later.
- LiveUnit (Runnable, synchronized): wraps SimulatedUnit; ConcurrentLinkedQueue<TripOrder> inbox; per-trip random
  Behaviour; on END with TIMED_OUT/FAILED -> out of service for repairTime, then position + backInService; publishes a
  volatile UnitView; nothing moves while the clock is paused (elapsed = 0).
- LiveSimulation (public): MAX_SPEED 300, START 2026-01-05T08:00Z, 4 daemon "agents-N" threads; every patient and unit
  is its own scheduleWithFixedDelay task (random first delay); constructor builds the city with the clock paused and
  publishes a first snapshot; start() (once, else ISE), stop() (shutdownNow + await), pause, resume, setSpeed
  (1..300), speed, paused, startTime, latestSnapshot. Package-private engine() and threadsStopped() for tests.
- Tests: ScaledClockTest (fake nanos), LiveSimulationTest (real threads at 300x, waits up to 8 s per condition).
Known limit: the in-memory event list grows forever (ponytail comment in LiveEngine; a few MB per simulated day).
Speeds above 300x would make 25 ms beats too coarse for the 30 s signal timeout.

## dispatch-service (IN PROGRESS), package com.medidispatch.service
Maven module (parent medidispatch). pom: imports the spring-boot-dependencies 4.1.1 BOM in dependencyManagement
(scope import, type pom) so our own parent stays the parent; deps dispatch-core, spring-boot-starter-webmvc,
spring-boot-starter-webmvc-test (test); maven.compiler.parameters=true; spring-boot-maven-plugin with the version
written out (plugins are not managed by the BOM) and the repackage goal.
- DispatchServiceApplication: @SpringBootApplication + @EnableScheduling.
- EngineConfig (@Configuration): the composition root. Package-private @Bean methods build every port (in-memory
  adapters for now), policy (LadderResponseDecider, SimpleScorer) and engine part with `new`; Spring passes beans in
  as parameters, each built once. Constants ACK_TIMEOUT 60 s, SIGNAL_TIMEOUT 30 s, STALL_TIMEOUT 120 s,
  MAX_READING_AGE 10 min, SEARCH_RADIUS_KM 20. Swap the port beans here for the Postgres adapters.
- DispatchService (@Service): the single front door; every public method is synchronized on it (one global lock,
  reads included) AND runs its body in tx.execute / tx.executeWithoutResult (TransactionTemplate): lock, BEGIN, work,
  COMMIT, unlock (see "Database adapters" for why not @Transactional; ponytail: global lock, drop it + optimistic
  locking for several instances). registerPatient / registerUnit (ISE if the id exists),
  submitReading -> ReadingResult(outcome, CaseInfo or null), acceptTrip (onEnRoute), reportPosition, arrive
  (onArrived), completeTrip (complete; PRIMARY also onStabilised + close), backInService, findCase, openCaseOf,
  findUnit, findTrip, activeTripOf. @Scheduled(fixedDelay = 1000) tick(): dispatchAll (dispatchNext until empty, then
  caseManager.onAssigned), checkTimeouts, checkTrips. Unknown trip/unit ids -> NotFoundException (404); the
  engine's ISE means a wrong state (409). requireTrip returns the Assignment.
  Idempotent steps (weak networks): acceptTrip / arrive / completeTrip return the trip unchanged if it is ALREADY in
  the target state (EN_ROUTE / ARRIVED / DONE); any other state goes to the engine, which refuses it (409). The engine
  stays strict on purpose: leniency for retries lives at the front door. completeTrip must return early, or a repeat
  would close the case twice.
- Records CaseInfo (includes the patient's latitude/longitude: from(case, patient); every CaseInfo is built by the
  private caseInfo(c) helper, which looks the patient up), TripInfo (engine "assignment"/"resource" become
  "trip"/"unit" outside the engine), UnitInfo (lat/long flattened, lastSeenAt null if never), ReadingResult; built
  inside the lock with static from(...).
- snapshot(): synchronized, copies findAll units, findOpen cases and findActive trips into a LiveSnapshot(at, units,
  openCases, activeTrips) (compact constructor takes List.copyOf, so it is truly immutable and safe to hand to the
  feed thread). No I/O inside the lock. DispatchService now also takes the Clock (timestamps the snapshot).
- Tests: EngineConfigTest (@SpringBootTest: engine built, beans shared), DispatchServiceTest (since 3d:
  @SpringBootTest + @Import(TestDatabaseConfig) on the app database, TestDatabase.resetApp() before each test; the
  background loop also ticks, tests still call tick() by hand, same lock so either dispatches: full happy path, duplicate ids, unknown ids, arrive before accept, 50 threads + a ticking thread ->
  exactly 10 units busy and 10 cases RESPONDING, aRepeatedStepIsHarmless, goingBackwardsIsStillRefused,
  aSnapshotShowsTheFleetTheOpenCasesAndTheTrips).

## dispatch-service REST API (DONE, tested), package com.medidispatch.service.api
Controllers only translate HTTP <-> DispatchService calls; no logic. Decisions taken: repeated steps -> 200; readings
always 200 with the outcome (DUPLICATE, TOO_OLD... are not errors, so devices don't retry); unit without a trip -> 204.
- PatientController (/api): POST /patients (201; body PatientRequest), GET /patients/{id} (PatientInfo, with the
  emergency contact; ponytail: personal data with no login yet), GET /patients/{id}/case, POST /readings (body is
  the domain VitalsReading record itself; 200 + ReadingResult), GET /cases/{id}.
- UnitController (/api/units): POST (201 + UnitInfo; body UnitRequest; baseId and driver required, unknown base -> 400), GET /{id}, GET /{id}/trip (200 or 204; 404 for
  an unknown unit), PUT /{id}/position (204; body Coordinates; PUT = idempotent by design), POST /{id}/back-in-service.
- TripController (/api/trips): GET /{id}, POST /{id}/accept, /arrive, /transport, /complete (action URLs, not "set
  the status"). /transport body TransportRequest {"needs": [...]}, @RequestBody(required = false): no body or null
  needs = nothing special.
- FacilityController (/api/facilities): POST (201 + FacilityInfo; body FacilityRequest; duplicate id 409, accepting
  clinic / unknown capability or kind 400), GET (all, sorted by id), GET /{id}, PUT /{id}/accepting (body
  AcceptingRequest {"accepting": false}; PUT = sets a value, repeat-safe; clinic -> true is 400). ponytail: no login,
  any caller acts for any hospital (BACKLOG).
- Request records: Coordinates(Double latitude, Double longitude) + toLocation() (NOT Location as a body: missing
  double fields silently become 0.0; boxed Double + null check refuses them), PatientRequest(id, Coordinates home,
  name, Integer age, sex, Contact emergencyContact): name/age/sex required (checked with new PersonDetails(...) in
  the compact constructor), contact optional; toPatient(). UnitRequest(id, ResourceType type, Coordinates position,
  String baseId, Contact driver): baseId and driver REQUIRED (every unit has an owner). Compact constructors refuse
  a missing position; blank ids and missing types are refused by the engine's own constructors. FacilityRequest(id,
  name, kind, position, capabilities, Boolean accepting, String phone): kind, position and phone required, missing capabilities = none, missing
  accepting = (kind == HOSPITAL); toFacility() lets the Facility record check the rest. AcceptingRequest(Boolean):
  required (guessing could misdirect ambulances). TransportRequest(Set<Capability> needs): null -> Set.of().
- ApiErrors (@RestControllerAdvice, returns Spring's ProblemDetail, RFC 9457): NotFoundException 404,
  IllegalStateException 409 (ponytail: a real bug throwing ISE would show as 409), IllegalArgumentException 400,
  HttpMessageNotReadableException 400 (detail = getMostSpecificCause().getMessage(); covers broken JSON, unknown enum
  values and record constructors that throw while Jackson reads the body).
- Lookups in controllers use .orElseThrow(() -> new NotFoundException(...)) so every 404 has a ProblemDetail body.
- ApiTest (@SpringBootTest + @AutoConfigureMockMvc from org.springframework.boot.webmvc.test.autoconfigure; MockMvc,
  com.jayway.jsonpath.JsonPath to read ids out of answers). All tests share one context and the real scheduled tick
  runs, so every test uses its own ids, and the only dispatching test works in Mumbai (far outside the 20 km radius of
  the Delhi tests) so tests cannot steal each other's units. Tests call service.tick() instead of waiting.
- Skipped: Location header on 201; Idempotency-Key store (add for SOS if one open case per patient is not enough).
- Spring Boot 4 notes: Jackson 3 (package tools.jackson), Instants are ISO strings in JSON.

## Live feed (DONE, tested), package com.medidispatch.service.live
LiveFeed (@RestController): GET /api/live (text/event-stream) returns an SseEmitter (30 min timeout; EventSource
reconnects by itself); onCompletion/onTimeout/onError remove it from a CopyOnWriteArrayList; the first snapshot is
sent at once. @Scheduled(fixedDelay = 1000) broadcast(): nothing if no screens; service.snapshot() (lock held only
while copying), THEN send "event:snapshot / data:{json}" to each screen; IOException or IllegalStateException -> drop
that screen. Rule: never do network I/O while holding the DispatchService lock.
Decisions: full snapshot every second (self-healing, no event ids/replay/heartbeats; ponytail: ~10 KB/s per screen,
switch to deltas + snapshot on connect at hundreds of units on weak links); one feed for control room and hospital;
crew phones keep polling GET /api/units/{id}/trip (Web Push later). ponytail: one send thread, a frozen screen delays
everyone's update.
- application.properties: spring.task.scheduling.pool.size=2 (default is ONE thread for all @Scheduled methods, so a
  stuck send would delay the dispatch tick).
- closeAll() listens for ContextClosedEvent and completes every emitter: Boot shuts down gracefully (waits up to 30 s
  for requests in progress) and an SSE stream never ends, so without it every shutdown hung 30 s (surefire printed
  "kill self fork JVM").
- LiveFeedTest: RANDOM_PORT (real Tomcat), @LocalServerPort (org.springframework.boot.test.web.server), java.net.http
  HttpClient with BodyHandlers.ofInputStream, reads lines as they arrive: "event:snapshot", "data:..." (Spring writes
  no space after the colon); registers a unit and waits for it in a later message; @Timeout(10).
- Watching it by hand on Windows: `curl.exe -N http://localhost:8080/api/live` (plain `curl` in Windows PowerShell is
  Invoke-WebRequest, which waits for the response to end).

## Database schema (DONE, tested)
- docker-compose.yml (root): one service `db`, image timescale/timescaledb-ha:pg17 (Postgres 17 + PostGIS +
  TimescaleDB), db/user/password all `medidispatch` (ponytail: plain-text dev password), laptop port 5433 (a Windows Postgres service owns 5432 here; container port 5432), named volume
  dbdata mounted at /home/postgres/pgdata/data (this image's data dir, NOT /var/lib/postgresql/data).
- dispatch-service/src/main/resources/db/migration/V1__init.sql (Flyway; never edit after it has run, add V2...).
  V2__hospitals.sql: see "Hospitals" below.
  Conventions: text ids; enums as text + CHECK; geography(Point, 4326), LONGITUDE FIRST (ST_Point(lon, lat, 4326)),
  metres; timestamptz everywhere (driver maps it to OffsetDateTime: instant.atOffset(ZoneOffset.UTC)).
  Tables: patients(id, home); cases(id, patient_id, status, band, opened_at, queued) with partial unique index
  one_open_case_per_patient (status NOT IN CLOSED/CANCELLED; also serves findOpen) and partial index queued_cases
  (opened_at) WHERE queued (the CaseQueue is this flag; order = band CRITICAL first, then opened_at, then id, like
  InMemoryCaseQueue); units(id, type, status, position, moved_from, last_seen_at, last_moved_at, claimed_by -> cases)
  with GiST index on position; assignments(id, case_id, unit_id, role, status, assigned_at) with index on case_id and
  partial unique index one_active_trip_per_unit (ASSIGNED/EN_ROUTE/ARRIVED); vitals hypertable (1-day chunks),
  nullable measurements, PRIMARY KEY (patient_id, at) = Timescale's rule + duplicate detector + latestFor.
  The claim: UPDATE units SET claimed_by = :case WHERE id = :unit AND (claimed_by IS NULL OR claimed_by = :case)
  (1 row = won); release: SET claimed_by = NULL WHERE id = ? AND claimed_by = ?.
- Decisions: one image; text + CHECK; claimed_by column (not a claims table); queued flag; vitals compression /
  retention / continuous aggregates later in their own migration (ponytail note in V1); NO version columns
  (optimistic locking) while DispatchService's one lock serialises writes: add them, with a two-thread test that loses
  an update first, when the lock goes. Adapters should update only the columns that change.
- pom (dispatch-service): flyway-core, flyway-database-postgresql, postgresql, testcontainers-postgresql, all TEST
  scope for now (versions from Boot's BOM: Flyway 12.4.0, driver 42.7.13, Testcontainers 2.0.5; TC 2 class is
  org.testcontainers.postgresql.PostgreSQLContainer, not generic). The app itself does not touch the database yet.
- SchemaTest (service.persistence, 8 tests, plain JDBC, no Spring): uses the shared TestDatabase (see adapters
  below), tables emptied before each test.
  Race test: connection a claims inside a transaction, b (other thread) is still waiting after 300 ms, a commits,
  b gets 0 rows. Also: re-claim/release semantics, one open case (23505), one active trip (23505), CHECK (23514),
  duplicate reading (23505) + latest, hypertable listed in timescaledb_information.hypertables, ST_DWithin 5 km finds
  the 2 km unit not the 20 km one, ST_Distance about 2000 m. Harmless Flyway warning: extension "timescaledb" already
  exists (this image pre-creates it).

## Database adapters (DONE: the engine runs on Postgres), package com.medidispatch.service.persistence
Pieces (re-ordered because the JPA starter makes Boot connect to the database at startup): 3a (DONE) patients, units,
claimer on JDBC. 3b (DONE) the app connects + migrates at startup, trips on JPA. 3c (DONE): cases, queue (FOR UPDATE
SKIP LOCKED), vitals on JDBC (F). Every port now has a database adapter (EventPublisher stays in memory by decision).
3d (DONE): EngineConfig builds the adapters, each DispatchService call one transaction INSIDE the lock (L).
Decisions: JdbcClient + hand-written SQL for the engine's ports; JPA (separate TripEntity + mapper, Spring Data
method-name queries, saveAndFlush so SQL runs at save() like JDBC, ddl-auto=validate because Flyway owns the schema)
only for AssignmentRepository and later users/roles; static restore(...) in core; service always on Postgres (in-memory
adapters stay for core tests and the simulator); one Testcontainers test class per adapter + the whole Spring suite on
Postgres in 3c (no shared contract test-jar); TransactionTemplate inside the synchronized methods (NOT @Transactional on
top: the proxy would commit after the lock is released, and the next thread would read stale data); EventPublisher
stays in memory (outbox when something outside listens).
Rules: each column has ONE owning adapter (cases.queued -> queue, units.claimed_by -> claimer; the repositories never
write them: CaseManager enqueues BEFORE it saves the case). The engine calls save() after every change (checked:
CaseManager, AssignmentManager, UnitAssigner, UnitTracker), which is what makes a database adapter possible.
- Core: Case.restore(id, patientId, status, band, openedAt), Resource.restore(id, type, status, location, movedFrom,
  lastSeenAt, lastMovedAt) (null times = never), Assignment.restore(id, caseId, resourceId, role, status, assignedAt)
  (Assignment now has one private constructor with all checks; the public one passes a random UUID and ASSIGNED);
  Resource.movedFrom() getter added. Restored objects obey the state machines as usual (tested).
- pom (since 3b): spring-boot-starter-data-jpa (Hibernate, Spring Data JPA, JDBC/JdbcClient, HikariCP),
  spring-boot-starter-flyway (Boot 4 needs the starter: flyway-core alone no longer runs at startup),
  flyway-database-postgresql, postgresql (runtime); test: spring-boot-starter-data-jpa-test, testcontainers-postgresql.
  (3a used plain spring-jdbc so Boot would not create a DataSource yet; replaced.)
- application.properties: spring.datasource.url/username/password = ${DB_URL:...}/${DB_USERNAME:medidispatch}/
  ${DB_PASSWORD:medidispatch} (env vars, local compose defaults; ponytail: drop the defaults with the full-stack
  compose, flagged by a security review), spring.jpa.hibernate.ddl-auto=validate, spring.jpa.open-in-view=false.
  Running the app now needs `docker compose up -d` first (connection refused otherwise).
- DbTime: Instant <-> OffsetDateTime at UTC (the driver's type for timestamptz), null stays null; Postgres keeps
  microseconds.
- JdbcPatientRepository: upsert (INSERT ... ON CONFLICT (id) DO UPDATE SET home = excluded.home); reads ST_Y = lat,
  ST_X = lon (::geometry needed).
- JdbcResourceRepository: upsert of every column EXCEPT claimed_by; nullable times bound with
  Types.TIMESTAMP_WITH_TIMEZONE (a null alone has no type); findAvailableNear / findAssignedNear: validates like in
  memory, empty type set returns early (a list param expands to IN (?, ?, ...), empty = invalid SQL), ST_DWithin(...,
  metres, false) + ORDER BY ST_Distance(..., false), id: false = sphere, same maths as Location.distanceKmTo, so both
  adapters agree; findAll ORDER BY id.
- JdbcResourceClaimer: tryClaim = UPDATE ... WHERE id AND (claimed_by IS NULL OR = case), rows == 1; release only by
  the holder; claimedBy selects WHERE claimed_by IS NOT NULL (no row = empty). Difference from memory: a unit that
  does not exist cannot be claimed (false). claimed_by references cases, so the case must be saved first.
- Tests: TestDatabase (test folder) = ONE container for the whole run (started on first use, Flyway once, Ryuk removes
  it at JVM end), DriverManagerDataSource (new connection per request, no pool), jdbc(), reset() = TRUNCATE all five
  tables, openCase(id) inserts patient "p-" + id and an ALERTED case. Every database test calls reset() in
  @BeforeEach. JdbcPatientRepositoryTest (5), JdbcResourceRepositoryTest (15: every field round-trips, null = never,
  saving leaves the claim alone, radius/status/type filters, nearest then id, assigned search, findAll, and
  givesTheSameAnswersAsTheInMemoryRepository: 60 random units, seed 42, same ids in the same order from 4 centers),
  JdbcResourceClaimerTest (11, including 20 threads on separate connections, exactly one wins).
- JPA (3b): TripEntity (@Entity @Table("assignments"), no @Column: Boot's naming turns caseId into case_id; unitId =
  domain resourceId; @Enumerated(STRING); Instant -> timestamptz by itself; protected no-arg constructor, not final;
  static from(Assignment) / toAssignment() via Assignment.restore). TripJpaRepository (public interface extends
  JpaRepository<TripEntity, String>, derived queries findByCaseIdAndStatusInOrderByAssignedAtAscIdAsc,
  findByUnitIdAndStatusIn (Optional: the DB index allows at most one), findByStatusInOrderByAssignedAtAscIdAsc).
  JpaAssignmentRepository (public, implements the port): saveAndFlush (SQL at save(), like JDBC; a second active trip
  for a unit throws DataIntegrityViolationException at that line), maps to domain at once (no managed entity leaves),
  ACTIVE = statuses with !isTerminal(), findActive now ordered assigned_at then id. ponytail: domain-chosen ids, so
  every save is SELECT then INSERT/UPDATE (merge); Persistable if it matters.
- Test wiring (3b): TestDatabaseConfig (public @TestConfiguration(proxyBeanMethods = false)) with a
  DynamicPropertyRegistrar bean setting spring.datasource.* from TestDatabase.url()/username()/password() (each first
  starts + migrates the container): ONE container for plain and Spring tests (no @ServiceConnection, which would start
  a second). EVERY Spring test that starts Boot needs @Import(TestDatabaseConfig.class) (ApiTest, LiveFeedTest,
  EngineConfigTest; forgetting it = "Failed to load ApplicationContext"). Spring's Flyway re-runs at context start and
  finds V1 applied. DispatchServiceTest (AnnotationConfigApplicationContext, no Boot) is unaffected.
  TestDatabase.openCase(JdbcClient, id) inserts through a given client.
- JpaAssignmentRepositoryTest (23): @DataJpaTest (org.springframework.boot.data.jpa.test.autoconfigure) +
  @AutoConfigureTestDatabase(replace = NONE) (org.springframework.boot.jdbc.test.autoconfigure) +
  @Import(TestDatabaseConfig.class). Each test is rolled back; @BeforeAll TestDatabase.reset() once (JDBC tests leave
  rows committed); @BeforeEach inserts cases c1-c4 and units through JdbcClient.create(Spring's DataSource), which joins
  the test transaction. Mirrors InMemoryAssignmentRepositoryTest (compares by id: restored objects are new objects)
  plus every field round-trips, demotion saved, second active trip refused at save, findActive ordering. The slice
  prints Hibernate SQL (select-then-insert visible).
- JdbcCaseRepository (3c): upsert of every column EXCEPT queued; findOpenByPatientId / findOpen use the LITERAL
  `status NOT IN ('CLOSED', 'CANCELLED')`, word for word like the partial index (with ? parameters Postgres cannot
  always prove a partial index applies once the driver reuses a prepared plan); findOpen ORDER BY opened_at, id; a
  second open case for a patient -> DuplicateKeyException. static toCase(rs) is package-private, shared with the queue.
- JdbcCaseQueue (3c): enqueue = UPDATE SET queued = true, 0 rows (case never saved) -> IllegalStateException (never
  silently lose an emergency); remove = UPDATE ... WHERE id AND queued (rows == 1); size = count(*) WHERE queued;
  peekNext = the same order without locking; pollNext = ONE statement: UPDATE cases SET queued = false WHERE id =
  (SELECT id ... WHERE queued ORDER BY <CRITICAL 0, HIGH 1, MEDIUM 2, else 3>, opened_at, id LIMIT 1 FOR UPDATE SKIP
  LOCKED) RETURNING the case. SKIP LOCKED: a second worker takes the next case instead of waiting; without it, it would
  wait, re-check, find the row no longer queued and return NOTHING (LIMIT 1). Difference from memory: ranks by the band
  saved now (memory kept a snapshot per entry); the engine always re-queues on a band change. ponytail: band order is
  spelled out in SQL (BAND_RANK); a new band needs a migration anyway.
- JdbcVitalsRepository (3c): INSERT ... ON CONFLICT (patient_id, at) DO NOTHING (a repeat is ignored, like memory);
  nullable measurements bound with Types.INTEGER / DOUBLE and read with getObject(..., Integer.class) (getInt would
  turn a missing value into 0); latestFor ORDER BY at DESC LIMIT 1; every reading is kept (memory kept only the latest).
  ponytail: one INSERT per reading, batch when a load test needs it.
- Tests (3c): JdbcCaseRepositoryTest (15; the in-memory "closed without saving drops out" quirk is inverted: only
  what was saved counts), JdbcCaseQueueTest (22: mirrored, the snapshot test replaced by theQueueRanksByTheSavedBand,
  never-saved case refused, savingACaseLeavesItQueued (CaseManager's order: enqueue, then save), and the SKIP LOCKED
  race: worker A polls on a SingleConnectionDataSource with auto-commit off (holds "first"), worker B polls at once and
  gets "second" without waiting; @Timeout(10, SEPARATE_THREAD) because a blocked JDBC call is not interruptible),
  JdbcVitalsRepositoryTest (8: mirrored + nulls stay null, a repeat ignored, every reading kept).
- 3d, the switch-over: EngineConfig's port beans are new JdbcXxx(jdbc) / new JpaAssignmentRepository(trips) (JdbcClient
  from Boot, TripJpaRepository from Spring Data, as @Bean parameters); EventPublisher stays InMemoryEventPublisher
  (ponytail: an event recorded in a transaction that rolls back stays as a "ghost"; outbox later). DispatchService takes
  a TransactionTemplate (Boot's bean, over JpaTransactionManager, which covers JDBC too). tick(): while (dispatchOne())
  with each dispatched case its own transaction (dispatchNext + onAssigned; Boolean.TRUE.equals(tx.execute(...)) because
  execute may return null), then one transaction for checkTimeouts and one for checkTrips: a failing case rolls back
  alone and is retried next tick. Reads (findX, snapshot) are transactions too (one consistent picture).
- Precision (found by ApiTest in 3d): Instant.now() on Windows has 100 ns, Postgres keeps microseconds and ROUNDS, so
  a resent reading looked older than the stored copy (OUT_OF_ORDER instead of DUPLICATE). Fixed twice: VitalsReading
  (core) truncates timeStamp to MICROS in its compact constructor (VitalsReadingTest.theTimeIsKeptToTheMicrosecond),
  and DbTime.toDb truncates every Instant to MICROS (never stored later than the moment it describes).
- Test databases (3d): TestDatabase's container holds TWO databases, both migrated at start: `test` (adapter tests;
  reset()) and `app` (CREATE DATABASE app; whole-application tests; resetApp()). Why: Spring keeps started test contexts
  alive for the whole run and their scheduled dispatch loop keeps ticking; on `test` it would poll the queue tests'
  cases. TestDatabaseConfig points at app (appUrlStarted()); JpaAssignmentRepositoryTest points at test with its own
  @DynamicPropertySource. TestDatabase is public, with public resetApp(), appUrlStarted(), username(), password().
- RestartTest: deliberately not a Spring test (the test framework caches contexts). SpringApplicationBuilder(
  DispatchServiceApplication).web(NONE).run("--spring.datasource.url=..." ...): settings as command-line args, because
  .properties(...) sets DEFAULT properties (lowest priority) and application.properties would win. First app: patient,
  unit, critical reading, tick, accept; closed; second app: case RESPONDING, trip EN_ROUTE, unit ASSIGNED, then arrive
  and complete work (case CLOSED, unit AVAILABLE).
- Checked by hand (Nov 22): docker compose up -d, java -jar dispatch-service/target/dispatch-service-1.0-SNAPSHOT.jar,
  POST patient/unit/critical reading, the loop dispatched, app killed, restarted ("Schema is up to date"): unit ASSIGNED,
  same trip, case RESPONDING. Packaging the jar needed one online Maven run (maven-jar-plugin deps).

## Hospitals (DONE: H1, H1b, H2, H3a, H3b, H3c)
- Capability enum: CARDIAC, STROKE, TRAUMA, PAEDIATRIC. FacilityKind: HOSPITAL, CLINIC.
- Facility record (id, name, location, kind, capabilities, accepting) replaced H1's Hospital: one record with a kind,
  because hospitals and clinics differ only in canTreat. Compact constructor: blank id/name, null location/kind/
  capabilities, a null capability (hand loop, so IAE not NPE) -> IAE; a CLINIC with capabilities or accepting=true ->
  IAE (a clinic has no emergency department; withAccepting(true) on a clinic throws too). Set.copyOf. Factories
  hospital(id, name, location, capabilities, accepting) and clinic(id, name, location). isHospital(). canTreat(needed)
  = isHospital() && capabilities.containsAll(needed) (a clinic treats nothing, not even an empty set).
  withAccepting(b) = diversion on/off (save over the old record).
- FacilityRepository port (save replaces, findById, findAll sorted by id) + InMemoryFacilityRepository.
- engine/DestinationChooser.choose(from, needed) -> Optional<Facility>: filters to hospitals FIRST, then 1) nearest
  capable + accepting, 2) nearest capable even on diversion, 3) nearest accepting, 4) nearest at all; ties by id;
  empty only if no hospital. Straight-line km (ponytail: routing later; reads all facilities).
- Resource.baseId: Optional<String>; 3-arg constructor = no base (delegates to the 4-arg one); blank -> IAE;
  restore(id, type, baseId, status, ...) (baseId is the 3rd parameter). Refer by id like trips do; existence is
  checked at registration (H3b) and by the units.base_id foreign key. JdbcResourceRepository reads/writes base_id.
- H2, the transport leg (one trip, second leg): trip ARRIVED -> TRANSPORTING -> DONE only (or ARRIVED -> DONE on
  scene); case RESPONDING -> TRANSPORTING -> STABILISED only, then CLOSED. TRANSPORTING is active (not terminal).
  Upgrader (RESPONDING only), Preemptor (ASSIGNED/EN_ROUTE only), TripMonitor (EN_ROUTE only) and TimeoutMonitor
  (ASSIGNED only) skip it with no code change; CaseManager.handle only updates the band (default branch). Cancel and
  fail on a TRANSPORTING trip are refused (breakdown during transport: BACKLOG).
- Assignment.hospitalId (Optional getter) and needs() (unmodifiable Set<Capability>, empty until transport; added
  in H3a so the hospital pre-alert can say "stroke"). startTransport(hospitalId, needs) is the ONLY way into
  TRANSPORTING: blank hospital or missing/null-containing needs -> IAE; not PRIMARY or not ARRIVED -> ISE;
  transitionTo(TRANSPORTING) -> ISE "use startTransport". demoteToSupport refuses a TRANSPORTING trip.
  restore(id, caseId, resourceId, role, status, assignedAt, hospitalId, needs): blank hospital -> IAE, TRANSPORTING
  without one -> IAE, needs as above. Hospital and needs stay after DONE (history).
- ResourceType.canCarryPatient(): AMBULANCE_ALS/BLS only (a nurse asking for an ambulance: BACKLOG).
- AssignmentManager.startTransport(tripId, hospitalId, needs): save + AssignmentStatusChanged(ARRIVED -> TRANSPORTING);
  unit stays ASSIGNED and claimed; complete() from TRANSPORTING frees it as usual. Private find() helper shared by
  move/demoteToSupport/startTransport. CaseManager.onTransporting(caseId).
- engine/TransportCoordinator(assignments, resources, chooser, assignmentManager, caseManager).start(tripId, needed):
  checks PRIMARY, ARRIVED, unit.type().canCarryPatient(), chooses from the UNIT's current position (empty -> ISE "No
  hospital is registered"), then moves the CASE FIRST (the only step that can still fail) and then the trip, so any
  refusal changes nothing. No new event (decided): listeners read the hospital from the trip. Finishing = the usual
  complete -> onStabilised -> close (service completeTrip, LiveEngine, Simulation all work unchanged).
- H3a (database), V2__hospitals.sql: facilities(id, name, kind CHECK, location geography, capabilities text[] NOT
  NULL DEFAULT '{}' CHECK (capabilities <@ ARRAY[the 4 names]), accepting; table CHECK kind = 'HOSPITAL' OR (no
  capabilities AND NOT accepting)). Array, not a join table (decided: read whole, the chooser filters in Java).
  units.base_id REFERENCES facilities. assignments.hospital_id REFERENCES facilities; assignments.needs text[] NOT
  NULL DEFAULT '{}' (same <@ CHECK); CONSTRAINT transport_has_hospital CHECK (status <> 'TRANSPORTING' OR
  hospital_id IS NOT NULL). cases_status_check and assignments_status_check (Postgres's automatic names for V1's
  column CHECKs, <table>_<column>_check) dropped and re-added with TRANSPORTING in ONE ALTER TABLE each;
  one_active_trip_per_unit dropped and recreated with TRANSPORTING. Nullable/constant-default ADD COLUMN = metadata
  only, no row rewrite. No inbound index (ponytail, tens of active trips). No facility delete (FKs keep history).
- JdbcFacilityRepository: upsert, findById, findAll ORDER BY id; capabilities as String[] param (driver -> text[]),
  read with rs.getArray(...).getArray(). Package-private static toNames(Set<Capability>) (EnumSet order, so stored
  arrays are stable) / fromNames(rs, column), also used by TripEntity. TripEntity: hospitalId, String[] needs
  (Hibernate maps Java arrays to Postgres arrays itself; ddl-auto=validate accepts text[]).
  TestDatabase TRUNCATE includes facilities. Tests: SchemaTest +5 (clinic rule, unknown capability, base FK = SQLState
  23503, transport needs hospital, a transporting unit cannot get a second trip), JdbcFacilityRepositoryTest (8),
  JpaAssignmentRepositoryTest +3 (hospital + needs round trip, empty needs, TRANSPORTING still active),
  JdbcResourceRepositoryTest +1 (base round trip).
- H3b (service + REST): EngineConfig beans facilityRepository (JdbcFacilityRepository), destinationChooser,
  transportCoordinator. DispatchService takes FacilityRepository + TransportCoordinator (constructor: ...,
  tripMonitor, facilities, transport, clock, tx). New methods, each one transaction inside the lock:
  registerFacility(Facility) (duplicate id -> ISE), setAccepting(id, boolean) (unknown -> NotFoundException; clinic
  -> IAE from withAccepting), findFacility, facilities(), startTransport(tripId, needs) (already TRANSPORTING ->
  returned unchanged, first hospital and needs stand). registerUnit(id, type, where, baseId): unknown base -> IAE
  (400: a wrong value in the body, not a wrong URL), no unit created. completeTrip unchanged (works from
  TRANSPORTING). DTOs: FacilityInfo (new), UnitInfo + baseId, TripInfo + hospitalId + needs, LiveSnapshot +
  facilities (decided: tens of rows, diversion shows live). Simulators unchanged (decided).
  Tests: DispatchServiceTest +8 (register/list/find, duplicate, diversion, base must exist, Mrs. Sharma end to end,
  repeated transport, no hospital = nothing changes, snapshot facilities); ApiTest +3 (facilities over HTTP, bad
  facility requests 400, Kolkata transport over HTTP with a based ambulance).
- H3c (hospital views): DispatchService.TRANSPORT_SPEED_KMH = 40 (public; ponytail: straight line, no roads).
  [R4b replaced it: the ETA now comes from TravelTime, see Routing R4.]
  DispatchService also takes VitalsRepository (constructor: ..., facilities, transport, vitals, clock, tx).
  fleetOf(id): units whose baseId is the facility, sorted by id (filters resources.findAll() in Java; ponytail:
  findByBaseId + index at thousands). inboundTo(id): active trips that are TRANSPORTING with this hospitalId; per
  trip the case (band), the unit (live position), vitals.latestFor(patient) (null if none); distance = unit ->
  hospital; etaMinutes = ceil(km / 40 * 60) (rounded UP: never 0 while still out); sorted by eta, then trip id.
  Both: unknown facility -> NotFoundException (404); nothing -> []. InboundPatient record (tripId, caseId,
  patientId, band, needs, unitId, unitType, latitude, longitude, distanceKm, etaMinutes, latestVitals).
  FacilityController: GET /{id}/units, GET /{id}/inbound. Decided: filter in Java, no new SQL; screens POLL
  /inbound every few seconds (the global live feed carries no vitals); latest reading only (history = BACKLOG);
  SBAR is the week-2 screen's presentation, not an API field.
  Tests: DispatchServiceTest +5 (own fleet only, chosen hospital sees the patient with band/needs/ETA/vitals and
  the other sees nobody, ETA drops after a position report, empty after handover, soonest first); ApiTest +1 (Pune:
  fleet, pre-alert with etaMinutes 2 = 1.05 km rounded up, empty after complete, 404s).
- Registration (decided Nov 22): the OPERATOR adds facilities (POST /facilities; the simulator plays the operator);
  self sign-up is skipped (trust: a fake hospital must never get patients). Each hospital manages its own:
  PUT /facilities/{id}/accepting, POST /units with baseId (base must exist), GET /facilities/{id}/units (its fleet),
  GET /facilities/{id}/inbound (pre-alert: ETA, latest vitals, needs). No auth yet (BACKLOG). Dispatch still sends
  the nearest free unit whatever its base; the base is where a unit waits and returns to.

## People (B1, written)
Decided Nov 22: the core objects carry who people are, as OPTIONAL fields (the in-memory simulator stays
anonymous); the API's request records make them REQUIRED (every client goes through the API). DispatchService is
permissive like the core. Dispatch never uses any of it.
- domain/Contact(name, phone): blank name -> IAE; static checkPhone(phone): optional leading +, then 7 to 15
  digits, spaces and dashes allowed ("+91 98450 12345" passes). Used for drivers, emergency contacts, facility phones.
- domain/PersonDetails(name, age 0..MAX_AGE=120, Sex sex); domain/Sex {FEMALE, MALE, OTHER}.
- Patient: new 4-arg constructor (id, location, PersonDetails details, Contact emergencyContact), the old 2-arg one
  delegates with nulls; details() and emergencyContact() are Optionals.
- Resource: 5-arg constructor (..., baseId, Contact driver), 3/4-arg delegate; restore(id, type, baseId, driver,
  status, location, movedFrom, lastSeenAt, lastMovedAt); driver() Optional.
- Facility record: 7th component String phone (null = none, else checkPhone); a 6-arg constructor without phone
  keeps old calls working; withPhone(phone); withAccepting keeps the phone.
- V3__people.sql: units.driver_name/driver_phone (CONSTRAINT driver_complete: both or neither); patients.name,
  age (CHECK 0..120), sex (CHECK), emergency_name/emergency_phone (details_complete, emergency_contact_complete);
  facilities.phone. All NULLable: pre-V3 rows exist (ponytail: NOT NULL in a later migration once cleaned). The
  three Jdbc repositories read/write them (nullable params typed with Types.VARCHAR/INTEGER).
- DispatchService.registerPatient(Patient) (was (id, home)); registerUnit(id, type, where, baseId, Contact driver);
  findPatient(id) -> Optional<PatientInfo>. DTOs: UnitInfo + driver, FacilityInfo + phone, CaseInfo + patientName,
  age, sex, InboundPatient + patientName, age, sex (from(trip, case, patient, unit, ...)), PatientInfo (new: id,
  lat, lon, name, age, sex, emergencyContact; never in the live feed).
- Tests: ContactTest, PersonDetailsTest, PatientTest +3, ResourceTest +2, FacilityTest +3; SchemaTest +2 (age 150,
  sex ROBOT, name without age/sex, driver without phone); repository round trips; DispatchServiceTest +2 (full
  record lookup; case, pre-alert and unit show the people); ApiTest +3 (record over HTTP, missing data 400s, unit
  shows its driver). ApiTest units belong to a shared clinic "api-base" at (0, 0), created in @BeforeEach if absent.
- Security finding (automated review, Nov 22): GET /api/patients/{id} exposes personal data with no login; known
  and deferred to the security piece (BACKLOG access control). Also for that piece: caller-chosen guessable patient
  ids (p1) -> consider server-generated UUIDs; emergency contact only for roles that need it.

## Simulator over HTTP (DONE: S1 + S2), dispatch-sim, package com.medidispatch.sim
The Bangalore city drives the RUNNING service over HTTP like real wearables and crew phones. The in-memory
LiveSimulation and the JavaFX window are unchanged.
- CityLink (package-private interface): reading, answered, position, arrived, backInService. LiveEngine implements
  it (methods now public @Override); LivePatient and LiveUnit take a CityLink instead of LiveEngine.
- Two clocks (decided): the city keeps its ScaledClock for the world's pace (illness, driving, on scene, repairs);
  everything SENT carries real time (HttpLink.retimed(reading, Instant.now())). The service's timeouts stay real
  seconds. LiveConfig.bangalore(): 60 patients, 6 ALS, 6 BLS, 5 nurses, 3 doctors, 8 illnesses/h, 5% of each
  failure, 20 min repair, speed 10 (city size unused); withStartSpeed(speed).
- BangaloreCity: bounds lat 12.83..13.10, lon 77.48..77.78; FACILITIES = 6 hospitals (blr-h-koramangala TRAUMA+
  CARDIAC, blr-h-jayanagar CARDIAC+STROKE, blr-h-whitefield TRAUMA, blr-h-hebbal STROKE+PAEDIATRIC,
  blr-h-malleshwaram PAEDIATRIC, blr-h-ecity none) and 4 clinics (blr-c-indiranagar, -yeshwanthpur, -banashankari,
  -marathahalli), MADE-UP names at real neighbourhoods, phones +91 80 41/42xx. plan(config) -> Plan(facilities,
  units, patients) using core objects: ambulances round-robin over hospitals, nurses/doctors over clinics, each at its
  base with a generated driver; patients p001.. with generated name/age (10% children, else 30-85)/sex (2% OTHER),
  70% with an emergency contact; all from the seed. inside(location).
- ServiceClient: java.net.http.HttpClient + Jackson 3 (tools.jackson.databind.json.JsonMapper; JsonNode.asString(),
  asText() is deprecated in 3). get/post/put return Response(status, JsonNode body), never throw for error statuses
  (only UncheckedIOException when unreachable); stream(path) -> Stream<String> lines (BodyHandlers.ofLines) for SSE;
  static text(node, member) (null for missing/JSON null); close() = shutdownNow (Java 21+).
- LiveFeedReader: daemon thread "live-feed", reconnects after 2 s; static readEvents(lines, onEvent): data lines
  joined with \n, a blank line ends an event, one optional space after "data:", other lines skipped, an unfinished
  event is not sent. The service names its events "snapshot".
- TripWatcher.update(snapshot JsonNode) -> List<Change(kind NEW|ROLE|GONE, tripId, unitId, caseId, patientId, role,
  target)>: NEW first (in feed order, target = the open case's lat/lon), then ROLE, then GONE sorted by id.
- HttpLink implements CityLink: setUp(plan) refuses if GET /api/facilities is non-empty (message names docker
  compose down -v), then POSTs facilities, units, patients (bodies as LinkedHashMaps). ONE sender thread
  ("http-sender") sends everything in order and owns the trip bookkeeping (trips, onSceneSince); a timer thread
  flushes positions every 1 s (ConcurrentHashMap keeps only each unit's latest; a broken-down unit still resends its
  spot so the stall check works) and checks on-scene every 250 ms; arrived -> POST arrive, then if PRIMARY treat the
  patient and start the on-scene timer (Simulation.ON_SCENE on the city clock) -> POST complete. GONE -> GET
  /api/trips/{id} for the final status -> TripOrder END (TIMED_OUT/FAILED start the repair). Counters: readings,
  tripsSeen, tripsFinished, conflicts (409s: normal races), errors (printed for the first 20). After stop() nothing
  counts as an error (an interrupted in-flight request is not a failure).
- HttpSimulation (public): constructor builds the plan and agents on a paused ScaledClock; setUp(); start() (agents
  every LiveEngine.BEAT on 4 daemon threads); stop(); status() one line; readingsSent/tripsFinished/errors.
  HttpSimulatorMain [url=http://localhost:8080] [speed=10]: set up, start, status every 10 s, Ctrl+C stops.
- Kept in package com.medidispatch.sim (not .http, changed from the plan) to reuse the package-private agents.
- Tests: TripWatcherTest (6), LiveFeedReaderTest (4), BangaloreCityTest (4), HttpLinkTest (1, re-timing);
  dispatch-service SimulatorOverHttpTest (RANDOM_PORT + TestDatabaseConfig + resetApp): a small city (seed 42, 8
  patients, 400 illnesses/h, no failures, speed 300) closes a case within 90 s with 0 errors (about 10 s in the full
  build; ~45 s alone because the container starts too); set-up refuses a non-empty database.
  dispatch-service has dispatch-sim as a TEST-scope dependency (never the other way: the service is packaged as a
  Spring Boot jar, which other modules cannot load classes from).
- S2 (transport and return to base), HTTP mode only (the in-memory LiveEngine still treats on arrival):
  - SimulatedUnit: optional home (4-arg constructor; 3-arg = no home, parks where a trip ends). goTo(destination)
    = second leg of the SAME trip (ISE without a trip): new target, behaviour forced NORMAL (no planned failure with a
    patient on board: the service cannot fail a TRANSPORTING trip), arrives a second time. headHome() (free + home +
    not there) -> drives back reporting positions, goingHome() false once home; assign() cancels it.
    reportsPosition() = on a trip (acknowledged, not arrived, not silent) OR goingHome.
  - LiveUnit: TripOrder.Kind LEG (drive on to target); END with DONE/CANCELLED -> free + headHome; after repair,
    back in service -> headHome. A unit driving home is AVAILABLE in the service (no service change).
  - HttpLink: connect(patients, units, plan, dice) also keeps unit types, patient ages, facility locations. Scene
    arrival: POST arrive, NOT treated (stays at its steady peak, so the band holds and the pre-alert shows real
    vitals). On-scene time up: trip demoted to SUPPORT -> nothing; else GET /api/cases/{id} band; an ambulance
    (canCarryPatient) with HIGH/CRITICAL -> POST /transport {"needs": needsFor(age, dice)} -> LEG to the chosen
    hospital (transporting set); refused -> fall back to treat + complete. Otherwise treat + POST complete. The
    second arrival of a transporting trip = handover: treat, POST complete, transported++.
    static needsFor(age, dice): under 18 -> PAEDIATRIC (+ TRAUMA 30%); adults CARDIAC 30%, STROKE 25%, TRAUMA 20%,
    nothing 25%. Status line: "... N finished, M to hospital ...".
  - HttpSimulation: each SimulatedUnit gets home = its base location; transported() accessor.
  - Tests: SimulatedUnitTest +6, HttpLinkTest +4 (needs rule), SimulatorOverHttpTest +1 (a trip DONE with a
    blr-h-* hospital and its case CLOSED, 0 errors).
- To run it: docker compose down -v; docker compose up -d (from C:\Projects\medidispatch); in IntelliJ reload Maven
  once (Jackson), start DispatchServiceApplication, then run HttpSimulatorMain (Program arguments e.g.
  "http://localhost:8080 30" for speed 30). At speed 10 the first transport comes after about 4-5 minutes. Watch with
  curl.exe localhost:8080/api/facilities/blr-h-jayanagar/inbound. Run again only after wiping (setup refuses).
  Tried by the user on Nov 22: the city registered (10 facilities).

## Next steps
Order agreed Nov 22 (the user does not want pacing by PLAN's week dates): B1 people (done) -> S1/S2 the simulator
over HTTP, building a BANGALORE city (real coordinates, made-up hospital names at neighbourhoods, units owned by
hospitals/clinics with drivers, transport, return to base: a simulator-only behaviour, the unit is AVAILABLE while
driving back) -> M1 a live map page in the browser on OpenStreetMap (served by the service, reads /api/live) ->
SEC security (L: login, roles operator/hospital/crew/device, hospital scoping) -> the screens.
S1, S2, M1 and routing R1-R5 are done. Order changed Nov 22 (the user's call): ROUTING FIRST (R1..R5, see "Routing"), then M1b
(map views: Streets/Plain/Dark/Satellite, layer toggles, bigger markers, live speed control in the simulator
console), then SEC security (L): login, roles (operator, hospital, crew, device), hospital scoping, personal data
only for roles that need it, non-guessable patient ids; the simulator and map then need to log in. Then the screens.
Clarified with the user: the backend service is the REAL application's core; the simulator, the JavaFX window and
map.html are dev/demo tools; the real front end (React control room, crew PWA, hospital panel, patient app) is
still to be built.

## Routing (R1-R5 DONE; R2b ALT landmarks later), module dispatch-routing, package com.medidispatch.routing
Why: everything moves and is measured in straight lines today (units fly over lakes; dispatch picks the nearest as
the crow flies; ETA = straight line at 40 km/h). Decided: write our own graph and algorithms (not OSRM/GraphHopper)
for the learning and portfolio value; traffic = a MODEL (road-class speeds, rush-hour factors, simulated incidents,
siren factor), live traffic is paid data. Pieces: R1 road data + graph, R2 Dijkstra then A* on travel time, R3
traffic model, R4 a TravelTime port in core (straight line stays the default) used by dispatch ranking (ring search
finds candidates, then rank the closest few by drive time), hospital choice and ETA, R5 simulator drives along
routes and the map draws them.
- Module dispatch-routing (the user created it; the folder was first misspelled "disptach-routing", renamed by
  Claude: folder moved, root pom fixed, IntelliJ needed Reload All Maven Projects). Depends on dispatch-core
  (Location) and Jackson 3.1.5.
- RoadClass: MOTORWAY 80, TRUNK 60, PRIMARY 45, SECONDARY 35, TERTIARY 30, UNCLASSIFIED 25, RESIDENTIAL 20,
  LIVING_STREET 10 km/h (free-flow); fromHighwayTag(tag) (_link = parent class; footway/cycleway/service ->
  empty).
- RoadGraph: CSR arrays (latitude[], longitude[], firstEdge[n+1], edgeTarget[], edgeLength[] float metres,
  edgeClass[] byte = enum position); package-private constructor checks the arrays fit; loop
  for (e = firstEdge(n); e < edgeEnd(n); e++). Directed: two-way road = 2 edges.
- GraphBuilder: addNode(osmId, lat, lon) maps OSM ids to 0..n-1 (same id again keeps the first), addRoad(from,
  to, class, forward, backward) measures metres with Location.distanceKmTo, skips self-loops; arrays double
  from 1024; build() = counting sort (count, running total, place).
- OverpassImport.read(InputStream): Jackson STREAMING (JsonParser, JsonToken.PROPERTY_NAME "elements", readTree one
  element at a time; Jackson 3 needs DeserializationFeature.FAIL_ON_TRAILING_TOKENS disabled for that); nodes go
  straight in, ways kept and turned into roads at the end (order independent). directions(): oneway yes/true/1
  forward, -1/reverse backward, no/false/0 both; untagged roundabout/circular and MOTORWAY one-way.
- GraphCleanup.largestStronglyConnected: Kosaraju (finish order DFS, reversed graph, last-finished first),
  explicit array stacks (a 200k-node loop test), keeps the largest component, renumbered (via GraphBuilder with old
  node numbers as ids).
- NearestNode: grid of CELL_DEGREES 0.0025 (~280 m) stored as CSR; ring search with the exact stop rule (a node in
  ring k is at least (k-1) x the smaller cell side away); ties to the lower node; points outside are clamped.
- GraphFile: DataOutputStream binary: MAGIC "MDRG", VERSION 1, class NAMES (mapped by name on load), counts,
  arrays. Refuses junk, other versions, unknown class names.
- ImportBangalore main: data/bangalore-roads.json -> cleanup -> data/bangalore.graph (args optional; working
  directory = project root).
- The data (downloaded Nov 22 with the user's OK, from https://overpass-api.de/api/interpreter, the query in the R1
  plan, bbox 12.83,77.48,13.10,77.78; the server was at capacity first, a retry 30 s later worked): 63.5 MB JSON,
  OSM as of 2026-11-22. Result: read 407,584 nodes / 862,642 edges, kept 402,843 / 855,218 (98.8%), graph file
  15.8 MB, under 2 s. data/ is in .gitignore. Map data (c) OpenStreetMap contributors, ODbL.
- Running ImportBangalore outside IntelliJ: maven-dependency-plugin is not in the offline cache; the classpath is
  dispatch-routing/target/classes;dispatch-core/target/classes; jackson-databind 3.1.5, jackson-core 3.1.5,
  com.fasterxml.jackson.core:jackson-annotations 2.21 jars; java -Xmx2g.
- Tests (35): RoadClassTest 4, GraphBuilderTest 8, OverpassImportTest 7, GraphCleanupTest 5 (incl. one-way dead
  end, 200k loop), NearestNodeTest 6 (incl. closer node in the next ring, 1,000 random points vs brute force),
  GraphFileTest 5.
- R2 (Dijkstra and A*): CostModel interface (seconds(g, edge), maxSpeedMetresPerSecond() for A*'s estimate);
  FreeFlow (length / class speed; max worked out from the enum). MinHeap (package-private): binary heap in two
  arrays (children 2i+1, 2i+2), sift with the moving item held aside, grows by doubling, no decrease-key: lazy
  deletion. Route record (nodes[], seconds, metres, explored = nodes taken off the heap) + from(), to(), points(g).
  Router(g, cost): dijkstra(from, to) / aStar(from, to) -> Optional<Route> (empty = unreachable; bad node = IAE);
  one private search, `guided` picks the priority (dist vs dist + estimate); settled[] skips stale heap copies and
  finished nodes; stops when the TARGET IS POPPED; cameFrom[] + cameBy[] rebuild the path (a CSR edge does not know
  its start). estimate(node, target) (package-private) = ESTIMATE_MARGIN 0.9999 x haversine metres (same 6,371 km
  radius as Location, raw doubles, no objects) / max speed; the margin absorbs float rounding of stored lengths so the
  estimate never overestimates. ponytail: fresh arrays per search (~5 MB, ~1 ms); reuse with stamps, bidirectional
  search, contraction hierarchies later. RouteDemo main: loads data/bangalore.graph, compares both.
- R2 results on the real graph (Nov 22): graph loads in 150 ms. Koramangala -> Jayanagar (3.5 km straight): 8.4 min,
  6.5 km by road; Dijkstra 24,932 nodes / 6.1 ms, A* 11,429 / 5.2 ms. Electronic City -> Hebbal (22.3 km): 34.8 min,
  27.6 km; Dijkstra 289,596 / 46.2 ms, A* 168,558 / 50.5 ms. Same answers. A* gained only 1.7-2.2x (predicted
  5-10x): the estimate assumes 80 km/h (motorway) while most roads are 20-45 km/h, so it is weak, and each A* step
  pays for 4 trig calls. Decided: accept for now (5-50 ms per route is fine for dispatch); R2b ALT landmarks (16
  landmarks, |d(L,t) - d(L,n)| lower bounds, typically 5-20x fewer nodes, ~25 MB) is the planned optimisation later,
  with these numbers as the "before".
- R2 tests: MinHeapTest 4, RouterTest 6 (fast detour beats slow lane, one-way not driven backwards, unreachable,
  start = target, path continuous and totals add up, bad input), RouterPropertyTest 2 (random 30x30 grids with
  one-way and missing streets: A* cost = Dijkstra for 200 pairs and never explores more; estimate <= real time).
- R3 (traffic model): CostModel.seconds(g, FROM, edge) now gets the start node (incidents need the midpoint);
  FreeFlow ignores it; Router skips a road whose cost is Double.POSITIVE_INFINITY (closed).
  TrafficProfile.bangaloreWeekday(): 24 hourly factors for MAIN roads (00-05 1.0, 06 0.9, 07 0.75, 08 0.55, 09 0.45,
  10 0.5, 11 0.65, 12-15 0.7, 16 0.65, 17 0.5, 18 0.4, 19 0.45, 20 0.6, 21 0.75, 22 0.9, 23 1.0); factor(class,
  LocalTime) = 1 - (1 - main) x share, linear between hours, wraps at 23; share(): TRUNK/PRIMARY/SECONDARY 1.0,
  MOTORWAY 0.8, TERTIARY/UNCLASSIFIED 0.6, RESIDENTIAL/LIVING_STREET 0.3 (switch without default). Factors in (0, 1].
  Incident(id, centre, radiusMetres, speedFactor 0..<1) record, closure(...) = factor 0; a road piece is inside
  when its MIDPOINT is (real pieces are short; a toy test with one 1.5-km piece had to centre the circle on the road).
  TrafficModel(profile): incidents in a ConcurrentHashMap by id (add replaces, remove, incidents() sorted);
  at(Instant | LocalTime, emergency) -> a FROZEN CostModel (record Frozen): 8 class speeds computed once; siren =
  min(traffic x SIREN_TRAFFIC_BOOST 1.3, free x SIREN_CAP 1.2); incidents copied into Areas with a bounding box (1%
  larger) checked before the exact haversine; overlapping jams multiply; jams apply after the siren; top speed =
  fastest free-flow x 1.2 for emergencies. BANGALORE = Asia/Kolkata. ponytail: traffic frozen at departure;
  time-dependent routing is the upgrade. Decided: circles (not edge ids: graph numbering changes on re-import),
  weekday curve only, made-up but reasonable siren numbers.
- R3 results on the real graph: Koramangala -> Jayanagar 03:00 normal 8.4 min / 6.5 km; 09:30 normal 15.6 min /
  5.2 km (a DIFFERENT, shorter route through smaller roads: rush hour changes which route is fastest); 09:30
  emergency 12.0 min; with a 150 m closure halfway along the rush-hour route 12.4 min (local detour).
- R3 tests: TrafficProfileTest 6, TrafficModelTest 10 (jam only inside, closure + reopen, multiply, siren cap and
  boost, top speed, frozen picture ignores later incidents, UTC -> Bangalore), TrafficRoutingTest 4 (night main road
  vs rush-hour lane, closure detour, only road closed -> no route, A* = Dijkstra with random incidents/times/both
  profiles). RouterPropertyTest.randomGrid is package-visible for reuse.
- R4a (core): port TravelTime.seconds(from, to, emergency) -> OptionalDouble (empty = no way there).
  engine/StraightLineTravelTime: SPEED_KMH 40, km / 40 x 3600, siren ignored; the default everywhere (old
  constructors kept, so the simulator and older tests are unchanged). Dispatcher(..., TravelTime):
  RANKED_BY_TRAVEL_TIME 5; fastestFirst(nearestFirst, patient) (package-private) re-ranks the first 5 of the ring
  search by seconds with the siren on (unreachable = infinity, so tried last), ties keep their distance order, the
  rest appended in distance order. SupportDispatcher(..., TravelTime): "is the responder sooner" compares secondsTo vs
  primarySeconds; the ring search is still capped at the primary's straight-line km (ponytail: a responder a bit
  farther in km but quicker by road is not looked at). DestinationChooser(facilities, travel): "nearest" = soonest by
  TravelTime with the siren on, unreachable hospitals skipped, ties by id. ponytail: the Preemptor still compares
  straight-line km.
  Tests: FixedTravelTime helper (from/to/unreachable/unreachableTo); StraightLineTravelTimeTest 3; DispatcherTest +3
  (unreachable tried last, only the nearest five ranked + ties keep order, missing TravelTime refused);
  DestinationChooserTest +3 (farther but quicker by road wins, unreachable skipped, missing args); SupportDispatcherTest
  +1 and a few changed.
- R4b (routing + service): RoadTravelTime implements TravelTime: snap both points (NearestNode), freeze the traffic
  at clock.instant() (TrafficModel.at), A*, add the straight legs to/from the road at SNAP_LEG 20 km/h; empty when
  A* finds no route (closure). load(path, traffic, clock); graph(). EngineConfig: trafficModel bean
  (bangaloreWeekday); travelTime(@Value medidispatch.routing.graph-file, default data/bangalore.graph in
  application.properties): RoadTravelTime if the file is found (findGraph also tries one folder up, for IntelliJ
  starting inside dispatch-service/), else StraightLineTravelTime with a warning; log "Road routing on: <path>
  (<n> nodes, <m> edges)". Dispatcher, SupportDispatcher and DestinationChooser beans take it. DispatchService takes
  TravelTime (constructor: ..., vitals, travel, clock, tx); pre-alert ETA = travel.seconds(unit, hospital, siren),
  falling back to straight line at 40 km/h when empty; distanceKm stays straight-line. TRANSPORT_SPEED_KMH removed.
  Service tests run on straight lines: TestDatabaseConfig sets graph-file "", RestartTest passes
  --medidispatch.routing.graph-file=.
  Tests: RoadTravelTimeTest 7 (L-shaped road longer than the line, snap legs added, peak = night / 0.4, siren faster,
  closure -> empty, load() from a file, bad args), RoutingConfigTest 4 (no file -> straight line, file -> roads, blank
  = off, relative path tried one folder up).
- R5 decided: the service is the only place that knows the roads; the simulator and the map ASK it over HTTP
  (like a crew phone asking for navigation), so dispatch-sim stays without the graph and the in-memory/JavaFX
  simulators keep straight lines. Routes are NOT stored (stateless): the crew and the map ask the same question at
  almost the same moment and get the same line (upgrade: the trip stores its route once real crew apps report
  theirs). No re-routing mid-leg: a closure only affects legs planned after it (traffic frozen at departure, R3).
  Incidents are not in the live feed (the map polls them), so LiveSnapshot and DispatchService are unchanged.
- R5a (routing + service): record Drive(points >= 2, seconds, metres) (points copied); RoadTravelTime.drive(from,
  to, emergency) -> Optional<Drive>: from, the route's nodes, to (an end exactly on its node is not repeated; A to A
  gives [A, A]); seconds() = drive().seconds(), one path. api/RouteController(TravelTime, TrafficModel):
  GET /api/route?fromLat&fromLon&toLat&toLon&emergency(default false) -> RouteInfo(seconds, metres,
  points [[lat, lon], ...]) (pairs: a cross-city route has 1000+ points); `travel instanceof RoadTravelTime` (core
  needs no route port), else the straight line [from, to] at 40 km/h; 404 NotFoundException when closures leave no
  way; 400 for a missing/non-number param (Spring) or a bad Location (IAE). POST /api/incidents (IncidentRequest
  {id, position{latitude, longitude}, radiusMetres, speedFactor}, 201, same id replaces), GET /api/incidents
  (Incident records, sorted by id), DELETE /api/incidents/{id} (204, also for an unknown id). ponytail: incidents
  in memory only and open to anyone until SEC. Also fixed a misplaced Javadoc in PatientController.
  Tests: DriveTest 2, RoadTravelTimeTest +2 (drive points/time/length, closure -> empty), RouteControllerTest 2
  (plain JUnit on an L-shaped road: route follows the roads, an incident added through the controller closes it and
  removing it reopens), ApiTest +4 (straight-line route over HTTP, bad route params 400, incidents add/replace/
  list/delete twice, bad incidents 400).
- R5b (simulator): CityLink.route(from, to, emergency) -> Optional<Way(points, seconds)>, a DEFAULT method returning
  empty (straight line), so LiveEngine is unchanged; unlike the other calls it WAITS. HttpLink.route: GET /api/route
  on the calling agent thread (a read, needs no place in the sender's order); 404 -> empty quietly, other failures
  counted as errors and empty; static way(JsonNode) parses the answer. LiveUnit asks on START and LEG (siren) and on
  heading home after a trip or a repair (no siren; private headHome()/drive()). SimulatedUnit moves along waypoints
  (next index, restKm = length after the current waypoint, legSpeedKmPerHour); the straight line is ONE waypoint,
  so the old behaviour is unchanged exactly. follow(points, seconds): skips the first point, replaces the last with
  the leg's real destination, speed = route km / seconds (seconds <= 0 keeps the type's speed), tripKm = road length
  so "halfway" (breakdown, signal loss) is half the ROAD; refused when the unit is going nowhere (ISE) or < 2 points
  (IAE). New accessors home(), legSpeedKmPerHour().
  Tests: SimulatedUnitTest +6 (detour followed, arrives in the route's 600 s +-1 tick, breakdown in the middle of
  the north leg, ends exactly at the patient, drive home follows a route, refusals, no time keeps the type's speed),
  HttpLinkTest +1 (way() parsing). SimulatorOverHttpTest now exercises /api/route on every leg (errors 0).
- R5c (map): see "Live map".
- R5 verified in the browser pane against the running city with the real graph (Nov 24): trip routes of 177 and
  317 points, trimming advanced (15 -> 62, 22 -> 77 in ~8 s), a nurse following the streets through CV Raman
  Nagar, the Silk Board closure drawn red, no console errors.

## Live map (M1, DONE)
- dispatch-service/src/main/resources/static/map.html: served by Spring Boot from static/ (no controller), open
  http://localhost:8080/map.html. One self-contained page: Leaflet 1.9.4 from unpkg with SRI integrity hashes
  (decided: Leaflet for this viewer; MapLibre stays planned for the React control room), OpenStreetMap raster tiles
  with the required credit (ponytail: public tile servers are for dev/demo only).
- EventSource('/api/live'), event "snapshot" (the browser reconnects by itself). Markers are Leaflet divIcons kept in
  Maps by id and updated in place (setLatLng; the icon is rebuilt only when its look changes; stale ids pruned), so
  nothing flickers. Hospitals = red "H" (grey on diversion), clinics teal "+", open cases = dots by band (pulse while
  ALERTED; CSS "scale" property so the centring transform stays), units = ALS/BLS/N/D/C labels coloured by status,
  trips = polyline unit -> patient, or unit -> hospital while TRANSPORTING (red), dashed for SUPPORT. Popups are
  built with textContent only (API values are never inserted as HTML). Side panel: connection, counts, hospitals
  (accepting/diversion, inbound count, click = zoom + popup), legend. Narrow screens stack the panel below.
- Shows only patients with an OPEN case (decided: the feed carries only those). No login, like the API.
- Bugs found in the browser check: (1) first fitBounds ran while the pane was tiny -> zoom 19; fixed with
  map.invalidateSize() + maxZoom 13. (2) the hospital list was rebuilt every second, swallowing clicks; now rebuilt
  only when its content key changes. Browsers cache the page: Ctrl+F5 after changing it.
- R5c: trip lines follow the roads. routes Map keyed "tripId|patient" or "tripId|hospital" (TRANSPORTING) ->
  {points, next}; askRoute() fetches /api/route ONCE per leg from the unit's position at that moment, emergency=true
  (the same question as the crew); until the answer, or with none, the straight line. ahead(): nearest route point
  to the unit, searching only FORWARD from `next` (a road passing near itself cannot make the line jump back),
  draws [unit, ...points after it]. Legs no longer active are forgotten. Incidents: GET /api/incidents every 5 s,
  L.circle per id (red closure, orange jam, popup), pruned. Legend: route, jam, closed.
- Test: ApiTest.theLiveMapPageIsServed (200, listens to /api/live, uses OpenStreetMap tiles, fetches /api/route and
  /api/incidents).
BACKLOG.md cleanups were deferred by the user. Real scoring (NEWS2 etc.) is week 3: RiskScorer makes it a drop-in.
