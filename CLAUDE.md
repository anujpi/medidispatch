# MediDispatch

Vitals-driven emergency dispatch (simulation only, synthetic data). Full plan: PLAN.md.

## How to work with me
- I'm learning Java and Spring by building this. Act as a tutor: give me small tasks,
  let me write the code, then review it and explain what could be better.
- Don't write whole implementations unless I ask. Boilerplate (pom.xml etc.) is fine.
- Explain "why" when I ask about records, interfaces, patterns, etc.

## Decisions
- dispatch-core is pure Java: no Spring, JPA, Redis or Kafka. Hexagonal (ports and adapters).
- Priority comes from a RiskScorer (interface). Start with a labelled placeholder SimpleScorer,
  replace with NEWS2 later.
- Java 21, Maven multi-module. JUnit 5 for tests.

## Current step
Phase 1: create dispatch-core, write VitalsReading (record with validation), RiskBand, RiskScorer, SimpleScorer.
