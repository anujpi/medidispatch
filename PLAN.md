# MediDispatch: Vitals-Driven Emergency Response (Full Plan)

A patient-centric dispatch system. Registered, monitored people stream live vitals. The system turns those
vitals into a continuously updated **risk score**, decides **what level of response** each person needs,
and dispatches the right resource (nurse call, medicine delivery, ambulance) in priority order.

- **Stack:** Java 21, Spring Boot / Spring Cloud, Postgres, Redis, Kafka, Docker, Kubernetes
- **Goal 1:** learn LLD, HLD and scalable system design properly (streaming ingestion, scoring, dispatch)
- **Goal 2:** a portfolio / mini-project piece with a clear societal story: *"Catch deterioration early and send the right help first."*
- **Headline metrics:** time from deterioration to dispatch, time to arrival, and scoring accuracy against simulated ground truth

> **Safety framing (put this in the README):** this is a **simulation and decision-support prototype**.
> It uses **synthetic patients and synthetic vitals only**. It is not a medical device and must never be used
> for real clinical decisions.

---

## 0. Decisions already made

| Decision | Choice |
|---|---|
| Product | **Not a generic engine.** Patient-centric: priority comes from live vitals and patient context |
| Who the patients are | **Registered, monitored people** (elderly, cardiac, chronic conditions) with continuous vitals. Assumed default; one-off call-in triage is a later extension |
| Scoring | Use **established clinical scores** (NEWS2 first), never an invented formula |
| Responses | Escalation ladder: self-care prompt > nurse/tele-call > medicine delivery > ambulance |
| Core | **Pure Java module** (no Spring/JPA/Redis/Kafka), hexagonal architecture |
| Services | Start with a modular `dispatch-service`, split only where load demands |
| Data | **Synthetic only**, produced by a patient simulator |
| Repo | New monorepo, separate from the `spring-microservice` sandbox |
| Concurrency (initial) | One dispatcher loop per partition. Multi-worker is a later experiment |

Open decisions: call-in triage as a second intake path, and single-loop vs multi-worker dispatcher.

---

## 1. Core concepts

### Domain
- **Patient:** `id, age, conditions[], medications[], allergies[], baselineVitals, location, consent`
- **VitalsReading:** `patientId, timestamp, heartRate, spo2, systolicBp, diastolicBp, respRate, temperature, consciousness (ACVPU), source, quality`
- **RiskAssessment:** `patientId, score, band (LOW/MEDIUM/HIGH/CRITICAL), contributingFactors[], trend, computedAt, scorerVersion`
- **Case:** an open episode for a patient, with `status, band history, openedAt`
- **Response:** the action taken (`type: PROMPT | NURSE_CALL | MED_DELIVERY | AMBULANCE`), plus its own lifecycle
- **Resource:** ambulance (ALS/BLS), nurse, courier, with `capabilities, location, status`
- **Hospital:** `departments, bed capacity, specialties`

### Priority is live, not fixed
```
Vitals stream > Validate/clean > Score > Update risk + trend > Decide response level > Queue/re-rank > Dispatch > Monitor
```
A case can climb the queue while it waits. A resource already en route can be redirected if a worse case appears.

### States
- **Case:** `MONITORING > ALERTED > RESPONDING > STABILISED > CLOSED` (plus `ESCALATED`, `CANCELLED`)
- **Response:** `PENDING > OFFERED > ACCEPTED > EN_ROUTE > ARRIVED > DONE` (plus `REJECTED`, `TIMED_OUT`, `PREEMPTED`)
- **Resource:** `AVAILABLE`, `ASSIGNED`, `OFFLINE`

---

## 2. Scoring design (the heart of the project)

Do not invent medicine. Build a pluggable `RiskScorer` and start from published, documented scores.

- [ ] **NEWS2** as the first scorer: respiratory rate, SpO2 (scale 1, optional scale 2 for hypercapnic patients), supplemental O2, systolic BP, pulse, consciousness (ACVPU), temperature
- [ ] **qSOFA** (sepsis screen) and/or **MEWS** as alternative scorers to compare
- [ ] **Context modifiers**: age, chronic conditions (cardiac, COPD, diabetes), medications (for example beta blockers mask heart rate)
- [ ] **Trend analysis**: rate of change over a sliding window. A rapidly rising score outranks a high but stable one
- [ ] **Personal baseline**: compare to the patient's own normal, not only population ranges
- [ ] **Explainability**: always store the contributing factors for each score ("SpO2 88 contributed 3 points")
- [ ] **Bands and thresholds** mapped to response levels, configurable
- [ ] **Scorer versioning**: store `scorerVersion` with every assessment so results are reproducible

Compare scorers in the simulator: NEWS2 alone vs NEWS2 + context + trend.

---

## 3. Target architecture

```
        Wearable/Patient simulator
                   |
        ingestion-service ----> Kafka: vitals-readings
                                   |
                          scoring-service (uses scoring module)
                                   |
                         Kafka: risk-assessments
                                   |
   api-gateway (JWT)        dispatch-service (uses dispatch-core)
        |                      |            |
   patient-service        fleet-service   hospital-service
        |                      |            |
     Postgres           Redis GEO + PG    Postgres
                                   |
                        Kafka: response-events
                                   |
              tracking-service      notification-service
                       \               /
                        dashboard (live map + patient board)

platform: naming-server (Eureka), config-server (later compared with K8s equivalents)
```

### Repo layout
```
medidispatch/
  dispatch-core/         pure Java: domain, scoring, response decision, dispatch, ports, in-memory fakes
  dispatch-service/      Spring Boot: REST, adapters (Redis/PG/Kafka), wiring
  ingestion-service/     vitals intake, validation, cleaning
  scoring-service/       runs RiskScorer on the stream (or embedded in dispatch-service at first)
  patient-service/       registration, conditions, consent, baselines
  fleet-service/         resources, status, live location
  hospital-service/      capacity, departments, destination choice
  tracking-service/      live position + ETA, websocket
  notification-service/  alerts to patient, family, dispatcher, responder
  platform/              api-gateway, naming-server, config-server
  common/                shared event + DTO definitions
  simulator/             patient vitals simulator + resource simulator
  dashboard/             live patient board + map
  deploy/                docker-compose.yml, k8s/
  docs/                  architecture, state machines, scoring spec, ADRs, API specs
  PLAN.md
```

---

## 4. dispatch-core design (pure Java)

The core contains everything that is *logic*. Frameworks and infrastructure sit outside, behind ports.

### 4.1 Modules inside the core
- **domain:** entities, value objects, enums, state machines, domain events
- **scoring:** `RiskScorer` interface and implementations, trend calculator, baseline comparer
- **triage / response decision:** maps a risk assessment (plus patient context) to a response level
- **dispatch:** queue, candidate finder, assignment, preemption, timeouts, reassignment

### 4.2 Strategies
- [ ] `RiskScorer`: `News2Scorer`, `QSofaScorer`, `ContextAdjustedScorer`
- [ ] `TrendAnalyzer`: sliding window, slope, spike detection
- [ ] `ResponseDecider`: band + context > response type (the escalation ladder)
- [ ] `FilterStrategy`: `CapabilityFilter` (ALS vs BLS, nurse vs courier), `AvailabilityFilter`, `RangeFilter`
- [ ] `RankingStrategy`: `EtaRanking`, `NearestRanking`
- [ ] `AssignmentPolicy`: `InstantAssign` (critical), `OfferWithTimeout` (non-critical)
- [ ] `FailurePolicy`: `ReassignImmediately`, `RetryThenEscalate`
- [ ] `PriorityPolicy`: orders cases by risk, trend and wait time, with aging so no case is starved
- [ ] `DataQualityPolicy`: how to treat missing, stale or implausible readings

### 4.3 Ports (interfaces owned by the core)

| Port | Purpose | Real adapter | Test fake |
|---|---|---|---|
| `CaseQueue` | enqueue / reprioritise / pollNext / remove | Redis sorted set | in-memory priority queue |
| `PatientRepository` | get patient + baseline | Postgres | in-memory map |
| `VitalsStore` | recent readings for trend windows | Redis stream / timeseries | in-memory ring buffer |
| `ResourceRepository` | nearby / get / save | Redis GEO + Postgres | in-memory map |
| `ResourceClaimer` | atomic `tryClaim(resourceId, caseId)` | Redis Lua / optimistic lock | `synchronized` map |
| `EventPublisher` | publish domain events | Kafka via outbox | list collector |
| `Clock` | now() | system clock | controllable fake |
| `TimeoutScheduler` | callback at time T | scheduled executor / delay queue | manual tick |
| `EtaCalculator` | ETA between two points | straight-line, later routing API | fixed speed |

### 4.4 Engine components
- [ ] `VitalsProcessor`: validate, clean, store, trigger scoring
- [ ] `RiskEngine`: runs the scorer, computes trend, produces a `RiskAssessment`
- [ ] `CaseManager`: opens, updates, escalates and closes cases from assessments
- [ ] `Dispatcher` (the loop): poll highest-priority case, find candidates, filter, rank, claim, assign
- [ ] `ReprioritisationService`: re-ranks the queue when a case's risk changes
- [ ] `PreemptionManager`: a critical case may redirect a resource serving a lower-risk one, and the displaced case is re-queued
- [ ] `TimeoutMonitor` and `ReassignmentHandler`
- [ ] `AlertDeduplicator`: avoids alert storms and flapping between bands (hysteresis)
- [ ] `IdempotencyGuard`

### 4.5 Rules to keep
- No framework annotations in the core. Wiring happens in `dispatch-service`.
- Ports are **coarse-grained**, not chatty.
- Atomic claim is a **storage guarantee** (port), never `synchronized` in the core.
- Scoring is **pure and deterministic**: same inputs, same output. That makes it testable and auditable.

---

## 5. Cross-cutting concerns specific to health data

- **Data quality:** handle sensor dropouts, implausible values (SpO2 0), stale readings, and clock skew explicitly
- **Alert fatigue:** hysteresis, cooldowns, deduplication so the system does not spam
- **Fairness and bias:** age and conditions must raise appropriate attention, never silently lower it. Document how and test for it
- **Privacy and security:** treat as real health data even though it is synthetic: encryption in transit and at rest, role-based access, audit log for every access to a patient record, consent flag
- **Auditability:** every score, decision and dispatch is stored with its inputs and the scorer version
- **Failure modes:** what happens if scoring is down? (fallback to last known risk plus a conservative default, never silence)

---

## 6. Step-by-step plan

Rough total: **14 to 18 weeks** solo. Every phase must end with something runnable.

### Phase 0: Design (about 1 week)
1. Create the new repo and folder layout.
2. Write the problem statement, goals, and the safety framing.
3. Read and summarise the NEWS2 spec. Write `docs/scoring-spec.md` with the exact tables you will implement.
4. Draw state machines for Case, Response and Resource.
5. Define the domain model, including `VitalsReading` fields and valid ranges.
6. Define the ports list with signatures on paper.
7. Define the escalation ladder: which band triggers which response.
8. Sketch the architecture and the Kafka topics and events.
9. Write the first ADRs: pure-Java core, established scores, synthetic data only, modular dispatch first.

**Done when:** the scoring spec, diagrams and ADRs exist and you can explain every transition and threshold.

### Phase 1: Scoring module in pure Java (about 2 weeks)
1. Create the `dispatch-core` Maven module (no Spring deps).
2. Implement `Patient`, `VitalsReading`, `RiskAssessment` and the value objects with validation.
3. Implement `News2Scorer` exactly per the spec, with table-driven tests (known inputs and expected scores).
4. Implement `DataQualityPolicy` (missing, stale and implausible readings).
5. Implement `TrendAnalyzer` (sliding window, slope).
6. Add personal baseline comparison and context modifiers (age, conditions, medications).
7. Produce explainable output (contributing factors per score).
8. Add `QSofaScorer` as a second scorer to prove the interface works.

**Done when:** a pure JUnit suite scores hundreds of known cases correctly and explains each score.

### Phase 2: Response decision and dispatch in the core, in-memory (about 3 weeks)
1. Define all ports and write in-memory fakes (including a fake `Clock`).
2. Implement `ResponseDecider` (band + context > response type).
3. Implement `CaseManager` with the Case state machine and hysteresis.
4. Implement `CaseQueue` ordering by risk, trend and wait time, with aging.
5. Implement `Dispatcher`: candidate finding, filtering (ALS/BLS, nurse), ranking, atomic claim, assignment.
6. Add `TimeoutMonitor` and `ReassignmentHandler` (driven by the fake clock).
7. Add `ReprioritisationService`: a deteriorating patient jumps the queue.
8. Add `PreemptionManager`: redirect a lower-risk response for a critical case.
9. Write the concurrency test: many threads, one resource, exactly one claim wins.
10. Add the second response flow, **medicine delivery**, to confirm the response abstraction holds.

**Done when:** a plain JUnit test runs thousands of simulated patients through scoring and dispatch in seconds, including deterioration, timeouts and preemption.

### Phase 3: Patient and resource simulator (about 1.5 weeks)
1. Build a **patient simulator** that generates realistic vitals trajectories: stable, slowly deteriorating, sudden cardiac or respiratory event, sensor glitch, and recovery.
2. Build a **resource simulator** for moving ambulances, nurses and couriers.
3. Drive `dispatch-core` directly (no network).
4. Record metrics: time from deterioration to alert, time to dispatch, time to arrival, false alerts, missed deteriorations.
5. Compare scorers: NEWS2 only, NEWS2 + context, NEWS2 + context + trend. Save the numbers.

**Done when:** you have a results table proving that context and trend improve early detection.

### Phase 4: First real services, synchronous (about 3 weeks)
1. Create the Spring Boot modules: `dispatch-service`, `patient-service`, `fleet-service`.
2. Set up Postgres per service with Flyway migrations.
3. Implement Redis adapters: GEO for `ResourceRepository`, sorted set for `CaseQueue`, Lua for `ResourceClaimer`, a stream or timeseries for `VitalsStore`.
4. Wire `dispatch-core` into `dispatch-service` through a `@Configuration` class.
5. Build REST APIs: register patient, submit vitals, query risk and case status, update resource status and location.
6. Reuse the existing gateway, Eureka and config server from your sandbox.
7. Add JWT auth with roles (patient, caregiver, dispatcher, responder, admin).
8. Add the audit log for patient-data access, the global error model, validation and correlation IDs.
9. Integration tests with Testcontainers (Postgres and Redis).

**Done when:** posting deteriorating vitals for a patient opens a case and assigns a real unit through Redis, and the double-claim test passes against real Redis.

### Phase 5: Healthcare layer (about 2 weeks)
1. Create `hospital-service` (capacity, departments, bed counts).
2. Add hospital selection after pickup: department match, capacity, distance.
3. Extend the ambulance flow into two legs: unit to patient, then patient to hospital.
4. Add suitability rules (ALS vs BLS, equipment).
5. Add Resilience4j (circuit breaker, timeout, retry) on service-to-service calls.
6. Add the scoring-down fallback: last known risk plus a conservative default.

**Done when:** a critical patient gets a suitable unit and a suitable hospital end to end, and the system degrades safely if scoring is unavailable.

### Phase 6: Event-driven streaming (about 3 weeks)
1. Add Kafka. Topics: `vitals-readings`, `risk-assessments`, `case-events`, `response-events`, `location-updates`, `notification-requests`, plus dead-letter topics.
2. Build `ingestion-service` for high-volume vitals intake and validation.
3. Run scoring as a stream consumer, partitioned by patient ID so each patient's readings stay ordered.
4. Implement the **outbox pattern** so DB changes and events commit together.
5. Make all consumers idempotent. Add retry and dead-letter handling.
6. Build `tracking-service` (live position and ETA, websocket) and `notification-service` (patient, caregiver, dispatcher, responder).
7. Chaos test: kill a service mid-flow and confirm no lost or duplicated cases or assignments.

**Done when:** vitals flow as a stream through scoring to dispatch, and the system survives restarts without losing or duplicating anything.

### Phase 7: Containers and Kubernetes (about 2 weeks)
1. Dockerfile per service (multi-stage, small images).
2. `docker-compose.yml` for the full stack (services, Postgres, Redis, Kafka).
3. Kubernetes (kind or minikube): Deployments, Services, ConfigMaps, Secrets, probes.
4. Ingress or the gateway as the entry point.
5. **HPA** for ingestion and scoring, and prove it scales under simulator load.
6. Compare K8s service discovery and config with Eureka and Config Server and write an ADR.
7. CI with GitHub Actions (build, test, image build).

**Done when:** `kubectl apply` brings up the system and HPA reacts to a load spike.

### Phase 8: Observability, scale and demo (about 2.5 weeks)
1. Prometheus and Grafana: ingestion rate, scoring latency, queue depth, time-to-dispatch, alert counts, false alert rate.
2. Distributed tracing across services (OpenTelemetry and Zipkin).
3. Load test (k6 or Gatling): thousands of simulated patients streaming vitals. Find and fix the real bottleneck.
4. Experiment with partitioning dispatch by region. Optionally try multi-worker dispatch.
5. Build the dashboard: a live patient board (risk, trend, band), a map with units, and a score explanation panel.
6. Polish the README: problem, safety framing, architecture, how to run, results table.
7. Record a 3 to 5 minute demo video: a patient deteriorates, the score climbs, the queue re-ranks and help is dispatched.

**Done when:** you can demo live and show measured detection and response-time numbers.

### Stretch
- Call-in triage as a second intake path (one-off vitals or symptoms)
- Predictive deterioration (a simple ML or rule-based model compared with NEWS2)
- Pre-positioning ambulances toward high-risk zones
- Medication-aware scoring and interaction checks
- Real routing and ETA API
- Family and caregiver mobile view

---

## 7. Testing strategy

| Layer | What | Tools |
|---|---|---|
| Scoring unit | NEWS2 tables, edge values, missing data, baselines | JUnit 5, table-driven tests |
| Trend and quality | windows, spikes, glitches, stale data | JUnit, fake Clock |
| Dispatch unit | queue ordering, preemption, timeouts, reassignment | JUnit, fake Clock |
| Concurrency | many threads claim one resource | JUnit, ExecutorService |
| Adapters | Redis, Postgres, Kafka behaviour | Testcontainers |
| Service | REST contracts, auth roles | MockMvc / RestAssured |
| System | full flow in compose or K8s | patient simulator |
| Load | ingestion throughput and latency | k6 or Gatling |
| Fairness | same vitals across ages and conditions give sensible outcomes | scenario tests |

---

## 8. Tech checklist

- [ ] Java 21, Maven multi-module
- [ ] Spring Boot, Spring Cloud (gateway, eureka, config, openfeign)
- [ ] Postgres, JPA, Flyway
- [ ] Redis (GEO, sorted set, streams or timeseries, Lua)
- [ ] Kafka (partitioning by patient, outbox, DLQ)
- [ ] Resilience4j
- [ ] OpenTelemetry + Zipkin, Prometheus, Grafana
- [ ] Docker, docker-compose, Kubernetes (kind or minikube), HPA
- [ ] Testcontainers, JUnit 5, k6 or Gatling
- [ ] GitHub Actions

---

## 9. Docs to produce

- [ ] Scoring spec (exact NEWS2 tables, modifiers, thresholds, sources)
- [ ] State machine diagrams
- [ ] Architecture diagram (logical and deployment) and the port/adapter diagram of the core
- [ ] Data flow diagram of the vitals pipeline
- [ ] OpenAPI specs per service
- [ ] ADRs (one page each)
- [ ] Safety, privacy and fairness note
- [ ] README with demo steps and results
- [ ] Results table: scorer comparison, detection latency, load test numbers

---

## 10. Risks and how to handle them

| Risk | Mitigation |
|---|---|
| Presenting it as real medicine | Prominent simulation and decision-support disclaimer, synthetic data only |
| Inventing clinical logic | Use published scores and cite sources in the scoring spec |
| Over-scoping | Every phase ends runnable. Cut stretch items first |
| Microservices too early | Core, scoring and dispatch stay together until load says otherwise |
| Alert storms and flapping | Hysteresis, cooldowns, deduplication from Phase 2 |
| Double-assignment bugs | Concurrency test in Phase 2, repeated against real Redis in Phase 4 |
| Out-of-order or lost vitals | Partition by patient, idempotent consumers, timestamps and sequence numbers |
| No demo story | Patient simulator in Phase 3, dashboard before the end, not last week |
| Time slip | Phases 0 to 4 alone already make a strong project |

---

## 11. Minimum viable portfolio (if time runs short)

Phases 0 to 4 plus the demo from Phase 8 (simulator results, short video, README).
That gives you: a pure-Java scoring and dispatch core, a measured comparison of scoring strategies,
proven concurrency safety, and a working service with live re-prioritisation.

---

## 12. First actions (next 3 days)

1. Create the new repo `medidispatch` with the folder layout.
2. Read the NEWS2 specification and write `docs/scoring-spec.md`.
3. Draw the Case, Response and Resource state machines.
4. Write the ports as Java interfaces (signatures only) in `dispatch-core`.
