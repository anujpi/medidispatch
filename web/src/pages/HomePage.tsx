import { useEffect, useState, type ReactNode } from 'react'
import { ArrowRight } from 'lucide-react'
import { Link, useNavigate } from 'react-router'
import { getJson } from '../api/client'
import type { FacilityInfo } from '../api/types'
import { usePolling } from '../api/usePolling'
import { AppShell } from '../components/AppShell'
import { CityScene, CrewScene, HomeScene, HospitalScene } from '../components/Doodles'
import { ErrorPanel } from '../components/ErrorPanel'
import { JourneyStrip } from '../components/JourneyStrip'
import type { JourneyStep } from '../components/journey'
import styles from './home.module.css'

/** How long the story on the start page stays on each step before moving on. */
export const STORY_STEP_MS = 2600

/**
 * The start page, "Who are you today?". It first tells the whole idea in one picture: a journey strip that plays
 * through the five moments of an emergency for Asha, a made-up patient. Then one card per kind of person, each with
 * a small drawing, one human sentence and the way in. Until the login (SEC), the hospital, unit or patient is
 * chosen right here: hospitals from the service's list, units and patients by their id.
 */
export function HomePage() {
  const facilities = usePolling('facilities', () => getJson<FacilityInfo[]>('/api/facilities'), 30_000)
  const hospitals = (facilities.data ?? []).filter((f) => f.kind === 'HOSPITAL')
  const step = useStoryStep()

  return (
    <AppShell>
      <div className={styles.page}>
        <section className={styles.hero} aria-labelledby="hero-title">
          <div className={styles.heroText}>
            <p className={styles.eyebrow}>Emergency care for Bengaluru</p>
            <h1 id="hero-title" className={styles.heroTitle}>From the first warning sign to the hospital door.</h1>
            <p className={styles.heroLead}>
              A small sensor on the wrist keeps an eye on someone you love. The moment their readings turn
              dangerous, the nearest right ambulance is on its way, and the hospital knows they are coming.
            </p>
          </div>
          <figure className={styles.story} aria-label="How a call unfolds">
            <figcaption className={styles.storyCaption}>How a call unfolds</figcaption>
            <JourneyStrip step={step} name="Asha" />
          </figure>
        </section>

        <section aria-labelledby="who-title" className={styles.who}>
          <div className={styles.whoHeader}>
            <h2 id="who-title" className={styles.whoTitle}>Who are you today?</h2>
            <p className={styles.whoLead}>Everyone sees the same story, from their own side.</p>
          </div>

          <div className={styles.cards}>
            <RoleCard scene={<CityScene />} title="I watch over the city"
                      text="See every call, crew and hospital at once, and step in whenever you are needed."
                      tint="busy">
              <Link to="/control" className={styles.primary}>
                Open the control room <ArrowRight size={16} aria-hidden="true" />
              </Link>
            </RoleCard>

            <RoleCard scene={<HospitalScene />} title="I work at a hospital"
                      text="Know who is coming, how unwell they are and when, before the doors open."
                      tint="critical">
              {facilities.error && <ErrorPanel error={facilities.error} />}
              {!facilities.error && hospitals.length === 0 && !facilities.loading && (
                <p className={styles.quiet}>No hospitals have joined yet.</p>
              )}
              {hospitals.length > 0 && <HospitalPicker hospitals={hospitals} />}
            </RoleCard>

            <RoleCard scene={<CrewScene />} title="I'm on an ambulance crew"
                      text="Your call on one screen: where to go, who you are helping and what they need."
                      tint="high">
              <IdForm label="Your unit" placeholder="als-1" path="/crew/" button="Start my shift" />
            </RoleCard>

            <RoleCard scene={<HomeScene />} title="I'm a patient or family"
                      text="See that help is coming, who is coming and when. You are not alone."
                      tint="ok">
              <IdForm label="Patient number" placeholder="p001" path="/patient/" button="See how they are" />
            </RoleCard>
          </div>
        </section>

        <p className={styles.footnote}>
          A demonstration. Every patient, crew and call you see here is simulated.
        </p>
      </div>
    </AppShell>
  )
}

/**
 * Moves the start page's story one step every {@link STORY_STEP_MS}, round and round. People who asked their
 * computer for less motion see the story standing still at "Help is coming".
 */
function useStoryStep(): JourneyStep {
  const [step, setStep] = useState<JourneyStep>(1)
  useEffect(() => {
    const still = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
    if (still) {
      return
    }
    const timer = window.setInterval(() => {
      setStep((s) => ((s + 1) % 5) as JourneyStep)
    }, STORY_STEP_MS)
    return () => window.clearInterval(timer)
  }, [])
  return step
}

/** One person's card: a drawing on a soft tint, who it is for, one sentence, and the way in. */
function RoleCard({ scene, title, text, tint, children }:
                    { scene: ReactNode; title: string; text: string; tint: 'busy' | 'critical' | 'high' | 'ok'; children: ReactNode }) {
  return (
    <article className={`${styles.card} ${styles[tint]}`} aria-label={title}>
      <div className={styles.scene}>{scene}</div>
      <div className={styles.cardBody}>
        <h3 className={styles.cardTitle}>{title}</h3>
        <p className={styles.cardText}>{text}</p>
        <div className={styles.cardAction}>{children}</div>
      </div>
    </article>
  )
}

/** A list of the hospitals to choose from, and a button that opens the chosen one's arrivals. */
function HospitalPicker({ hospitals }: { hospitals: FacilityInfo[] }) {
  const [chosen, setChosen] = useState('')
  const navigate = useNavigate()
  const id = chosen || hospitals[0].id
  return (
    <form
      className={styles.form}
      onSubmit={(e) => {
        e.preventDefault()
        navigate(`/hospital/${encodeURIComponent(id)}`)
      }}
    >
      <select className={styles.field} aria-label="Your hospital" value={id} onChange={(e) => setChosen(e.target.value)}>
        {hospitals.map((h) => (
          <option key={h.id} value={h.id}>{h.name}</option>
        ))}
      </select>
      <button type="submit" className={styles.primary}>
        See who is coming <ArrowRight size={16} aria-hidden="true" />
      </button>
    </form>
  )
}

/** A one-field form that opens path + the typed id (the placeholder's id when left empty). */
function IdForm({ label, placeholder, path, button }: { label: string; placeholder: string; path: string; button: string }) {
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
      <input className={styles.field} aria-label={label} placeholder={`${label}, for example ${placeholder}`}
             value={id} onChange={(e) => setId(e.target.value)} />
      <button type="submit" className={styles.primary}>
        {button} <ArrowRight size={16} aria-hidden="true" />
      </button>
    </form>
  )
}
