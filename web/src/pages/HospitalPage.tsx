import { CircleCheck, Phone } from 'lucide-react'
import { useParams } from 'react-router'
import { getJson } from '../api/client'
import type { FacilityInfo, InboundPatient } from '../api/types'
import { usePolling } from '../api/usePolling'
import { AppShell } from '../components/AppShell'
import { ErrorPanel } from '../components/ErrorPanel'
import { TriageTag } from '../components/TriageTag'
import { UNIT_TYPE } from '../labels'
import styles from './pages.module.css'

/** How often the pre-alert is asked for again. */
const HOSPITAL_POLL_MS = 5_000

const NEED: Record<string, string> = {
  CARDIAC: 'Cardiac', STROKE: 'Stroke', TRAUMA: 'Trauma', PAEDIATRIC: 'Paediatric',
}

/**
 * A hospital's panel: who it is, whether it accepts patients, and the arrivals (patients on the way in, soonest
 * first, with what they need). Asked for every 5 seconds, not from the whole-city live feed: after the login a
 * hospital may only see its own patients.
 */
export function HospitalPage() {
  const { facilityId = '' } = useParams()
  const id = encodeURIComponent(facilityId)
  const { data, error, loading } = usePolling(
    facilityId,
    () => Promise.all([
      getJson<FacilityInfo>(`/api/facilities/${id}`),
      getJson<InboundPatient[]>(`/api/facilities/${id}/inbound`),
    ]),
    HOSPITAL_POLL_MS,
  )
  const [hospital, inbound] = data ?? [null, []]

  return (
    <AppShell app="Hospital">
      {error && <ErrorPanel error={error} notFound={`No hospital with the id "${facilityId}"`} />}
      {loading && !data && !error && <p className={styles.muted}>Loading</p>}
      {hospital && (
        <>
          <div className={styles.pageHeader}>
            <h1>{hospital.name}</h1>
            <div className={styles.facts}>
              <span className={`${styles.pill} ${hospital.accepting ? '' : styles.pillWarn}`}>
                {hospital.accepting ? 'Accepting patients' : 'On diversion'}
              </span>
              {hospital.phone && (
                <a className={styles.fact} href={`tel:${hospital.phone.replace(/\s/g, '')}`}>
                  <Phone size={14} className={styles.factIcon} aria-hidden="true" />
                  {hospital.phone}
                </a>
              )}
              {hospital.capabilities.length > 0 && (
                <span className={styles.chips} aria-label="Specialties">
                  {hospital.capabilities.map((c) => <span key={c} className={styles.chip}>{NEED[c] ?? c}</span>)}
                </span>
              )}
            </div>
          </div>

          <section className={styles.panel} aria-labelledby="arrivals-title">
            <div className={styles.panelHead}>
              <h2 id="arrivals-title">Arrivals</h2>
              <span className={styles.count}>{inbound.length}</span>
              <span className={styles.panelMeta}>Soonest first, updated every 5 seconds</span>
            </div>
            {inbound.length === 0 && (
              <div className={styles.empty}>
                <CircleCheck size={22} className={styles.emptyIcon} aria-hidden="true" />
                <strong>No patients on the way</strong>
                <span>A pre-alert appears here as soon as a crew starts bringing a patient to you.</span>
              </div>
            )}
            {inbound.length > 0 && (
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>Arrives in</th>
                    <th>Priority</th>
                    <th>Patient</th>
                    <th className={styles.hideSmall}>Needs</th>
                    <th className={styles.hideSmall}>Unit</th>
                  </tr>
                </thead>
                <tbody>
                  {inbound.map((p) => (
                    <tr key={p.tripId} className={p.band === 'CRITICAL' ? styles.critical : ''}>
                      <td title="Estimated arrival">
                        <span className={styles.time}>{p.etaMinutes}<span className={styles.timeUnit}>min</span></span>
                      </td>
                      <td><TriageTag band={p.band} /></td>
                      <td>
                        <span className={styles.strong}>{p.patientName ?? p.patientId}</span>
                        {p.age !== null && <span className={styles.detail}>{p.age}{p.sex ? ' ' + p.sex.charAt(0) : ''}</span>}
                      </td>
                      <td className={styles.hideSmall}>
                        {p.needs.length ? p.needs.map((n) => NEED[n] ?? n).join(', ') : <span className={styles.muted}>None</span>}
                      </td>
                      <td className={styles.hideSmall}>
                        <span className={styles.strong}>{p.unitId}</span>
                        <span className={styles.detail}>{UNIT_TYPE[p.unitType]}</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </>
      )}
    </AppShell>
  )
}
