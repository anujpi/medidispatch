import { Ambulance } from 'lucide-react'
import { useParams } from 'react-router'
import { useLiveSnapshot } from '../api/useLiveSnapshot'
import { AppShell } from '../components/AppShell'
import { ConnectionBadge } from '../components/ConnectionBadge'
import { TriageTag } from '../components/TriageTag'
import { TRIP_STATUS, UNIT_TYPE } from '../labels'
import styles from './pages.module.css'

/**
 * A crew's screen, first version: the unit and its current call, led by one plain instruction. Read from the live
 * feed for now; the crew app proper (accept, arrive, GPS, vitals) comes later and will read only its own trip.
 */
export function CrewPage() {
  const { unitId = '' } = useParams()
  const { snapshot, connected } = useLiveSnapshot()
  const unit = snapshot?.units.find((u) => u.id === unitId)
  const trip = snapshot?.activeTrips.find((t) => t.unitId === unitId)
  const patientCase = trip && snapshot?.openCases.find((c) => c.id === trip.caseId)
  const hospital = trip?.hospitalId ? snapshot?.facilities.find((f) => f.id === trip.hospitalId) : undefined

  return (
    <AppShell app="Crew" headerEnd={<ConnectionBadge connected={connected} />}>
      {!snapshot && <p className={styles.muted}>Connecting to the live feed</p>}
      {snapshot && !unit && (
        <section className={styles.panel}>
          <div className={styles.empty}>
            <strong>No unit with the id "{unitId}"</strong>
            <span>Check the id in the address.</span>
          </div>
        </section>
      )}
      {unit && (
        <>
          <div className={styles.pageHeader}>
            <h1>{unit.id}</h1>
            <div className={styles.facts}>
              <span>{UNIT_TYPE[unit.type]}</span>
              <span className={`${styles.pill} ${unit.status === 'ASSIGNED' ? styles.pillBusy
                : unit.status === 'OFFLINE' ? styles.pillOff : ''}`}>
                {unit.status === 'AVAILABLE' ? 'Free' : unit.status === 'ASSIGNED' ? 'On a trip' : 'Offline'}
              </span>
            </div>
          </div>

          <section className={styles.panel} aria-labelledby="call-title">
            <div className={styles.panelHead}>
              <h2 id="call-title">Current call</h2>
            </div>
            {!trip && (
              <div className={styles.empty}>
                <Ambulance size={22} className={styles.emptyIcon} aria-hidden="true" />
                <strong>No call</strong>
                <span>Your next call appears here as soon as dispatch assigns it.</span>
              </div>
            )}
            {trip && (
              <>
                <p className={styles.instruction}>
                  {trip.status === 'TRANSPORTING' ? `Take the patient to ${hospital?.name ?? 'hospital'}`
                    : 'Go to the patient'}
                </p>
                <dl className={styles.details}>
                  {patientCase && (
                    <>
                      <dt>Patient</dt>
                      <dd>
                        <span className={styles.strong}>{patientCase.patientName ?? patientCase.patientId}</span>
                        {patientCase.age !== null && (
                          <span className={styles.detail}>{patientCase.age}{patientCase.sex ? ' ' + patientCase.sex.charAt(0) : ''}</span>
                        )}
                      </dd>
                      <dt>Priority</dt>
                      <dd><TriageTag band={patientCase.band} /></dd>
                    </>
                  )}
                  <dt>Needs</dt>
                  <dd>{trip.needs.length ? trip.needs.map((n) => n.charAt(0) + n.slice(1).toLowerCase()).join(', ') : 'None'}</dd>
                  <dt>Status</dt>
                  <dd>{TRIP_STATUS[trip.status]}</dd>
                  <dt>Role</dt>
                  <dd>{trip.role === 'SUPPORT' ? 'Supporting the main unit' : 'Main unit'}</dd>
                </dl>
              </>
            )}
          </section>
        </>
      )}
    </AppShell>
  )
}
