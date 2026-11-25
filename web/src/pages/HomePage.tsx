import { useState, type ReactNode } from 'react'
import { Ambulance, ArrowRight, Hospital, MonitorDot, UserRound } from 'lucide-react'
import { Link, useNavigate } from 'react-router'
import { getJson } from '../api/client'
import type { FacilityInfo } from '../api/types'
import { usePolling } from '../api/usePolling'
import { AppShell } from '../components/AppShell'
import { ErrorPanel } from '../components/ErrorPanel'
import styles from './pages.module.css'

/**
 * The start page: one row per screen, what it is for, and how to open it. Until the login (SEC) decides, the hospital,
 * unit or patient is chosen here: hospitals from the service's list, units and patients by their id.
 */
export function HomePage() {
  const facilities = usePolling('facilities', () => getJson<FacilityInfo[]>('/api/facilities'), 30_000)
  const hospitals = (facilities.data ?? []).filter((f) => f.kind === 'HOSPITAL')

  return (
    <AppShell>
      <div className={styles.narrow}>
        <div className={styles.pageHeader}>
          <h1>Open a screen</h1>
          <p className={styles.subtitle}>Emergency medical dispatch for Bengaluru.</p>
        </div>

        <section className={styles.panel}>
          <ul className={styles.launch}>
            <Launch icon={<MonitorDot size={18} />} title="Control room"
                    text="Every open case, unit and trip in the city, live.">
              <Link to="/control" className={styles.button}>
                Open the control room <ArrowRight size={14} aria-hidden="true" />
              </Link>
            </Launch>

            <Launch icon={<Hospital size={18} />} title="Hospital"
                    text="Patients on their way to your emergency department.">
              {facilities.error && <ErrorPanel error={facilities.error} />}
              {!facilities.error && hospitals.length === 0 && !facilities.loading && (
                <p className={styles.muted}>No hospitals are registered yet.</p>
              )}
              <ul className={styles.links}>
                {hospitals.map((h) => (
                  <li key={h.id}>
                    <Link to={`/hospital/${encodeURIComponent(h.id)}`}>{h.name}</Link>
                  </li>
                ))}
              </ul>
            </Launch>

            <Launch icon={<Ambulance size={18} />} title="Crew" text="A unit's current call: who, where, and what they need.">
              <IdForm label="Unit id" placeholder="als-1" path="/crew/" />
            </Launch>

            <Launch icon={<UserRound size={18} />} title="Patient"
                    text="A patient's own view: whether help is needed and on its way.">
              <IdForm label="Patient id" placeholder="p001" path="/patient/" />
            </Launch>
          </ul>
        </section>
      </div>
    </AppShell>
  )
}

function Launch({ icon, title, text, children }: { icon: ReactNode; title: string; text: string; children: ReactNode }) {
  return (
    <li className={styles.launchItem}>
      <span className={styles.launchIcon} aria-hidden="true">{icon}</span>
      <div className={styles.launchTitle}>
        <h2>{title}</h2>
        <p>{text}</p>
      </div>
      <div className={styles.launchAction}>{children}</div>
    </li>
  )
}

// a one-field form that opens path + the typed id (the placeholder's id when left empty)
function IdForm({ label, placeholder, path }: { label: string; placeholder: string; path: string }) {
  const [id, setId] = useState('')
  const navigate = useNavigate()
  return (
    <form
      className={styles.form}
      onSubmit={(e) => {
        e.preventDefault()
        navigate(path + encodeURIComponent(id.trim() || placeholder))
      }}
    >
      <input className={styles.input} aria-label={label} placeholder={placeholder} value={id}
             onChange={(e) => setId(e.target.value)} />
      <button type="submit" className={`${styles.button} ${styles.buttonQuiet}`}>Open</button>
    </form>
  )
}
