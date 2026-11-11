# Backlog

Known gaps in the engine (`dispatch-core`), to tackle later. None of them blocks the simulator or the Spring layer.
Ordered roughly by priority inside each group.

## Engine behaviour

- [ ] **Upgrade the response when a case gets worse.** *Found by the simulator; most important.* A patient who
      deteriorates gradually first crosses into MEDIUM, so a nurse is sent; a minute later they are CRITICAL, but
      while a case is RESPONDING `CaseManager` only updates the band, so the nurse stays the only help and no
      ambulance is ever sent. When the band rises while RESPONDING, the engine should check the case's new
      `ResponsePlan`: if the current primary unit's type is not in the new plan's primary types, send a proper
      unit (keeping the nurse on as a first responder, or standing them down).
- [ ] **Unit back in service.** A unit whose trip timed out or failed goes `OFFLINE` and never returns: there is no
      "back in service" call yet. Add `AssignmentManager`/`UnitTracker`-level `onBackInService(unitId)`
      (`OFFLINE -> AVAILABLE`). The simulator will show the fleet shrinking without it. *Small; do first.*
- [ ] **Head-of-line blocking in the dispatcher.** `dispatchNext()` only looks at the most urgent case. If that case
      cannot be served (for example a CRITICAL case and no ALS anywhere), less urgent cases behind it that *could* be
      served (a MEDIUM case and a free nurse) wait too. Fix: when the top case gets nothing, try the next ones in the
      queue before giving up for this round.
- [ ] **ETA instead of straight-line distance.** Ranking, ring search and "is the first responder closer" all use
      straight-line distance. Add an `EtaCalculator` port (fixed speed first, a routing API later in Spring).
- [ ] **Queue aging.** Low-urgency cases can wait forever if worse ones keep arriving. Let waiting time slowly raise
      a case's priority.
- [ ] **Hysteresis in `CaseManager`.** A patient hovering at a band boundary can flip band on every reading
      (MEDIUM, HIGH, MEDIUM...). Require a margin or several readings before changing band. Fits with NEWS2 scoring.
- [ ] **Failure and cancel reasons.** `FAILED` does not say why (lost signal vs stalled vs reported breakdown), and
      `CANCELLED` does not say preempted vs stood down vs cancelled by a person. A small reason enum on the trip
      (and in `AssignmentStatusChanged`) would help the simulator's statistics and the dashboard.
- [ ] **First responders, more cases.** Support is only considered when the primary is sent (a responder freed a
      minute later is not sent). Support for HIGH cases and trained volunteers (`COMMUNITY_RESPONDER`) were deferred.
- [ ] **Role in `AssignmentStatusChanged`.** The event does not carry the trip's role; listeners must remember it
      from `UnitAssigned`.

## Scoring

- [ ] **Real scoring.** Replace the placeholder `SimpleScorer` with NEWS2, then add trend (getting worse fast),
      the patient's own baseline, and context (conditions). `RiskScorer` makes it a drop-in swap.
- [ ] **Reading history queries.** `VitalsRepository` only has `latestFor`. Trend scoring will need "last N readings"
      or "readings since T".

## Reliability and concurrency (mostly solved by the Spring layer)

- [ ] **Non-atomic steps.** Recovery (end trip, then requeue) and preemption (cancel, then dispatch again) are two
      steps each. Real transactions come with Postgres; a unit hand-over inside one transaction fixes preemption.
- [ ] **Single dispatch loop.** Preemption assumes only one thread dispatches.
- [ ] **In-memory adapters are not thread-safe** (except the claimer). Fine for tests and the simulator.
- [ ] **`SafeEventPublisher`.** A decorator that logs and swallows publishing failures, so a broken event bus never
      breaks a dispatch. Designed but not added; the outbox pattern replaces it in Spring.
- [ ] **Reproducible ids.** Case and trip ids come from `UUID.randomUUID()`, so two simulator runs with the same seed
      can differ slightly when two cases tie in the queue. An id-generator port would make runs fully reproducible.

## Small cleanups

- [ ] Delete `engine/ResponseTypes.java` (unused since the response ladder).
- [ ] `event/Checks`: messages lack a space (`"caseIdis required"`); use `" is required"`.
- [ ] `VitalsReading`: remove the leftover `//      }` line.
