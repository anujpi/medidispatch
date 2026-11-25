import { Phone, X } from 'lucide-react'
import { Link } from 'react-router'
import { getJson } from '../api/client'
import type { CaseInfo, LiveSnapshot, TripInfo, VitalsReading } from '../api/types'
import { useDriveMinutes } from '../api/useDriveMinutes'
import { usePolling } from '../api/usePolling'
import { TriageTag } from '../components/TriageTag'
import { VitalsGrid } from '../components/VitalsGrid'
import { CASE_STATUS, NEED, TRIP_STATUS, UNIT_TYPE, clockTime, minutesBetween } from '../labels'
import type { LatLon } from '../map/routes'
import styles from '../pages/pages.module.css'
import css from './control.module.css'
import { CaseActions, OutOfService } from './CaseActions'

/**
 * The picked case, as a card over the map: who the patient is, how long they have waited, and every unit on the
 * case with where it is heading, when it should get there, and how to call its driver. The dispatcher's overrides
 * (send another unit, cancel, take a unit out of service) are here too.
 */
export function CaseDetail({ snapshot, openCase, onClose }: {
  snapshot: LiveSnapshot
  openCase: CaseInfo
  onClose: () => void
}) {
  const c = openCase
  const trips = snapshot.activeTrips.filter((t) => t.caseId === c.id)
  const waited = minutesBetween(c.openedAt, snapshot.at)
  return (
    <aside className={css.detail} aria-labelledby="detail-title">
      <div className={css.detailHead}>
        <h2 id="detail-title">{c.patientName ?? c.patientId}</h2>
        <TriageTag band={c.band} waiting={c.status === 'ALERTED'} />
        <button type="button" className={`${css.iconButton} ${css.close}`} aria-label="Close" onClick={onClose}>
          <X size={16} aria-hidden="true" />
        </button>
      </div>
      <dl className={`${styles.details} ${css.detailFacts}`}>
        <dt>Patient</dt>
        <dd>{c.age !== null ? `${c.age}${c.sex ? ', ' + c.sex.toLowerCase() : ''}` : 'Unknown'}</dd>
        <dt>Status</dt>
        <dd>{CASE_STATUS[c.status]}</dd>
        <dt>Opened</dt>
        <dd>{clockTime(c.openedAt)} <span className={styles.muted}>({waited} min ago)</span></dd>
      </dl>
      <h3 className={css.detailSub}>Vitals now</h3>
      <LatestVitals patientId={c.patientId} />
      <p className={css.fileLink}><Link to={`/control/patients/${encodeURIComponent(c.patientId)}`}>Open patient file</Link></p>
      <h3 className={css.detailSub}>Units</h3>
      {trips.length === 0 && (
        <p className={css.detailNote}>No unit yet. The nearest free unit is sent as soon as one is available.</p>
      )}
      <ul className={css.units}>
        {trips.map((t) => <UnitOnCase key={t.id} trip={t} snapshot={snapshot} openCase={c} />)}
      </ul>
      <CaseActions key={c.id} snapshot={snapshot} openCase={c} />
    </aside>
  )
}

function UnitOnCase({ trip, snapshot, openCase }: { trip: TripInfo; snapshot: LiveSnapshot; openCase: CaseInfo }) {
  const unit = snapshot.units.find((u) => u.id === trip.unitId)
  const toHospital = trip.status === 'TRANSPORTING'
  const hospital = snapshot.facilities.find((f) => f.id === trip.hospitalId)
  const target = toHospital ? hospital : openCase
  return (
    <li className={css.unitRow}>
      <div className={css.unitLine}>
        <span className={styles.strong}>{trip.unitId}</span>
        {unit && <span className={styles.muted}>{UNIT_TYPE[unit.type]}</span>}
        {trip.role === 'SUPPORT' && <span className={styles.chip}>Support</span>}
        {unit && target && (
          <Eta tripKey={trip.id + '|' + trip.status} from={[unit.latitude, unit.longitude]}
               to={[target.latitude, target.longitude]} />
        )}
      </div>
      <div className={css.unitMeta}>
        {toHospital && hospital ? `To ${hospital.name}` : TRIP_STATUS[trip.status]}
        {toHospital && trip.needs.length > 0 && <>, needs {trip.needs.map((n) => NEED[n]).join(', ')}</>}
      </div>
      {unit?.driver && (
        <a className={css.driver} href={`tel:${unit.driver.phone}`}>
          <Phone size={13} aria-hidden="true" /> {unit.driver.name}, {unit.driver.phone}
        </a>
      )}
      {!toHospital && <OutOfService unitId={trip.unitId} />}
    </li>
  )
}

// the newest reading of the last hour, asked every 15 s
function LatestVitals({ patientId }: { patientId: string }) {
  const vitals = usePolling(`latest|${patientId}`,
    () => getJson<VitalsReading[]>(`/api/patients/${encodeURIComponent(patientId)}/vitals?hours=1`), 15_000)
  const readings = vitals.data ?? []
  return <VitalsGrid latest={readings.length > 0 ? readings[readings.length - 1] : null} size="small" />
}

// the drive time to where the unit is going
function Eta({ tripKey, from, to }: { tripKey: string; from: LatLon; to: LatLon }) {
  const minutes = useDriveMinutes(tripKey, from, to)
  return (
    <span className={css.eta} title="Estimated drive time">
      {minutes === null ? '…' : <><span className={styles.time}>{minutes}</span><span className={styles.timeUnit}>min</span></>}
    </span>
  )
}
