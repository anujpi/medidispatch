# MediDispatch: Master Plan

A patient-centric emergency dispatch system. Monitored people stream live vitals; the system turns them into a
risk score, decides what help each person needs, sends the right unit in priority order, picks the right hospital
and warns it before the patient arrives.

- **Submission:** second week of December 2026. Feature work ends **Dec 8**; Dec 9 onwards is buffer.
- **Goals:** learn LLD, system design and **computer networks** by building a real system; a portfolio piece with a
  clear story: *"Catch deterioration early and send the right help first."*

> **Safety framing (also in the README):** this is a **simulation and decision-support prototype**. It uses
> **synthetic patients and synthetic vitals only**. It is not a medical device and must never be used for real
> clinical decisions.

---

## 1. Where we are (Nov 20)

| Done | What it is |
|---|---|
| `dispatch-core` | pure-Java engine: scoring placeholder, cases, response ladder, ring search, claim, preemption, first responders, response upgrade, timeouts, trip monitoring, recovery, back in service |
| `dispatch-sim` | seeded 2-hour simulation + endless threaded live city (`LiveSimulation`) |
| `dispatch-sim-fx` | JavaFX window: live map, panel, report |
| tests | 612 (510 core, 63 sim, 22 fx, 17 service) |
| `dispatch-service` | Spring Boot app: `EngineConfig` wiring, `DispatchService` (one lock, 1 s dispatch loop, repeat-safe steps), REST API with ProblemDetail errors, SSE live feed (whole picture every second); in memory. Postgres next |

---

## 2. Decisions

| Decision | Choice | Why |
|---|---|---|
| Shape | **Modular monolith**: one Spring Boot app (`dispatch-service`), split by package | one deploy, one DB, real transactions; split only when there is a reason |
| Core | stays **pure Java**, wired with `@Bean` methods | hexagonal: Spring never enters the core |
| Database | **Postgres + PostGIS + TimescaleDB** (one server) | transactions for cases/trips/claims, spatial index for "nearest unit", time-series for vitals |
| Redis | **not now**; only for a measured need (e.g. live positions) | two sources of truth cannot share a transaction |
| Vitals history | kept, in tiers (section 5) | trend scoring now, a training set later |
| Frontend | **one React + TypeScript app (Vite)**, four faces, installable as a **PWA** | one codebase instead of web + Android + iOS |
| Map | **MapLibre + OpenStreetMap**; OSRM road ETAs later | free, no billing account |
| Live data | HTTP for commands, **SSE** for live screens, **Web Push** for crews (if time), **MQTT** later | each fits its network path (section 7) |
| Login | phone + **OTP** behind an `OtpSender` port (fake sender prints to the log), then a **JWT** with a role | no SMS account needed for the demo |
| Humans | a **control room** watches and can **override** every engine decision | no real service lets software dispatch unwatched |
| Work style | **two speeds**: *learn-deep* (spec, LLD, show code, user pastes) for parts the user must explain; *fast* (Claude writes, runs tests, then walks through) for routine parts | three weeks is not enough for everything at one speed |

---

## 3. Who uses it (the cast)

| Who | Needs | Device |
|---|---|---|
| **Patient** (and family) | SOS, "help is coming" + ETA, wearable status, emergency contacts | phone (PWA) |
| **Responder** (ALS/BLS crew, nurse, doctor) | new trip + Accept, navigation, Arrived, patient vitals, quick messages | phone in the vehicle (PWA) |
| **Control room** | live map, queue, alerts, override (reassign, cancel) | desktop |
| **Hospital** (emergency department) | incoming patients, arrival time, live vitals, SBAR handover | screen in the ED |
| **Admin** | register and verify units, doctors, hospitals | desktop |

---

## 4. Architecture

```
 wearable ──BLE──▶ patient phone ─┐
 (simulator plays it for now)     │ HTTPS
 crew phone (PWA) ────────────────┤ HTTPS  ◀── Web Push (if time)
 control room / hospital (web) ───┤ HTTPS  ◀── SSE live feed
                                  ▼
              ┌──────────── dispatch-service (Spring Boot) ────────────┐
              │ api: controllers, JSON records, errors, auth (JWT)     │
              │ DispatchService: the single front door to the engine   │
              │ dispatch loop (@Scheduled), live feed (SSE)            │
              │ adapters: Postgres / PostGIS / Timescale               │
              │ dispatch-core (pure Java, wired by EngineConfig)       │
              └──────────────────────────┬─────────────────────────────┘
                                         ▼
                      Postgres + PostGIS + TimescaleDB (one server)
```

Packages in `dispatch-service`: `com.medidispatch.service` (app, wiring, `DispatchService`),
`...service.api` (controllers, records), `...service.persistence` (database adapters), `...service.live` (SSE),
`...service.auth` (OTP, JWT).

**Concurrency:** step 1 uses one lock around the engine (same guarantee as `LiveEngine`, less code). With Postgres,
request threads work in their own transactions; one dispatch loop makes decisions; optimistic locking
(`@Version`) catches the rare clash.

---

## 5. Data design

| Data | Behaviour | Store |
|---|---|---|
| patients, units, hospitals | rarely change | plain tables |
| cases, trips | change often, all-or-nothing updates | plain tables, transactions; the claim is `UPDATE ... WHERE status = 'AVAILABLE'` |
| unit positions | overwritten every few seconds, "nearest to X" | PostGIS column + spatial (GiST) index |
| case queue | "most urgent waiting case" | an indexed query on WAITING cases, no separate store |
| vitals readings | append-only, heavy | TimescaleDB hypertable |
| events for others | write, then send | outbox table |

**Design load:** 50,000 monitored patients (one reading a minute, about 830 writes/s) and 500 units (a position
every 10 s). Vitals inserts are **batched**.

**Vitals retention:** 0–1 day raw; 1–30 days raw but compressed; 30 days–1 year only 5-minute aggregates
(continuous aggregate: the future training set); older deleted. Each case links to its patient and times, so
readings can be labelled with outcomes later.

---

## 6. The four faces (one app)

| Route | Main screens |
|---|---|
| `/control` | live map (patients by band, units by type, trips), case queue, alerts, unit list, case detail with override |
| `/crew` | waiting / new trip with one big **Accept**, trip (map, patient, live vitals, **Arrived**), quick messages, transport to hospital |
| `/patient` | **SOS**, status ("help is coming", ETA, unit on the map), wearable status, contacts |
| `/hospital` | incoming list (band, ETA), patient detail with live vitals and SBAR handover |

**PWA = website + manifest + service worker + HTTPS.** The service worker caches the app (opens with no signal),
queues requests made offline (store and forward) and receives push messages.
**Known limits:** no reliable GPS in the background (the crew app stays open on a mounted phone); iPhone web push
only after "Add to Home Screen"; Web Bluetooth only on Android Chrome.

---

## 7. Networking path (learn it while building it)

| Path | Technique | Lesson |
|---|---|---|
| crew taps Accept | HTTP POST | methods, status codes, headers, JSON, **idempotency** |
| server → live screens | **SSE** (one long-lived HTTP response the server keeps writing to) | TCP connections, SSE vs WebSocket vs polling, heartbeats |
| dashboard (5173) → API (8080) | | **CORS** |
| login | OTP → JWT over HTTPS | **TLS**, tokens, why secrets never go in URLs |
| phone on the LAN | HTTPS with a local certificate | certificates, why browsers demand HTTPS |
| server → crew phone, app closed | **Web Push** | **NAT**: why servers cannot reach phones directly |
| bad signal | retry with backoff, store and forward, "Slow 3G" in DevTools | latency vs bandwidth, packet loss |
| containers | docker-compose | **DNS**, ports, Docker networks |
| watching it | Wireshark, DevTools Network tab | seeing every packet of our own app |
| devices (later) | **MQTT** (QoS, "last will") | publish/subscribe on unreliable links |

---

## 8. The smart parts

**Early warning (predict before critical):**
1. **NEWS2** replaces `SimpleScorer` (published UK score, cite the source).
2. **Trend:** slope over the last 30–60 min; a fast fall raises priority before the band changes.
3. **Personal baseline:** compare each person with their own normal (later).
4. **ML** on the stored history (after submission).

"Getting worse" climbs a ladder, not straight to an ambulance: **watch closer → ask "are you OK?" → alert family or
a remote doctor → send a unit**. Hysteresis and cooldowns prevent alarm fatigue.

**Hospitals:** registry (location, capabilities such as cath lab, stroke unit, trauma, paediatrics; ED load);
destination = nearest hospital **that can treat this case**; trips get a second leg (scene → hospital);
**pre-alert** with live vitals; handover in **SBAR** format. Real integration standard: **HL7 FHIR** (later export).

**Weak networks:** silence is not failure. New **"no contact"** state: try another channel, send a backup while the
first unit continues (hedged dispatch), fail only much later. Crew app stores positions offline and sends them with
their original times. Repeated commands are harmless (the engine already refuses repeated transitions).

**Language (ASD-STE100 ideas):** every responder-facing text uses short sentences, one meaning per word, commands
("Patient is not breathing. Start CPR."). Structured one-tap messages instead of free chat. Easier to translate.

**Calls and chat:** no free chat; one-tap structured messages. "Call" opens the phone dialer (`tel:`); number masking
later. **Doctors:** sign up, licence number, admin approval, on/off duty, remote (telemedicine) help.

---

## 9. Design rules (not "AI-looking")

- Colour means **severity only** (band colours); everything else is calm neutral. Control room is dark and dense.
- Crew screens: **one primary action per screen**, huge tap targets, readable at a glance.
- No gradients, glass blur, emoji icons, marketing heroes or vague copy.
- References: **NHS** and **GOV.UK** design systems, **IBM Carbon** (dense dashboards), real shipped apps on
  **Mobbin** (driver apps for the crew flow), the book *Refactoring UI*, air-traffic and patient-monitor screens.
- Process: paper flow → rough layouts (Google Stitch is fine here) → apply one design system → compare with a real
  control-room or driver-app screenshot.

---

## 10. Three-week build plan

`(L)` = learn-deep, `(F)` = fast. Every week ends with something runnable.

### Week 1, Nov 19–25: the backend becomes real
- [x] `EngineConfig` + application class + startup test (F)
- [x] `DispatchService`: the single front door, the lock, the dispatch loop (L)
- [x] REST: request/response records, controllers, error handling (F) — HTTP + idempotency lesson (L)
- [x] SSE live feed for screens (L)
- [ ] Postgres + PostGIS + TimescaleDB in docker-compose; Flyway schema (L for the schema, F for the rest)
- [ ] database adapters for the ports, Testcontainers tests (L for the claim and nearest search, F for the rest)
- [ ] hospitals: domain, registry, destination choice, second trip leg, pre-alert data (L)
- [ ] the simulator drives the service over HTTP (F)

### Week 2, Nov 26–Dec 2: the screens
- [ ] React + TS + Vite app, routes for the four faces, API client, design tokens (F) — CORS lesson (L)
- [ ] control room: map, queue, alerts, override, live via SSE (F for the UI, L for the live data)
- [ ] OTP login, JWT, roles (L)
- [ ] crew PWA: manifest, service worker, trip flow, GPS, vitals, quick messages (F) — TLS on the LAN lesson (L)
- [ ] hospital panel: incoming, ETA, vitals, SBAR (F)

### Week 3, Dec 3–8: the smart parts and the finish
- [ ] NEWS2 scorer (L)
- [ ] trend analysis + early-warning ladder (L)
- [ ] "no contact" state instead of an instant fail (L)
- [ ] patient PWA: SOS, status, ETA (F)
- [ ] docker-compose for the whole stack (F) — Docker networking lesson (L)
- [ ] design pass, README, demo video (F)
- [ ] weak-network test (DevTools Slow 3G) and a Wireshark session (L)

### Dec 9 to submission
Buffer, fixes, rehearsal. **The demo:** laptop shows the control room, a phone runs the crew PWA, a patient turns
critical, the phone shows the trip, Accept, the unit moves on the big screen, the hospital panel shows the pre-alert.

---

## 11. Designed, built if time allows

Each is designed above and goes in the README as "next":
Web Push · MQTT for devices and crews · SMS fallback · Bluetooth pulse oximeter through the patient PWA ·
FHIR export · doctor onboarding and telemedicine · OSRM road ETAs · personal baselines · ML on the vitals history ·
number-masked calls · head-of-line blocking, queue aging, hysteresis (BACKLOG.md) · Redis for live positions if
measured · Kafka + outbox · Kubernetes · Prometheus/Grafana.

---

## 12. Testing

| Layer | Tools |
|---|---|
| core logic, scoring tables, trends | JUnit, fake clock |
| concurrency (one unit, many claimers) | JUnit + threads; repeated against real Postgres |
| database adapters | Testcontainers |
| REST contracts, roles | MockMvc |
| whole system | the simulator driving the running service |
| weak networks | DevTools throttling, Wireshark |

---

## 13. Risks

| Risk | Mitigation |
|---|---|
| Presenting it as real medicine | disclaimer everywhere, synthetic data only |
| Inventing clinical logic | NEWS2 with cited sources |
| Running out of time | build in the order above; each week ends runnable; unbuilt items are documented as designed |
| Last-week surprises | features end Dec 8, buffer after |
| UI looking generic | design rules in section 9, real-world references |
