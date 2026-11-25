import { ClipboardList, Phone } from 'lucide-react'
import { Link, useParams } from 'react-router'
import { getJson } from '../api/client'
import type { LiveSnapshot, PatientInfo, VitalsReading } from '../api/types'
import { useLiveSnapshot } from '../api/useLiveSnapshot'
import { usePolling } from '../api/usePolling'
import { AppShell } from '../components/AppShell'
import { ConnectionBadge } from '../components/ConnectionBadge'
import { ErrorPanel } from '../components/ErrorPanel'
import { TriageTag } from '../components/TriageTag'
import { VitalsGrid } from '../components/VitalsGrid'
import { CASE_STATUS, TRIP_STATUS, UNIT_TYPE, clockTime, minutesBetween } from '../labels'
import styles from './pages.module.css'
import css from './screens.module.css'

/** How far back the trend lines go. */
const TREND_HOURS = 6
/** How often the vitals are asked for again; a wearable sends a reading every 30 s or so. */
const VITALS_POLL_MS = 15_000

const SEX: Record<string, string> = { FEMALE: 'female', MALE: 'male', OTHER: 'other' }

/**
 * The dispatcher's patient file: who the patient is and how to reach their family, the open case and every unit on
 * it, the vital signs now with their trend over the last hours, and what has happened in the case. The medical
 * record (allergies, conditions, medicines, reports) is not stored yet; its panel says so instead of guessing.
 */
export function PatientFilePage() {
  const { patientId = '' } = useParams()
  const id = encodeURIComponent(patientId)
  const { snapshot, connected } = useLiveSnapshot()
  const record = usePolling(`patient|${patientId}`, () => getJson<PatientInfo>(`/api/patients/${id}`), 60_000)
  const vitals = usePolling(`vitals|${patientId}`,
    () => getJson<VitalsReading[]>(`/api/patients/${id}/vitals?hours=${TREND_HOURS}`), VITALS_POLL_MS)

  const patient = record.data
  const history = vitals.data ?? []
  const latest = history.length > 0 ? history[history.length - 1] : null

  return (
    <AppShell app="Patient file" wide headerEnd={<ConnectionBadge connected={connected} />}>
      {record.error && <ErrorPanel error={record.error} notFound={`No patient with the id "${patientId}"`} />}
      {record.loading && !patient && !record.error && <p className={styles.muted}>Loading</p>}
      {patient && (
        <>
          <section className={css.banner} aria-label="Patient">
            <span className={css.avatar} aria-hidden="true">{initials(patient.name ?? patient.id)}</span>
            <div className={css.who}>
              <h1>{patient.name ?? patient.id}</h1>
              <span>
                {patient.age !== null ? `${patient.age}${patient.sex ? ', ' + SEX[patient.sex] : ''}. ` : ''}
                Patient id {patient.id}
              </span>
            </div>
            {patient.emergencyContact && (
              <div className={css.contact}>
                <span>Emergency contact</span>
                <a href={`tel:${patient.emergencyContact.phone.replace(/\s/g, '')}`}>
                  <Phone size={14} aria-hidden="true" /> {patient.emergencyContact.name}, {patient.emergencyContact.phone}
                </a>
              </div>
            )}
          </section>

          {snapshot && <CurrentCase snapshot={snapshot} patientId={patient.id} />}

          <div className={css.columns}>
            <div className={css.mainColumn}>
              <section className={styles.panel} aria-labelledby="vitals-title">
                <div className={styles.panelHead}>
                  <h2 id="vitals-title">Vitals now</h2>
                  <span className={styles.panelMeta}>
                    {latest ? `Last reading ${clockTime(latest.timeStamp)}. Lines show the last ${TREND_HOURS} hours.`
                      : `No readings in the last ${TREND_HOURS} hours`}
                  </span>
                </div>
                {vitals.error && <div className={styles.panelBody}><ErrorPanel error={vitals.error} /></div>}
                <VitalsGrid latest={latest} history={history} />
              </section>

              {snapshot && <Timeline snapshot={snapshot} patientId={patient.id} />}
            </div>

            <aside className={css.sideColumn}>
              <section className={styles.panel} aria-labelledby="record-title">
                <div className={styles.panelHead}>
                  <h2 id="record-title">Medical record</h2>
                </div>
                <div className={styles.empty}>
                  <ClipboardList size={22} className={styles.emptyIcon} aria-hidden="true" />
                  <strong>Not recorded yet</strong>
                  <span>Allergies, conditions, medicines and reports will appear here once they are recorded.</span>
                </div>
              </section>
            </aside>
          </div>
        </>
      )}
    </AppShell>
  )
}

// the open case, in one line: urgency, since when, and where each unit is
function CurrentCase({ snapshot, patientId }: { snapshot: LiveSnapshot; patientId: string }) {
  const c = snapshot.openCases.find((one) => one.patientId === patientId)
  if (!c) {
    return <p className={css.noCase}>No open case. The patient's readings are being watched.</p>
  }
  const trips = snapshot.activeTrips.filter((t) => t.caseId === c.id)
  return (
    <section className={`${css.caseBar} ${c.band === 'CRITICAL' ? css.caseCritical : ''}`} aria-label="Open case">
      <TriageTag band={c.band} waiting={c.status === 'ALERTED'} />
      <strong>Open since {clockTime(c.openedAt)}</strong>
      <span>{CASE_STATUS[c.status]}</span>
      {trips.map((t) => {
        const hospital = snapshot.facilities.find((f) => f.id === t.hospitalId)
        return (
          <span key={t.id}>
            <span className={styles.strong}>{t.unitId}</span>{' '}
            {t.status === 'TRANSPORTING' && hospital ? `taking the patient to ${hospital.name}` : TRIP_STATUS[t.status].toLowerCase()}
          </span>
        )
      })}
    </section>
  )
}

// what has happened in the open case, from what the live picture knows: when it opened and when each unit was sent
function Timeline({ snapshot, patientId }: { snapshot: LiveSnapshot; patientId: string }) {
  const c = snapshot.openCases.find((one) => one.patientId === patientId)
  if (!c) return null
  const events: { at: string; text: string; sub: string }[] = [
    { at: c.openedAt, text: 'Case opened', sub: `Readings crossed the warning level. Priority: ${c.band.toLowerCase()}.` },
  ]
  for (const t of snapshot.activeTrips.filter((one) => one.caseId === c.id)) {
    const unit = snapshot.units.find((u) => u.id === t.unitId)
    events.push({
      at: t.assignedAt,
      text: `${t.unitId} sent${t.role === 'SUPPORT' ? ' as first responder' : ''}`,
      sub: `${unit ? UNIT_TYPE[unit.type] : 'Unit'}, now ${TRIP_STATUS[t.status].toLowerCase()}`,
    })
  }
  events.sort((a, b) => a.at.localeCompare(b.at))
  return (
    <section className={styles.panel} aria-labelledby="timeline-title">
      <div className={styles.panelHead}>
        <h2 id="timeline-title">What has happened in this case</h2>
        <span className={styles.panelMeta}>{minutesBetween(c.openedAt, snapshot.at)} min so far</span>
      </div>
      <ol className={css.timeline}>
        {events.map((e, i) => (
          <li key={i}>
            <span className={css.timelineAt}>{clockTime(e.at)}</span>
            <span className={css.timelineDot} aria-hidden="true" />
            <span>{e.text}<span className={css.timelineSub}>{e.sub}</span></span>
          </li>
        ))}
      </ol>
      <p className={css.panelFoot}>
        <Link to="/control">Back to the control room</Link>
      </p>
    </section>
  )
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/)
  return (parts[0].charAt(0) + (parts.length > 1 ? parts[parts.length - 1].charAt(0) : '')).toUpperCase()
}
