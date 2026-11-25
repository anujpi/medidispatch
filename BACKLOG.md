# Backlog

Known gaps in the engine (`dispatch-core`), to tackle later. None of them blocks the simulator or the Spring layer.
Ordered roughly by priority inside each group.

## Engine behaviour

- [x] **Upgrade the response when a case gets worse.** *Found by the simulator. Done: `ResponseUpgrader`.* A patient who
      deteriorates gradually first crosses into MEDIUM, so a nurse is sent; a minute later they are CRITICAL, but
      while a case is RESPONDING `CaseManager` only updates the band, so the nurse stays the only help and no
      ambulance is ever sent. When the band rises while RESPONDING, the engine should check the case's new
      `ResponsePlan`: if the current primary unit's type is not in the new plan's primary types, send a proper
      unit (keeping the nurse on as a first responder, or standing them down).
- [x] **Unit back in service.** *Done: `UnitTracker.onBackInService`, used by the live simulator after repairs.* A unit whose trip timed out or failed goes `OFFLINE` and never returns: there is no
      "back in service" call yet. Add `AssignmentManager`/`UnitTracker`-level `onBackInService(unitId)`
      (`OFFLINE -> AVAILABLE`). The simulator will show the fleet shrinking without it. *Small; do first.*
- [ ] **Head-of-line blocking in the dispatcher.** `dispatchNext()` only looks at the most urgent case. If that case
      cannot be served (for example a CRITICAL case and no ALS anywhere), less urgent cases behind it that *could* be
      served (a MEDIUM case and a free nurse) wait too. Fix: when the top case gets nothing, try the next ones in the
      queue before giving up for this round.
- [x] **ETA instead of straight-line distance.** Done in R4: a `TravelTime` port; dispatch ranks the nearest five by
      drive time, the first responder check and the hospital choice use it, and the pre-alert ETA is by road.
- [ ] **Still straight lines after R4.** The ring search itself (candidates by km), the support search radius (the
      primary's km) and the `Preemptor` still compare straight-line distance. Fine while the nearest five by km
      contain the fastest by road; revisit if a river or highway makes that false.
- [ ] **Re-route mid-leg.** A unit keeps the route it was given; a closure or jam added while it drives only affects
      legs planned afterwards. Re-plan the legs whose remaining route crosses a new incident (and tell the crew).
- [ ] **Store each trip's route.** The crew and the map ask `/api/route` separately (same answer in practice). When
      real crew apps exist, keep the route they follow on the trip and draw that.
- [ ] **Incidents are in memory and open.** Lost on restart; anyone who can reach the service can add one. Persist
      them and put them behind the operator role (security piece).
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

## Hospitals

- [ ] **Breakdown during transport.** A TRANSPORTING trip can only end as DONE: the trip monitor skips it and
      cancel/fail are refused. A real breakdown with the patient on board needs its own flow: send a second
      ambulance to where the broken one is (not the patient's home), move the patient over, then fail the first
      trip and take that unit offline.
- [ ] **A nurse asks for transport.** Only ambulances carry patients, so a nurse or doctor who finds the patient
      needs hospital care cannot start a transport. Add "request an ambulance" from the scene: requeue the case
      with an ambulance-only plan, with the nurse staying on as a first responder.

- [ ] **Hospital access control.** The hospital endpoints (diversion, adding units, fleet, inbound patients) will
      have no login at first, so any caller can act for any hospital. With roles (Spring Security, week 3), a
      hospital user may only touch its own facility, and only the operator may add facilities.
      Since B1 this also covers personal data: patient names, ages and emergency contacts
      (`GET /api/patients/{id}`), and drivers' phone numbers (in every unit view and the live feed). All synthetic
      today, but nothing stops anyone reading them until the security piece.

## Simulator and dev map

- [ ] **Simulator controls on the map.** Speed, pause and resume are typed commands in the simulator's console
      (M1b); the user wanted buttons. The map only talks to the service, so the simulator would need a small HTTP
      control endpoint (for example `POST /sim/speed`, `/sim/pause`, `/sim/resume` on its own port) and the map a
      Speed / Pause / Resume bar that calls it (CORS, or proxy through the service). About 30 minutes.
- [ ] **Pausing the city does not pause the service.** The service runs in real time, so crews on a trip time out
      after about 30 s of pause. A service-side "simulation paused" switch would fix it; the real system never
      pauses, so only if pausing becomes important.

## Web app

- [ ] **Generated API types.** `web/src/api/types.ts` is written by hand and can drift from the Java records;
      generate it from an OpenAPI description of the service.
- [ ] **One live feed per tab.** Each screen using `useLiveSnapshot` opens its own EventSource; share one
      through a React context once several components on a page need it.
- [ ] **Cream theme: "High" is close to the accent.** The high-priority orange and the burnt-orange accent are
      similar; shift one if it confuses anyone.

## Screens (after Step 1)

- [ ] **Children's hospitals take adults.** The engine sent a 77-year-old to Malleshwaram Children's Hospital:
      destination choice only checks the needed specialty. Give facilities an age range (or "children only") and
      skip it for adults, and vice versa.
- [ ] **The rest of the design canvas** ("MediDispatch screens"): medical record and reports, NEWS2 and usual
      readings, SBAR handover and treatments, beds and teams, "patient received", sign-in, crew quick entry.
- [ ] **Crew buttons vs the simulator.** Pressing a step on the crew phone for a simulated unit races the
      simulator driving that unit; harmless (steps are repeat-safe) but confusing in a demo. A per-unit "manual"
      switch in the simulator would hand a unit to a person.
- [ ] **Patient phone reads the whole live feed** to find its ambulance; after SEC it needs its own endpoint.

## Control room and overrides

- [ ] **Investigate: an override undone 3 seconds later (seen once, Nov 25).** On the live city a dispatcher's
      "send nurse-4" to a MEDIUM case (19:10:53 IST, 200 OK) was cancelled at 19:10:56 and the engine sent nurse-5
      (the unit it had just replaced) to the same case again. The case's first unit, nurse-1, had failed shortly
      before (a simulator-injected breakdown), and nurse-5 was the engine's re-dispatch. No other case got a unit
      that minute, and the only code that cancels a trip and requeues on its own is the preemptor, which would mean
      the case was still in the queue while RESPONDING. Two later overrides (one watched in the database every
      0.5 s for 30 s) held. Next step: reproduce with the service log open (look for "Override:" lines and anything
      between them and the cancel), or a core test: a case whose primary failed, re-dispatched, then overridden,
      then several dispatch rounds.
- [ ] **A quiet period after a cancel.** A cancelled case whose patient's readings stay bad reopens on the next
      reading (honest, but noisy for a known false alarm). A per-patient "ignore for N minutes" set by the
      dispatcher would stop it.
- [ ] **Take a busy unit from another case.** The dispatcher can only send a free unit; taking one from a less
      urgent case (manual preemption) needs the victim case requeued, like the engine's own preemption.
- [ ] **Eligible units from the server.** The "send another unit" list shows every free unit by straight-line
      distance; the service refuses the wrong type. A `GET /api/cases/{id}/candidates` ranked by drive time would
      list only the units that can serve the case.
- [ ] **Audit trail.** Overrides are an event and a log line; who did it, and a stored record, come with the
      login (SEC).
- [ ] **Control room map: incidents and a legend.** The dev map (map.html) shows road incidents and a legend;
      the control room's map has neither yet.
- [ ] **Alert sound, vitals in the case card, "unit not answering" alerts.**

## Small cleanups

- [ ] Delete `engine/ResponseTypes.java` (unused since the response ladder).
- [ ] `event/Checks`: messages lack a space (`"caseIdis required"`); use `" is required"`.
- [ ] `VitalsReading`: remove the leftover `//      }` line.
