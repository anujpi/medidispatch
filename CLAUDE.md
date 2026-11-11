# MediDispatch

Vitals-driven emergency dispatch (simulation only, synthetic data). Full plan: PLAN.md. Public overview: README.md.

## How to work with me
- I'm learning Java and Spring (LLD, HLD, scalable systems) by building this. Act as a tutor.
- Workflow for every step (when one section is done and we move to the next):
  1. Talk about what we build next and why.
  2. Spec: every class needed, its methods, and which package it goes in.
  3. LLD lesson (principle, what goes wrong otherwise, a check-yourself question), plus **Decisions for me**.
  4. STOP. When I ask for code, show it in chat with explanations so I can understand it first.
  5. When I say so, Claude writes the files into the project, writes/updates the tests and runs the suite.
  6. When all tests pass, I commit, then we go to the next step.
  Never dump code or tests unprompted. Every main class gets Javadoc (class, constructors, public methods).
- I type the code myself and run the tests, so explain every new Java feature (lambdas, switch, Optional, etc.)
  in plain words. I'm not comfortable with `::` method references or streams yet, so prefer explicit lambdas
  and plain loops.
- When I ask for code or tests, show them in chat. Don't create or edit Java files in my project unless I ask.
- Review my code honestly: point out real bugs first, then style.

## Decisions
- dispatch-core is pure Java: no Spring, JPA, Redis or Kafka. Hexagonal (ports and adapters).
- Priority comes from a RiskScorer (interface). SimpleScorer is a labelled placeholder, replace with NEWS2 later.
- Java 21, Maven multi-module. JUnit 5 for tests. Accessors are `x()` style, not `getX()`.
- Entities (Case, Resource, Patient) are classes with private state and guarded transitions; values are records.
- Always `save()` an entity after changing it, even though the in-memory adapters don't force it.
- Time comes in through a `java.time.Clock`, never `Instant.now()` inside engine code.
- Deadline: December. Plan to build everything in PLAN.md.

- Events are published AFTER state is saved, collected and sent at the end of an operation. Never publish no-ops
  (same band / same status). The engine must not fail if publishing fails (EventPublisher must not throw);
  the real fix later is the outbox pattern.
- Constructor injection everywhere; engine classes import ports and domain only, never InMemory adapters.

## Built so far (521 tests: 486 in dispatch-core, 35 in dispatch-sim)
Domain: VitalsReading, RiskBand, RiskAssessment, Case/CaseStatus, Resource/ResourceStatus/ResourceType
(AMBULANCE_ALS, AMBULANCE_BLS, NURSE, COURIER, DOCTOR), Location (haversine), Patient, Assignment (entity, with
AssignmentRole PRIMARY/SUPPORT) / AssignmentStatus (ASSIGNED, EN_ROUTE, ARRIVED, DONE, CANCELLED, TIMED_OUT, FAILED).
Ports + in-memory adapters: CaseQueue, ResourceRepository, ResourceClaimer, PatientRepository, CaseRepository,
AssignmentRepository (findById, findActiveByCaseId -> List, findActiveByResourceId -> Optional, findActive()),
VitalsRepository (save, latestFor(patientId) = newest by timeStamp), EventPublisher.
Events (package event): CaseStatusChanged, CaseBandChanged, UnitAssigned (carries the role), AssignmentStatusChanged.
Engine: VitalsProcessor + ProcessingResult, Dispatcher, CaseManager (incl. onAssignmentLost), AssignmentManager (incl. timeOut, onFailed),
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
- VitalsProcessor(patients, vitals, scorer, caseManager, clock, maxAge)
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
mvn is not on PATH for Claude. Claude can compile and run the whole suite with IntelliJ's bundled JDK
("C:/Program Files/JetBrains/IntelliJ IDEA 2026.2.0.1/jbr/bin" javac/java) plus the JUnit 5.11.4 / platform 1.11.4
jars from ~/.m2, building into its scratchpad (nothing written to the project). Classpaths need Windows paths (cygpath -w).
Compile order: core main, core test, sim main (cp core main), sim test; run with a small JUnit launcher over package
com.medidispatch. The user runs things from IntelliJ (Ctrl twice -> mvn test from the root medidispatch project).

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
The simulator found the response-upgrade gap (nurse stays the only help when MEDIUM turns CRITICAL): top of BACKLOG.

## Next steps after that
1. Fix "upgrade the response when a case gets worse" (and maybe "unit back in service") from BACKLOG.md.
2. JavaFX window in a separate module dispatch-sim-fx: Simulation gets snapshot() -> WorldSnapshot; AnimationTimer
   calls step(); Canvas map + side panel; SimulatorLauncher with a plain main. User is new to JavaFX: explain.
3. Then the Spring layer (REST, Postgres, Redis, Kafka/outbox, Docker, Kubernetes, dashboard). Deadline December.
4. Real scoring (NEWS2, trend, baseline, context) can come after Spring works: RiskScorer makes it a drop-in swap.
