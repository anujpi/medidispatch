# MediDispatch

Vitals-driven emergency dispatch (simulation only, synthetic data). Full plan: PLAN.md. Public overview: README.md.

## How to work with me
- I'm learning Java and Spring (LLD, HLD, scalable systems) by building this. Act as a tutor.
- For each step, use this format: **Why** we're building it, **Spec**, **LLD lesson** (principle, what goes
  wrong otherwise, a check-yourself question), **Tests**, **Decisions for me**.
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

## Built so far (about 300 tests, about 1,270 lines of engine code)
Domain: VitalsReading, RiskBand, RiskAssessment, Case/CaseStatus, Resource/ResourceStatus/ResourceType,
Location (haversine), Patient, Assignment (entity) / AssignmentStatus
(ASSIGNED, EN_ROUTE, ARRIVED, DONE, CANCELLED, TIMED_OUT, FAILED).
Ports + in-memory adapters: CaseQueue, ResourceRepository, ResourceClaimer, PatientRepository, CaseRepository,
AssignmentRepository (incl. findActive()), EventPublisher.
Events (package event): CaseStatusChanged, CaseBandChanged, UnitAssigned, AssignmentStatusChanged.
Engine: Dispatcher, CaseManager (incl. onAssignmentLost), AssignmentManager (incl. timeOut, onFailed),
TimeoutMonitor, ResponseRecovery. Scoring: RiskScorer, SimpleScorer (placeholder).
Test helper: MutableClock (a Clock that only moves with advance(Duration)); note it lives in src/main/.../engine
(it was meant for src/test; consider moving it).
FullLifecycleTest proves: reading -> score -> case -> dispatch -> trip -> complete -> close, and the freed
ambulance serves the next patient, with the exact ordered event list. TimeoutMonitorTest proves reassignment:
nearest unit goes silent -> timed out, unit OFFLINE, case requeued -> next unit is sent.

Rules baked in: a unit whose trip TIMED_OUT or FAILED goes OFFLINE (not AVAILABLE) so it is never picked again.
Recovery for a lost unit always goes through ResponseRecovery (assignment first, then case) so callers cannot
forget the second step.

Constructor shapes:
- CaseManager(cases, queue, publisher, clock)
- Dispatcher(queue, patients, resources, claimer, assignments, publisher, clock, searchRadiusKm)
- AssignmentManager(assignments, resources, claimer, publisher, clock)
- ResponseRecovery(assignmentManager, caseManager)
- TimeoutMonitor(assignments, recovery, clock, ackTimeout)

## Status of the last step (onFailed + ResponseRecovery): code is committed-ready, TESTS ARE MISSING
Done in main code: FAILED status, AssignmentManager.onFailed, ResponseRecovery, TimeoutMonitor uses it.
Still to do (in this order):
1. AssignmentStatusTest is OUT OF DATE: its ALLOWED table has no TIMED_OUT or FAILED, so the all-pairs test
   fails (and ASSIGNED/EN_ROUTE/ARRIVED rows are missing the new transitions). Update the table and the
   terminal tests (DONE, CANCELLED, TIMED_OUT, FAILED are terminal).
2. AssignmentManagerTest: add onFailed tests (from ASSIGNED, EN_ROUTE, ARRIVED; unit ends OFFLINE and unclaimed;
   event published; refused after DONE and for an unknown case).
3. ResponseRecoveryTest (new): onFailed while EN_ROUTE and ARRIVED; ordered events (trip change, then case change);
   breakdown scenario (nearest unit fails, dispatchNext sends the next one); a failed unit is not offered again;
   onTimedOut behaves the same with TIMED_OUT; no active trip -> throws and changes nothing; validation.
4. Commit.

Open small items (mine to fix): Dispatcher missing-patient message has no spaces ("Patient "+id+"not found...");
Dispatcher constructor error message omits assignments/publisher; EventPublisher has no "must not throw" comment;
SafeEventPublisher (decorator that logs and swallows failures) was designed but not added.

## Next steps after that
1. Live trip monitoring of EN_ROUTE units using location: redirect if the unit is NOT MOVING (stalled) or its SIGNAL
   IS LOST. Needs Resource to remember when it last reported (lastSeenAt) and a way to detect no movement
   (a "last progress" marker or short position history). A monitor like TimeoutMonitor calls
   recovery.onFailed. Overdue-arrival checks need an EtaCalculator.
2. Preemption (a worse case takes a unit already assigned to a lower-risk case; reuse cancel + requeue).
3. ResponseDecider (replaces the band->type table in Dispatcher), VitalsProcessor (single entry point).
4. Simulator with scorer comparison (inject breakdowns and signal loss), then real scoring
   (trend, baseline, context, NEWS2). Then the Spring layer.
