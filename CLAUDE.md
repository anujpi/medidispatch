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

## Built so far (663 tests: 522 in dispatch-core, 63 in dispatch-sim, 22 in dispatch-sim-fx, 56 in dispatch-service)
Domain: VitalsReading, RiskBand, RiskAssessment, Case/CaseStatus, Resource/ResourceStatus/ResourceType
(AMBULANCE_ALS, AMBULANCE_BLS, NURSE, COURIER, DOCTOR), Location (haversine), Patient, Assignment (entity, with
AssignmentRole PRIMARY/SUPPORT) / AssignmentStatus (ASSIGNED, EN_ROUTE, ARRIVED, DONE, CANCELLED, TIMED_OUT, FAILED).
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
  reads included; ponytail comment: revisit with Postgres). registerPatient / registerUnit (ISE if the id exists),
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
- Tests: EngineConfigTest (@SpringBootTest: engine built, beans shared), DispatchServiceTest (own
  AnnotationConfigApplicationContext(EngineConfig, DispatchService) per test, so no scheduling and tick() is called
  by hand: full happy path, duplicate ids, unknown ids, arrive before accept, 50 threads + a ticking thread ->
  exactly 10 units busy and 10 cases RESPONDING, aRepeatedStepIsHarmless, goingBackwardsIsStillRefused,
  aSnapshotShowsTheFleetTheOpenCasesAndTheTrips).

## dispatch-service REST API (DONE, tested), package com.medidispatch.service.api
Controllers only translate HTTP <-> DispatchService calls; no logic. Decisions taken: repeated steps -> 200; readings
always 200 with the outcome (DUPLICATE, TOO_OLD... are not errors, so devices don't retry); unit without a trip -> 204.
- PatientController (/api): POST /patients (201; body PatientRequest), GET /patients/{id}/case, POST /readings (body is
  the domain VitalsReading record itself; 200 + ReadingResult), GET /cases/{id}.
- UnitController (/api/units): POST (201 + UnitInfo; body UnitRequest), GET /{id}, GET /{id}/trip (200 or 204; 404 for
  an unknown unit), PUT /{id}/position (204; body Coordinates; PUT = idempotent by design), POST /{id}/back-in-service.
- TripController (/api/trips): GET /{id}, POST /{id}/accept, /arrive, /complete (action URLs, not "set the status").
- Request records: Coordinates(Double latitude, Double longitude) + toLocation() (NOT Location as a body: missing
  double fields silently become 0.0; boxed Double + null check refuses them), PatientRequest(id, Coordinates home),
  UnitRequest(id, ResourceType type, Coordinates position). Compact constructors refuse a missing position; blank ids
  and missing types are refused by the engine's own constructors.
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
  TimescaleDB), db/user/password all `medidispatch` (ponytail: plain-text dev password), port 5432, named volume
  dbdata mounted at /home/postgres/pgdata/data (this image's data dir, NOT /var/lib/postgresql/data).
- dispatch-service/src/main/resources/db/migration/V1__init.sql (Flyway; never edit after it has run, add V2...).
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

## Database adapters (IN PROGRESS: 3a done, 3b and 3c to go), package com.medidispatch.service.persistence
Split into 3 pieces. 3a (DONE): patients, units, claimer. 3b: cases, queue, vitals on JDBC (F) + trips on JPA (L).
3c: EngineConfig switches to the adapters, each DispatchService call one transaction INSIDE the lock, all Spring tests
on Postgres.
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
- pom: spring-jdbc (plain library, main scope; NOT the Boot starter, which would auto-create a DataSource while the
  app still runs in memory). Flyway, driver, Testcontainers still test scope until 3c.
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

## Next steps
Follow PLAN.md section 10 (week 1, Nov 19-25). Next piece: adapters 3b (JDBC for cases, the queue with FOR UPDATE SKIP
LOCKED, vitals; JPA for trips, starting with the JPA lesson), then 3c (the switch-over). Then hospitals, the simulator
over HTTP.
BACKLOG.md cleanups were deferred by the user. Real scoring (NEWS2 etc.) is week 3: RiskScorer makes it a drop-in.
