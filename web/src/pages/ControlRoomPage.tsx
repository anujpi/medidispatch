import { CircleCheck, Radio } from 'lucide-react'
import type { CaseInfo, LiveSnapshot, RiskBand } from '../api/types'
import { useLiveSnapshot } from '../api/useLiveSnapshot'
import { AppShell } from '../components/AppShell'
import { ConnectionBadge } from '../components/ConnectionBadge'
import { TriageTag } from '../components/TriageTag'
import { CASE_STATUS, UNIT_TYPE, minutesBetween } from '../labels'
import styles from './pages.module.css'

const URGENCY: Record<RiskBand, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 }

/** A critical case waiting longer than this without a unit is shown in red. */
const LATE_MINUTES = 2

// most urgent first, then the longest waiting
function byUrgency(a: CaseInfo, b: CaseInfo): number {
  return URGENCY[a.band] - URGENCY[b.band] || a.openedAt.localeCompare(b.openedAt)
}

/**
 * The control room: the dispatcher's overview. A strip of the numbers that matter, the open cases most urgent first
 * (how long each has waited, which unit is on it), and the fleet. Neutral by design: colour appears only where
 * something needs attention (urgency, a critical case left waiting). The map and the actions come in A2.
 */
export function ControlRoomPage() {
  const { snapshot, connected } = useLiveSnapshot()

  return (
    <AppShell app="Control room" wide headerEnd={<ConnectionBadge connected={connected} />}>
      <div className={styles.pageHeader}>
        <h1>Control room</h1>
        <p className={styles.subtitle}>Every open case and unit in Bengaluru, updated every second.</p>
      </div>
      {!snapshot && (
        <section className={styles.panel}>
          <div className={styles.empty}>
            <Radio size={22} className={styles.emptyIcon} aria-hidden="true" />
            <strong>Connecting to the live feed</strong>
            <span>The picture appears within a second of connecting.</span>
          </div>
        </section>
      )}
      {snapshot && (
        <>
          <Metrics snapshot={snapshot} />
          <div className={styles.grid}>
            <Queue snapshot={snapshot} />
            <Fleet snapshot={snapshot} />
          </div>
        </>
      )}
    </AppShell>
  )
}

function Metrics({ snapshot }: { snapshot: LiveSnapshot }) {
  const count = <T,>(list: T[], test: (item: T) => boolean) => list.filter(test).length
  const critical = count(snapshot.openCases, (c) => c.band === 'CRITICAL')
  const awaiting = count(snapshot.openCases, (c) => c.status === 'ALERTED')
  return (
    <div className={styles.metrics} aria-label="City summary">
      <Metric label="Open cases" value={snapshot.openCases.length} />
      <Metric label="Critical" value={critical} alarm={critical > 0} />
      <Metric label="Awaiting a unit" value={awaiting} alarm={awaiting > 0} />
      <Metric label="Units free" value={count(snapshot.units, (u) => u.status === 'AVAILABLE')}
              of={snapshot.units.length} />
      <Metric label="To hospital" value={count(snapshot.activeTrips, (t) => t.status === 'TRANSPORTING')} />
    </div>
  )
}

function Metric({ label, value, of, alarm }: { label: string; value: number; of?: number; alarm?: boolean }) {
  return (
    <div className={`${styles.metric} ${alarm ? styles.metricAlarm : ''}`}>
      <span className={styles.metricLabel}>{label}</span>
      <span className={styles.metricValue}>
        {value}
        {of !== undefined && <span className={styles.metricOf}>of {of}</span>}
      </span>
    </div>
  )
}

function Queue({ snapshot }: { snapshot: LiveSnapshot }) {
  const cases = [...snapshot.openCases].sort(byUrgency)
  return (
    <section className={styles.panel} aria-labelledby="queue-title">
      <div className={styles.panelHead}>
        <h2 id="queue-title">Open cases</h2>
        <span className={styles.count}>{cases.length}</span>
        <span className={styles.panelMeta}>Most urgent first</span>
      </div>
      {cases.length === 0 && (
        <div className={styles.empty}>
          <CircleCheck size={22} className={styles.emptyIcon} aria-hidden="true" />
          <strong>No open cases</strong>
          <span>A case opens here the moment a patient's readings cross a warning level.</span>
        </div>
      )}
      {cases.length > 0 && (
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Priority</th>
              <th>Patient</th>
              <th className={styles.hideSmall}>Status</th>
              <th>Unit</th>
              <th className={styles.right}>Waiting</th>
            </tr>
          </thead>
          <tbody>
            {cases.map((c) => {
              const units = snapshot.activeTrips.filter((t) => t.caseId === c.id).map((t) => t.unitId)
              const waited = minutesBetween(c.openedAt, snapshot.at)
              const late = c.band === 'CRITICAL' && c.status === 'ALERTED' && waited >= LATE_MINUTES
              return (
                <tr key={c.id} className={c.band === 'CRITICAL' ? styles.critical : ''}>
                  <td><TriageTag band={c.band} waiting={c.status === 'ALERTED'} /></td>
                  <td>
                    <span className={styles.strong}>{c.patientName ?? c.patientId}</span>
                    {c.age !== null && <span className={styles.detail}>{c.age}{c.sex ? ' ' + c.sex.charAt(0) : ''}</span>}
                  </td>
                  <td className={styles.hideSmall}>{CASE_STATUS[c.status]}</td>
                  <td>
                    {units.length === 0 && <span className={styles.muted}>Awaiting unit</span>}
                    {units.length > 0 && <span className={styles.strong}>{units[0]}</span>}
                    {units.length > 1 && <span className={styles.detail}>+ {units.slice(1).join(', ')}</span>}
                  </td>
                  <td className={styles.right} title="Waiting since the case opened">
                    <span className={`${styles.time} ${late ? styles.late : ''}`}>
                      {waited}<span className={styles.timeUnit}>min</span>
                    </span>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
    </section>
  )
}

function Fleet({ snapshot }: { snapshot: LiveSnapshot }) {
  const order = { ASSIGNED: 0, AVAILABLE: 1, OFFLINE: 2 }
  const units = [...snapshot.units].sort((a, b) => order[a.status] - order[b.status] || a.id.localeCompare(b.id))
  return (
    <section className={styles.panel} aria-labelledby="fleet-title">
      <div className={styles.panelHead}>
        <h2 id="fleet-title">Fleet</h2>
        <span className={styles.count}>{units.length}</span>
      </div>
      <table className={`${styles.table} ${styles.compact}`}>
        <thead>
          <tr>
            <th>Unit</th>
            <th>Type</th>
            <th className={styles.right}>Status</th>
          </tr>
        </thead>
        <tbody>
          {units.map((u) => (
            <tr key={u.id}>
              <td className={styles.strong}>{u.id}</td>
              <td>{UNIT_TYPE[u.type]}</td>
              <td className={styles.right}>
                <span className={styles.dotCell}>
                  <span className={`${styles.dot} ${u.status === 'ASSIGNED' ? styles.dotBusy
                    : u.status === 'OFFLINE' ? styles.dotOff : ''}`} aria-hidden="true" />
                  {u.status === 'ASSIGNED' ? 'On a trip' : u.status === 'AVAILABLE' ? 'Free' : 'Offline'}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
