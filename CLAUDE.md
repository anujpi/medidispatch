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

## Built so far (all with tests: about 275 tests, 1,150 lines of engine code)
Domain: VitalsReading, RiskBand, RiskAssessment, Case/CaseStatus, Resource/ResourceStatus/ResourceType,
Location (haversine), Patient, Assignment (entity) / AssignmentStatus (ASSIGNED, EN_ROUTE, ARRIVED, DONE, CANCELLED).
Ports + in-memory adapters: CaseQueue, ResourceRepository, ResourceClaimer, PatientRepository, CaseRepository,
AssignmentRepository, EventPublisher.
Events (package event): CaseStatusChanged, CaseBandChanged, UnitAssigned, AssignmentStatusChanged.
Engine: Dispatcher, CaseManager, AssignmentManager. Scoring: RiskScorer, SimpleScorer (placeholder).
FullLifecycleTest proves: reading -> score -> case -> dispatch -> trip -> complete -> close, and the freed
ambulance serves the next patient, with the exact ordered event list.

Constructor shapes:
- CaseManager(cases, queue, publisher, clock)
- Dispatcher(queue, patients, resources, claimer, assignments, publisher, clock, searchRadiusKm)
- AssignmentManager(assignments, resources, claimer, publisher, clock)

Open small items (mine to fix): Dispatcher missing-patient message has no spaces ("Patient "+id+"not found...");
Dispatcher constructor error message omits assignments/publisher; EventPublisher has no "must not throw" comment;
SafeEventPublisher (decorator that logs and swallows failures) was designed but not added.

## Current step
Timeouts and reassignment (spec agreed):
1. Add TIMED_OUT to AssignmentStatus (ASSIGNED -> TIMED_OUT, terminal); update AssignmentStatusTest table.
2. AssignmentRepository.findActive() returning a new List of non-terminal assignments (+ in-memory + test).
3. AssignmentManager.timeOut(caseId): trip -> TIMED_OUT, claim released, unit -> OFFLINE (NOT AVAILABLE, otherwise
   the same dead unit is picked again forever), event published.
4. CaseManager.onAssignmentLost(caseId): case RESPONDING -> ALERTED, re-enqueue, event published.
5. TimeoutMonitor(assignments, assignmentManager, caseManager, clock, ackTimeout).checkTimeouts(): for each active
   ASSIGNED trip with assignedAt + ackTimeout <= now, timeOut + onAssignmentLost; returns the count. Reassignment is
   then just the next dispatchNext(). Needs a MutableClock test helper (extends java.time.Clock).
Key test: two ambulances, the nearest never answers, checkTimeouts, dispatchNext sends the second one.

Right after timeouts (agreed, onFailed will be used a lot):
- AssignmentManager.onFailed(caseId): explicit failure report (breakdown, can't complete). Probably a FAILED
  AssignmentStatus (from ASSIGNED, EN_ROUTE, ARRIVED). Trip FAILED, unit OFFLINE, claim released; then the caller
  (or a small helper) runs CaseManager.onAssignmentLost so the case is requeued. Should also work for the
  timeout path so the logic lives in one place.
- Mid-trip monitoring of EN_ROUTE units using location: redirect if the unit is NOT MOVING (stalled) or its SIGNAL
  IS LOST. Needs Resource to remember when it last reported (lastSeenAt) and a short position history or a
  "last progress" marker to detect no movement; a monitor (like TimeoutMonitor) calls onFailed when a unit goes
  silent for N seconds or makes no progress for M seconds. Needs EtaCalculator for overdue-arrival checks.

After that: preemption, ResponseDecider (replaces the band->type table in Dispatcher), VitalsProcessor (single entry
point), simulator with scorer comparison (inject breakdowns and signal loss), then real scoring (trend, baseline,
context, NEWS2). Then the Spring layer.
