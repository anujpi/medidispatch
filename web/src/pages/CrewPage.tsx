import { useState } from 'react'
import { Ambulance } from 'lucide-react'
import { useParams } from 'react-router'
import { ApiError, getJson, postJson } from '../api/client'
import type { Capability, CaseInfo, FacilityInfo, TripInfo, UnitInfo, VitalsReading } from '../api/types'
import { useDriveMinutes } from '../api/useDriveMinutes'
import { usePolling } from '../api/usePolling'
import { AppShell } from '../components/AppShell'
import { ErrorPanel } from '../components/ErrorPanel'
import { TriageTag } from '../components/TriageTag'
import { VitalsGrid } from '../components/VitalsGrid'
import { NEED, UNIT_TYPE } from '../labels'
import styles from './pages.module.css'
import css from './screens.module.css'

/** How often the crew's call is asked for again. Every step is safe to repeat, so a quick poll is harmless. */
const CREW_POLL_MS = 3_000

const NEEDS: Capability[] = ['CARDIAC', 'STROKE', 'TRAUMA', 'PAEDIATRIC']

interface Call {
  unit: UnitInfo
  trip: TripInfo | null
  patientCase: CaseInfo | null
  hospital: FacilityInfo | null
  vitals: VitalsReading[]
}

// the unit, its trip (204 = none), the trip's case and, while transporting, its hospital; then the latest vitals
async function loadCall(unitId: string): Promise<Call> {
  const id = encodeURIComponent(unitId)
  const unit = await getJson<UnitInfo>(`/api/units/${id}`)
  const trip = (await getJson<TripInfo | undefined>(`/api/units/${id}/trip`)) ?? null
  if (!trip) return { unit, trip: null, patientCase: null, hospital: null, vitals: [] }
  const patientCase = await getJson<CaseInfo>(`/api/cases/${encodeURIComponent(trip.caseId)}`)
  const hospital = trip.hospitalId ? await getJson<FacilityInfo>(`/api/facilities/${encodeURIComponent(trip.hospitalId)}`) : null
  const vitals = await getJson<VitalsReading[]>(`/api/patients/${encodeURIComponent(patientCase.patientId)}/vitals?hours=1`)
  return { unit, trip, patientCase, hospital, vitals }
}

/**
 * The crew's phone: one plain instruction (where to go next, and how long it takes by road), the patient and their
 * latest vitals, and one large button for the next step: accept, arrived, take to hospital (with what the patient
 * needs) or finish, handed over. Every step is safe to repeat, so a tap on a weak network can simply be tried again.
 */
export function CrewPage() {
  const { unitId = '' } = useParams()
  const { data, error, loading } = usePolling(`crew|${unitId}`, () => loadCall(unitId), CREW_POLL_MS)

  return (
    <AppShell app="Crew" narrow>
      {error && <ErrorPanel error={error} notFound={`No unit with the id "${unitId}"`} />}
      {loading && !data && !error && <p className={styles.muted}>Loading</p>}
      {data && <CallScreen call={data} />}
    </AppShell>
  )
}

function CallScreen({ call }: { call: Call }) {
  const { unit, trip, patientCase, hospital } = call
  const toHospital = trip?.status === 'TRANSPORTING'
  const target = toHospital ? hospital : patientCase
  const minutes = useDriveMinutes(trip ? `${trip.id}|${trip.status === 'TRANSPORTING' ? 'h' : 'p'}` : 'none',
    [unit.latitude, unit.longitude], target ? [target.latitude, target.longitude] : null)

  return (
    <div className={css.phone}>
      <div className={css.phoneTop}>
        <span className={css.unitId}>{unit.id}</span>
        <span className={styles.muted}>{UNIT_TYPE[unit.type]}</span>
        <span className={`${styles.pill} ${unit.status === 'ASSIGNED' ? styles.pillBusy : unit.status === 'OFFLINE' ? styles.pillOff : ''}`}>
          {unit.status === 'AVAILABLE' ? 'Free' : unit.status === 'ASSIGNED' ? 'On a call' : 'Offline'}
        </span>
      </div>

      {!trip && (
        <section className={`${styles.panel} ${styles.empty}`}>
          <Ambulance size={24} className={styles.emptyIcon} aria-hidden="true" />
          <strong>No call</strong>
          <span>Your next call appears here the moment dispatch sends you. Keep this screen open.</span>
        </section>
      )}

      {trip && patientCase && (
        <>
          <section className={css.instruction} aria-label="Where to go">
            <span className={css.instructionLabel}>{instructionLabel(trip)}</span>
            <h1>{toHospital ? hospital?.name ?? 'the hospital' : patientCase.patientName ?? patientCase.patientId}</h1>
            {trip.status !== 'ARRIVED' && (
              <span className={css.instructionEta}>
                <span className={css.instructionMinutes}>{minutes ?? '…'}</span> min by road, siren on
              </span>
            )}
            {trip.role === 'SUPPORT' && <span className={css.instructionLabel}>You are the first responder; an ambulance is also coming.</span>}
          </section>

          <section className={`${styles.panel} ${css.patientCard}`} aria-label="Patient">
            <div className={css.patientHead}>
              <h2>{patientCase.patientName ?? patientCase.patientId}</h2>
              {patientCase.age !== null && <span className={styles.muted}>{patientCase.age}{patientCase.sex ? ' ' + patientCase.sex.charAt(0) : ''}</span>}
              <TriageTag band={patientCase.band} />
            </div>
            {trip.needs.length > 0 && <p className={css.patientNeeds}>Needs {trip.needs.map((n) => NEED[n].toLowerCase()).join(' and ')} care</p>}
            <VitalsGrid latest={call.vitals.length > 0 ? call.vitals[call.vitals.length - 1] : null} size="small" />
          </section>

          <NextStep trip={trip} />
        </>
      )}
    </div>
  )
}

function instructionLabel(trip: TripInfo): string {
  switch (trip.status) {
    case 'ASSIGNED': return 'New call. Go to'
    case 'EN_ROUTE': return 'Go to'
    case 'ARRIVED': return 'You are with'
    default: return 'Take the patient to'
  }
}

// the one next step for this trip; a refusal shows the service's words
function NextStep({ trip }: { trip: TripInfo }) {
  const [needs, setNeeds] = useState<Capability[]>([])
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const id = encodeURIComponent(trip.id)

  const run = (path: string, body?: unknown) => {
    setBusy(true)
    setProblem(null)
    postJson(path, body).then(
      () => setBusy(false),
      (e: unknown) => {
        setBusy(false)
        setProblem(e instanceof ApiError ? e.message : String(e))
      },
    )
  }
  const toggle = (n: Capability) => {
    setNeeds(needs.includes(n) ? needs.filter((x) => x !== n) : [...needs, n])
  }

  return (
    <div className={css.nextStep}>
      {problem && <p role="alert" className={css.stepProblem}>{problem}</p>}
      {trip.status === 'ASSIGNED' && (
        <button type="button" className={css.bigButton} disabled={busy} onClick={() => run(`/api/trips/${id}/accept`)}>Accept the call</button>
      )}
      {trip.status === 'EN_ROUTE' && (
        <button type="button" className={css.bigButton} disabled={busy} onClick={() => run(`/api/trips/${id}/arrive`)}>Arrived at the patient</button>
      )}
      {trip.status === 'ARRIVED' && trip.role === 'PRIMARY' && (
        <>
          <fieldset className={css.needs}>
            <legend>What does the patient need at hospital?</legend>
            {NEEDS.map((n) => (
              <label key={n} className={css.needChoice}>
                <input type="checkbox" checked={needs.includes(n)} onChange={() => toggle(n)} /> {NEED[n]}
              </label>
            ))}
          </fieldset>
          <button type="button" className={css.bigButton} disabled={busy}
                  onClick={() => run(`/api/trips/${id}/transport`, { needs })}>Take to hospital</button>
        </>
      )}
      {trip.status === 'ARRIVED' && (
        <button type="button" className={`${css.bigButton} ${css.quietButton}`} disabled={busy}
                onClick={() => run(`/api/trips/${id}/complete`)}>Treated here, finish the call</button>
      )}
      {trip.status === 'TRANSPORTING' && (
        <button type="button" className={css.bigButton} disabled={busy} onClick={() => run(`/api/trips/${id}/complete`)}>Handed over at the hospital</button>
      )}
    </div>
  )
}
