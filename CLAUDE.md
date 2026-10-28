# MediDispatch

Vitals-driven emergency dispatch (simulation only, synthetic data). Full plan: PLAN.md. Public overview: README.md.

## How to work with me
- I'm learning Java and Spring (LLD, HLD, scalable systems) by building this. Act as a tutor.
- Workflow for every step (when one section is done and we move to the next):
  1. Talk about what we build next and why.
  2. Spec: every class needed, its methods, and which package it goes in.
  3. LLD lesson (principle, what goes wrong otherwise, a check-yourself question), plus **Decisions for me**.
  4. STOP. Give code only when I ask. Give tests only after that, when I ask.
  5. When all tests pass, I commit, then we go to the next step.
  Never dump code or tests unprompted.
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

## Built so far (422 tests)
Domain: VitalsReading, RiskBand, RiskAssessment, Case/CaseStatus, Resource/ResourceStatus/ResourceType,
Location (haversine), Patient, Assignment (entity) / AssignmentStatus
(ASSIGNED, EN_ROUTE, ARRIVED, DONE, CANCELLED, TIMED_OUT, FAILED).
Ports + in-memory adapters: CaseQueue, ResourceRepository, ResourceClaimer, PatientRepository, CaseRepository,
AssignmentRepository (incl. findActive()), EventPublisher.
Events (package event): CaseStatusChanged, CaseBandChanged, UnitAssigned, AssignmentStatusChanged.
Engine: Dispatcher, CaseManager (incl. onAssignmentLost), AssignmentManager (incl. timeOut, onFailed),
TimeoutMonitor, UnitTracker, TripMonitor, ResponseRecovery (incl. onPreempted), Preemptor, DispatchCoordinator,
helper SearchRings (2, 4, 8, 16 km... capped at the max radius).
Scoring: RiskScorer, SimpleScorer (placeholder).
Response (package response): ResponseDecider (interface: decide(case, patient) -> ResponsePlan), ResponsePlan (record:
ordered List<ResourceType>, needsUnit(), allowedTypes()), LadderResponseDecider (placeholder ladder).
All main classes have Javadoc (class, constructors, public methods; trivial accessors skipped). Keep it up to date
when code changes, and document new classes the same way.
Ports added: CaseQueue.peekNext(), ResourceRepository.findAssignedNear(center, radiusKm, types).
Resource also tracks lastSeenAt / lastMovedAt via reportLocation(where, at); movement means more than 20 m from the
last counted position (movedFrom anchor), so GPS jitter is ignored and small steps add up.
Test helper: MutableClock (a Clock that only moves with advance(Duration)). It currently lives in src/main/.../engine
but only tests use it: move it to src/test/.../engine (small to-do).
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
- Preemptor(assignments, cases, resources, claimer, patients, decider, recovery, searchRadiusKm)
- Tests build both with `new LadderResponseDecider()`.
- DispatchCoordinator(dispatcher, preemptor, queue)

## Last step: onFailed + ResponseRecovery (DONE, tested)
FAILED status, AssignmentManager.onFailed, ResponseRecovery (onFailed, onTimedOut), TimeoutMonitor uses it.
Tests: AssignmentStatusTest updated for TIMED_OUT/FAILED, onFailed tests in AssignmentManagerTest, ResponseRecoveryTest
(stages, breakdown scenario, retry-loop guard, priority kept, events, refused calls change nothing, known-limit test).
ResponseRecoveryTest.knownLimit... documents that recovery is two non-atomic steps; rewrite it if recovery becomes atomic.

Open small items (mine to fix): delete engine/ResponseTypes.java (unused since the ladder); Checks messages lack a
space ("caseIdis required"); move MutableClock to src/test; leftover "//      }" line in VitalsReading.
SafeEventPublisher (decorator that logs and swallows failures) was designed but not added.

## Running tests
mvn is not on PATH for Claude. Claude can compile and run the whole suite with IntelliJ's bundled JDK
("C:/Program Files/JetBrains/IntelliJ IDEA 2026.2.0.1/jbr/bin" javac/java) plus the JUnit 5.11.4 / platform 1.11.4
jars from ~/.m2, building into its scratchpad (nothing written to the project). Classpaths need Windows paths (cygpath -w).

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

## Next steps after that
1. First responders (multi-unit response), the user's idea: for CRITICAL (maybe HIGH), also send the nearest
   nurse/doctor to start care before the ambulance. Outline agreed: ResponsePlan gets a support list; new
   ResourceType DOCTOR (maybe COMMUNITY_RESPONDER); Assignment gets a role (PRIMARY/SUPPORT); trips handled by
   assignment id instead of case id (big refactor: AssignmentManager, ResponseRecovery, both monitors, Preemptor);
   losing support never requeues the case, losing the primary does; support is best-effort (no queue, no
   preemption) and only sent if closer than the primary; closing a case ends all its trips. Open questions: who
   counts as a responder, which bands, does the nurse leave when the ALS arrives.
2. VitalsProcessor (single entry point for readings).
3. Simulator with scorer comparison (inject breakdowns and signal loss), then real scoring
   (trend, baseline, context, NEWS2). Then the Spring layer.
