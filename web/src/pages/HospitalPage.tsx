import { useState } from 'react'
import { CircleCheck, Phone } from 'lucide-react'
import { useParams } from 'react-router'
import { ApiError, getJson, putJson } from '../api/client'
import type { FacilityInfo, InboundPatient } from '../api/types'
import { usePolling } from '../api/usePolling'
import { AppShell } from '../components/AppShell'
import { ErrorPanel } from '../components/ErrorPanel'
import { TriageTag } from '../components/TriageTag'
import { VitalsGrid } from '../components/VitalsGrid'
import { NEED, UNIT_TYPE } from '../labels'
import styles from './pages.module.css'
import css from './screens.module.css'

/** How often the pre-alert is asked for again. */
const HOSPITAL_POLL_MS = 5_000

/**
 * A hospital's arrivals board: who it is, a switch for diversion, and every patient on the way in, soonest first,
 * each with a large countdown, what they need, the unit bringing them and their latest vitals. Asked for every
 * 5 seconds, not from the whole-city live feed: after the login a hospital may only see its own patients.
 */
export function HospitalPage() {
  const { facilityId = '' } = useParams()
  const id = encodeURIComponent(facilityId)
  const { data, error, loading } = usePolling(
    facilityId,
    () => Promise.all([
      getJson<FacilityInfo>(`/api/facilities/${id}`),
      getJson<InboundPatient[]>(`/api/facilities/${id}/inbound`),
    ]).then(([facility, arrivals]) => ({ facility, arrivals, fetchedAt: Date.now() })),
    HOSPITAL_POLL_MS,
  )
  const hospital = data?.facility ?? null
  const inbound = data?.arrivals ?? []

  return (
    <AppShell app="Hospital" wide>
      {error && <ErrorPanel error={error} notFound={`No hospital with the id "${facilityId}"`} />}
      {loading && !data && !error && <p className={styles.muted}>Loading</p>}
      {hospital && (
        <>
          <div className={css.titleRow}>
            <div className={styles.pageHeader}>
              <h1>{hospital.name}</h1>
              <div className={styles.facts}>
                {hospital.phone && (
                  <a className={styles.fact} href={`tel:${hospital.phone.replace(/\s/g, '')}`}>
                    <Phone size={14} className={styles.factIcon} aria-hidden="true" />
                    {hospital.phone}
                  </a>
                )}
                {hospital.capabilities.length > 0 && (
                  <span className={styles.chips} aria-label="Specialties">
                    {hospital.capabilities.map((c) => <span key={c} className={styles.chip}>{NEED[c]}</span>)}
                  </span>
                )}
              </div>
            </div>
            <DiversionSwitch hospital={hospital} fetched={data} />
          </div>

          <section aria-labelledby="arrivals-title" className={css.arrivals}>
            <div className={css.sectionHead}>
              <h2 id="arrivals-title">On the way to you</h2>
              <span className={styles.count}>{inbound.length}</span>
              <span className={styles.panelMeta}>Soonest first. Vitals as last sent by the patient's sensor.</span>
            </div>
            {inbound.length === 0 && (
              <div className={`${styles.panel} ${styles.empty}`}>
                <CircleCheck size={22} className={styles.emptyIcon} aria-hidden="true" />
                <strong>No patients on the way</strong>
                <span>When an ambulance sets off towards you, the patient appears here with an arrival time.</span>
              </div>
            )}
            {inbound.map((p) => <Arrival key={p.tripId} patient={p} fetchedAt={data?.fetchedAt ?? 0} />)}
          </section>
        </>
      )}
    </AppShell>
  )
}

// one patient on the way: a large countdown, then who, why, and their vitals; the clock time counts from the fetch
function Arrival({ patient: p, fetchedAt }: { patient: InboundPatient; fetchedAt: number }) {
  const arrivesAt = new Date(fetchedAt + p.etaMinutes * 60_000)
  return (
    <article className={`${css.arrival} ${p.band === 'CRITICAL' ? css.arrivalCritical : ''}`}
             aria-label={`${p.patientName ?? p.patientId}, arriving in ${p.etaMinutes} minutes`}>
      <div className={css.eta}>
        <span className={css.etaLabel}>Arrives in</span>
        <span className={css.etaValue}>{p.etaMinutes}</span>
        <span className={css.etaLabel}>
          min, at {arrivesAt.toLocaleTimeString('en-GB', { timeZone: 'Asia/Kolkata', hour: '2-digit', minute: '2-digit' })}
        </span>
      </div>
      <div className={css.arrivalBody}>
        <div className={css.arrivalWho}>
          <TriageTag band={p.band} />
          <h3>{p.patientName ?? p.patientId}</h3>
          {p.age !== null && <span className={styles.muted}>{p.age}{p.sex ? ' ' + p.sex.charAt(0) : ''}</span>}
          <span className={css.arrivalUnit}>{p.unitId}, {UNIT_TYPE[p.unitType]}, {p.distanceKm.toFixed(1)} km away</span>
        </div>
        <p className={css.arrivalNeeds}>
          {p.needs.length > 0 ? `Needs ${p.needs.map((n) => NEED[n].toLowerCase()).join(' and ')} care` : 'No special needs given'}
        </p>
        <VitalsGrid latest={p.latestVitals} size="small" />
      </div>
    </article>
  )
}

// accepting or on diversion; the switch shows the new value at once, then the service's own answer takes over
function DiversionSwitch({ hospital, fetched }: { hospital: FacilityInfo; fetched: unknown }) {
  const [changed, setChanged] = useState<{ value: boolean; whileShowing: unknown } | null>(null)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const accepting = changed && changed.whileShowing === fetched ? changed.value : hospital.accepting

  const flip = () => {
    const next = !accepting
    setBusy(true)
    setProblem(null)
    putJson(`/api/facilities/${encodeURIComponent(hospital.id)}/accepting`, { accepting: next }).then(
      () => {
        setChanged({ value: next, whileShowing: fetched })
        setBusy(false)
      },
      (e: unknown) => {
        setProblem(e instanceof ApiError ? e.message : String(e))
        setBusy(false)
      },
    )
  }

  return (
    <div className={`${css.switchBox} ${accepting ? '' : css.switchBoxOff}`}>
      <span className={css.switchText}>
        <strong>{accepting ? 'Accepting patients' : 'On diversion'}</strong>
        <span>{accepting ? 'Ambulances are sent here as usual' : 'New ambulances go elsewhere; those already on the way still come'}</span>
        {problem && <span role="alert" className={css.switchProblem}>{problem}</span>}
      </span>
      <button type="button" role="switch" aria-checked={accepting} aria-label="Accepting new patients"
              className={css.switch} disabled={busy} onClick={flip}>
        <span className={css.knob} />
      </button>
    </div>
  )
}
