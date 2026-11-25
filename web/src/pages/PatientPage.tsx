import { HeartPulse } from 'lucide-react'
import { useParams } from 'react-router'
import { ApiError, getJson } from '../api/client'
import type { CaseInfo, LiveSnapshot, PatientInfo, VitalsReading } from '../api/types'
import { useDriveMinutes } from '../api/useDriveMinutes'
import { useLiveSnapshot } from '../api/useLiveSnapshot'
import { usePolling } from '../api/usePolling'
import { AppShell } from '../components/AppShell'
import { ErrorPanel } from '../components/ErrorPanel'
import { VitalsGrid } from '../components/VitalsGrid'
import styles from './pages.module.css'
import css from './screens.module.css'

// GET /api/patients/{id}/case answers 404 when the patient has no open case: that is "all fine", not an error
async function openCaseOrNone(path: string): Promise<CaseInfo | null> {
  try {
    return await getJson<CaseInfo>(path)
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null
    throw e
  }
}

/**
 * The patient's own phone: whether help is needed, and when it is coming. With no open case, their latest vitals
 * and a reassuring line; with one, what is happening now (finding a unit, an ambulance coming in so many minutes,
 * the crew with them, on the way to hospital) and what to do while waiting.
 */
// ponytail: the unit and its position come from the whole-city live feed until the login (SEC) gives patients their
// own view of their trip.
export function PatientPage() {
  const { patientId = '' } = useParams()
  const id = encodeURIComponent(patientId)
  const { data, error, loading } = usePolling(
    patientId,
    () => Promise.all([
      getJson<PatientInfo>(`/api/patients/${id}`),
      openCaseOrNone(`/api/patients/${id}/case`),
      getJson<VitalsReading[]>(`/api/patients/${id}/vitals?hours=1`),
    ]),
    5_000,
  )
  const { snapshot } = useLiveSnapshot()
  const [patient, openCase, vitals] = data ?? [null, null, []]

  return (
    <AppShell app="Patient" narrow>
      {error && <ErrorPanel error={error} notFound={`No patient with the id "${patientId}"`} />}
      {loading && !data && !error && <p className={styles.muted}>Loading</p>}
      {patient && (
        <div className={css.phone}>
          <p className={css.hello}>Hello, {(patient.name ?? patient.id).split(' ')[0]}</p>
          {!openCase && (
            <section className={`${styles.panel} ${css.allWell}`}>
              <HeartPulse size={24} className={styles.emptyIcon} aria-hidden="true" />
              <h1>Your readings look normal</h1>
              <p>Your sensor is watched around the clock. If something changes, help is sent to you automatically.</p>
            </section>
          )}
          {openCase && <HelpStatus patient={patient} openCase={openCase} snapshot={snapshot} />}
          <section className={styles.panel} aria-labelledby="my-vitals">
            <div className={styles.panelHead}><h2 id="my-vitals">Your vitals now</h2></div>
            <VitalsGrid latest={vitals.length > 0 ? vitals[vitals.length - 1] : null} size="small" />
          </section>
        </div>
      )}
    </AppShell>
  )
}

function HelpStatus({ patient, openCase, snapshot }: { patient: PatientInfo; openCase: CaseInfo; snapshot: LiveSnapshot | null }) {
  const trip = snapshot?.activeTrips.find((t) => t.caseId === openCase.id && t.role === 'PRIMARY')
  const unit = trip ? snapshot?.units.find((u) => u.id === trip.unitId) : undefined
  const hospital = trip?.hospitalId ? snapshot?.facilities.find((f) => f.id === trip.hospitalId) : undefined
  const toHospital = trip?.status === 'TRANSPORTING'
  const target: [number, number] | null = toHospital && hospital ? [hospital.latitude, hospital.longitude]
    : [patient.latitude, patient.longitude]
  const minutes = useDriveMinutes(trip ? `${trip.id}|${toHospital ? 'h' : 'p'}` : 'none',
    unit ? [unit.latitude, unit.longitude] : null, trip && trip.status !== 'ARRIVED' ? target : null)

  let title = 'We are finding an ambulance for you'
  if (trip && toHospital) title = `On the way to ${hospital?.name ?? 'hospital'}`
  else if (trip && trip.status === 'ARRIVED') title = 'The crew is with you'
  else if (trip) title = 'An ambulance is coming to you'

  return (
    <section className={`${styles.panel} ${css.help}`} aria-labelledby="help-title">
      <h1 id="help-title">{title}</h1>
      {trip && trip.status !== 'ARRIVED' && (
        <p className={css.helpEta}><span>{minutes ?? '…'}</span> min</p>
      )}
      {unit?.driver && <p className={css.helpCrew}>{unit.id}, driven by {unit.driver.name}</p>}
      {(!trip || trip.status === 'ASSIGNED' || trip.status === 'EN_ROUTE') && (
        <div className={css.waitTips}>
          <h2>While you wait</h2>
          <ul>
            <li>Unlock the front door if you can.</li>
            <li>Sit or lie down somewhere comfortable.</li>
            <li>Keep your medicines and their list close.</li>
          </ul>
        </div>
      )}
    </section>
  )
}
