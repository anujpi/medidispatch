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

## Built so far (327 tests, about 1,270 lines of engine code)
Domain: VitalsReading, RiskBand, RiskAssessment, Case/CaseStatus, Resource/ResourceStatus/ResourceType,
Location (haversine), Patient, Assignment (entity) / AssignmentStatus
(ASSIGNED, EN_ROUTE, ARRIVED, DONE, CANCELLED, TIMED_OUT, FAILED).
Ports + in-memory adapters: CaseQueue, ResourceRepository, ResourceClaimer, PatientRepository, CaseRepository,
AssignmentRepository (incl. findActive()), EventPublisher.
Events (package event): CaseStatusChanged, CaseBandChanged, UnitAssigned, AssignmentStatusChanged.
Engine: Dispatcher, CaseManager (incl. onAssignmentLost), AssignmentManager (incl. timeOut, onFailed),
TimeoutMonitor, ResponseRecovery. Scoring: RiskScorer, SimpleScorer (placeholder).
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
- Dispatcher(queue, patients, resources, claimer, assignments, publisher, clock, searchRadiusKm)
- AssignmentManager(assignments, resources, claimer, publisher, clock)
- ResponseRecovery(assignmentManager, caseManager)
- TimeoutMonitor(assignments, recovery, clock, ackTimeout)

## Last step: onFailed + ResponseRecovery (DONE, tested)
FAILED status, AssignmentManager.onFailed, ResponseRecovery (onFailed, onTimedOut), TimeoutMonitor uses it.
Tests: AssignmentStatusTest updated for TIMED_OUT/FAILED, onFailed tests in AssignmentManagerTest, ResponseRecoveryTest
(stages, breakdown scenario, retry-loop guard, priority kept, events, refused calls change nothing, known-limit test).
ResponseRecoveryTest.knownLimit... documents that recovery is two non-atomic steps; rewrite it if recovery becomes atomic.

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
