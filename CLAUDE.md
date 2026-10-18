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

## Built so far (all with tests)
Domain: VitalsReading, RiskBand, RiskAssessment, Case/CaseStatus, Resource/ResourceStatus/ResourceType,
Location (haversine), Patient, Assignment (record for now).
Ports + in-memory adapters: CaseQueue, ResourceRepository, ResourceClaimer, PatientRepository, CaseRepository.
Engine: Dispatcher, CaseManager. Scoring: RiskScorer, SimpleScorer.
Known nit: `CaseRepository.findByOpenByPatientId` has an extra "By" in its name (rename to findOpenByPatientId).

## Current step
Make Assignment a real entity: AssignmentStatus (ASSIGNED, EN_ROUTE, ARRIVED, DONE, CANCELLED), Assignment class
with a guarded transitionTo (keep the 3-arg constructor), AssignmentRepository port + in-memory adapter, and have
Dispatcher save the assignment. Next after that: resource release and case completion, EventPublisher port,
timeouts and reassignment, preemption, ResponseDecider, VitalsProcessor, simulator, then real scoring (NEWS2).
