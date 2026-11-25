import { HeartPulse, Siren } from 'lucide-react'
import { useParams } from 'react-router'
import { ApiError, getJson } from '../api/client'
import type { CaseInfo, PatientInfo } from '../api/types'
import { usePolling } from '../api/usePolling'
import { AppShell } from '../components/AppShell'
import { ErrorPanel } from '../components/ErrorPanel'
import { TriageTag } from '../components/TriageTag'
import { CASE_STATUS } from '../labels'
import styles from './pages.module.css'

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
 * The patient's own screen, first version: one plain statement of whether help is needed and coming. The SOS button
 * and the ambulance's ETA come with the patient app.
 */
export function PatientPage() {
  const { patientId = '' } = useParams()
  const id = encodeURIComponent(patientId)
  const { data, error, loading } = usePolling(
    patientId,
    () => Promise.all([getJson<PatientInfo>(`/api/patients/${id}`), openCaseOrNone(`/api/patients/${id}/case`)]),
    5_000,
  )
  const [patient, openCase] = data ?? [null, null]

  return (
    <AppShell app="Patient">
      {error && <ErrorPanel error={error} notFound={`No patient with the id "${patientId}"`} />}
      {loading && !data && !error && <p className={styles.muted}>Loading</p>}
      {patient && (
        <>
          <div className={styles.pageHeader}>
            <h1>{patient.name ?? patient.id}</h1>
            <p className={styles.subtitle}>Your status, updated every 5 seconds.</p>
          </div>
          <section className={styles.panel}>
            {!openCase && (
              <div className={styles.empty}>
                <HeartPulse size={22} className={styles.emptyIcon} aria-hidden="true" />
                <strong>No alerts</strong>
                <span>Your readings look normal. If they change, help is sent to you automatically.</span>
              </div>
            )}
            {openCase && (
              <>
                <p className={styles.instruction}>
                  <Siren size={20} className={styles.factIcon} aria-hidden="true" />{' '}
                  {openCase.status === 'ALERTED' ? 'Finding a unit for you' : 'Help is on the way'}
                </p>
                <dl className={styles.details}>
                  <dt>Priority</dt>
                  <dd><TriageTag band={openCase.band} waiting={openCase.status === 'ALERTED'} /></dd>
                  <dt>Status</dt>
                  <dd>{CASE_STATUS[openCase.status]}</dd>
                  <dt>Since</dt>
                  <dd>{new Date(openCase.openedAt).toLocaleTimeString('en-GB', { timeZone: 'Asia/Kolkata' })}</dd>
                </dl>
              </>
            )}
          </section>
        </>
      )}
    </AppShell>
  )
}
