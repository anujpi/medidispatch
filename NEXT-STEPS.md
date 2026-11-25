# MediDispatch: where we are and what comes next

A handover note for picking the work up on another computer or account. Read this first, then CLAUDE.md (the detailed
project notes), PLAN.md (the plan) and BACKLOG.md (known gaps). Written 25 Nov 2026.

## 1. How we work (tell Claude this on the new machine)

These rules were kept in Claude's memory on the old computer, which does not move with the repo. Paste this section
into the first message on the new machine, or keep it in CLAUDE.md.

- **Two speeds.** PLAN.md tags each item (L) or (F).
  - **(L) learn-deep:** Claude explains what we are doing and why, with a concrete example; then the spec (classes,
    methods, packages); then the design (LLD) and the decisions for me. Then it STOPS.
    When I say **"show"**, Claude shows the code in chat **without comments**, and uses no tools at all.
    When I say **"write it"**, Claude writes the files **with Javadoc and comments**, writes the tests, and runs them.
  - **(F) fast:** Claude says in one line what it is about to do and waits for my ok. Then it writes the files and
    the tests, runs them, and walks me through what it did.
- **I commit.** Claude never commits. When I ask, it gives me a one-line commit message.
- **"update docs"** means updating CLAUDE.md, README.md, PLAN.md and BACKLOG.md.
- **Java style:** explicit lambdas (no `::` method references) and plain loops. Proper Javadoc on every main class.
- Work in small pieces: one class or layer at a time. I create Maven modules myself.
- Don't pace the work by PLAN.md's week dates.
- Ask before downloading anything, before starting Docker or other apps, and never stop the Windows Postgres service.

## 2. Setting up the new laptop

1. Install JDK 21 (IntelliJ IDEA's bundled JDK works), Docker Desktop and Node.js (LTS).
2. Clone the repo. Then copy **`data/bangalore.graph`** (15.7 MB) from the old laptop into `data/`. It is
   git-ignored, so it does not come with the clone. Without it the service still runs, but uses straight lines
   instead of roads (it logs a warning). To rebuild it instead: download the roads again (the Overpass query is in
   CLAUDE.md, "Routing") and run `ImportBangalore` in `dispatch-routing`.
3. Database: `docker compose up -d` in the project folder (Postgres with PostGIS and TimescaleDB on port **5433**).
   To start a fresh city: `docker compose down -v`, then `docker compose up -d`.
4. Build and test: `mvn install` (the first run downloads the dependencies; after that `mvn -o install` works
   offline). The database tests need Docker running.
5. Run the service: `DispatchServiceApplication` in `dispatch-service` (port 8080).
6. Run the city: `HttpSimulatorMain` in `dispatch-sim`. Type a number to set the speed, `p` to pause, `r` to resume,
   `s` for the status and `q` to quit.
7. Web app: `cd web`, `npm install`, `npm run dev`, then open http://localhost:5173. `npm test` runs the web tests.

Useful pages with the city running: `/control` (dispatcher), `/control/patients/p001` (a patient file),
`/hospital/blr-h-jayanagar`, `/crew/als-1`, `/patient/p001`. The old debug map is at http://localhost:8080/map.html.

## 3. Done so far

- **Engine (core):** vitals, scoring (a placeholder scorer for now), cases, units, trips, dispatch with atomic claims,
  preemption, first responders, timeouts, transport to hospital, hospital choice.
- **Service:** REST API, live feed (server-sent events), Postgres + PostGIS + TimescaleDB.
- **Simulator:** a Bangalore city driving the service over HTTP.
- **Routing:** our own road graph, A*, a traffic model; dispatch by drive time; road routes and incidents.
- **Web app:**
  - A1: the skeleton and three colour themes.
  - A2: control room with map, alerts, case card and operator overrides (cancel, send a unit, out of service).
  - Screens Step 1: patient file with 6-hour vitals trends, hospital arrivals with a diversion switch, crew phone
    with working steps, patient phone. New endpoint `GET /api/patients/{id}/vitals?hours=6`.
- Tests: about 1,050 Java and 49 web, all passing at the time of writing.

## 4. What comes next, in order

### Step 2: the medical record and NEWS2 (L)

- Store on each patient: blood group, allergies (what and the reaction), conditions (with the year), medicines (name,
  dose, when), and reports (title, date, finding; a file upload comes later).
- Database migration, API, and the simulator filling these in for its patients.
- **NEWS2 in the engine:** replace the placeholder scorer with the real NEWS2 (PLAN.md, week 3, learn-deep). The
  patient file then shows the score, the points per vital sign and the patient's usual readings.
- The patient file's "Medical record: not recorded yet" panel becomes the real record.

### Step 3: login and roles (SEC, L)

Phone number, then a 6-digit code (printed in the log for now, SMS later), then a signed token (JWT) with a role.
Roles: operator, hospital, crew, device. A hospital sees only its own patients; a crew only its own unit.

**Five decisions are still open. Answer them before "show":**
1. No user table yet: hospitals log in with the hospital's phone, crews with the driver's phone, operators from a
   config list. Real staff accounts go to BACKLOG. OK?
2. The operator may run the crew steps (accept, arrive, complete), so the simulator needs one operator login plus a
   device key instead of 20 crew logins. OK?
3. `map.html` (our debug map): retire it, since the control room covers it, or give it a login too? Claude
   recommends retiring it.
4. A token lasts one 12-hour shift, with no refresh or revocation yet (BACKLOG). OK?
5. HTTPS is not part of this step; it comes with the crew app on the LAN. OK?

Planned split: **SEC1** = login everywhere (the simulator and the web app log in; all roles may still do
everything); **SEC2** = role rules, hospital and crew scoping, personal data only for the roles that need it.

### Step 4: the hospital's tools

- The crew writes the handover in SBAR order (situation, background, assessment, recommendation), plus treatments
  given in the ambulance.
- Hospital beds and teams (emergency beds, resus bays, ICU, cath lab, stroke team).
- A "patient received" step that closes the case and frees the crew.

### After that

- The installable crew app (PWA): GPS, offline use, quick messages.
- Docker Compose for the whole stack; the weak-network test; the demo video.

## 5. Open issues to know about

- **An override was undone once** (BACKLOG, "Control room and overrides"): a dispatcher's "send another unit" was
  cancelled 3 seconds later and the engine re-sent its own choice. Seen once, not reproduced since.
- **A children's hospital was given an adult patient** (BACKLOG, "Screens"): hospital choice has no adults/children
  rule yet. Fix before showing it to a real hospital.

## 6. The design canvas

The clickable design of every screen ("MediDispatch screens") was made as a claude.ai artifact on the **old
account**: https://claude.ai/artifact/NxEN5uV5sRWHTV5DZXCkpD. It is private, so the new account cannot open it.
Before switching, either share it with the new account from the page's Share menu, or rely on the copy in this repo.

Its source files are copied into `design/canvas/` (one `.dc.html` per screen plus `canvas.json`). They only render
inside the artifact's design editor, but they hold the exact layouts, colours, text and sample data, so the screens
can be rebuilt from them. The story in them: Asha Rao, 68, possible sepsis, NEWS2 13, taken by als-3 to Jayanagar
Heart and Stroke Centre.
