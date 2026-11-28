import type { ReactNode } from 'react'
import { AmbulanceIcon, CrewIcon, HeartIcon, SafeIcon, SirenIcon } from './Doodles'
import { JOURNEY, journeySentence, type JourneyStep } from './journey'
import styles from './journey.module.css'

const ICONS: ReactNode[] = [
  <HeartIcon key="unwell" />,
  <SirenIcon key="coming" />,
  <CrewIcon key="with" />,
  <AmbulanceIcon key="hospital" />,
  <SafeIcon key="safe" />,
]

/**
 * The journey strip: the five moments of an emergency as round stops on a road, the ones already passed filled in,
 * the current one lifted and gently pulsing, and a small ambulance riding along the road to it. Underneath, one
 * plain sentence says where things stand. Screen readers get an ordered list with the current step marked.
 *
 * @param step    the current step (see journeyStep())
 * @param name    who the story is about, for the sentence ("Asha")
 * @param compact smaller stops and no sentence, for tight places such as a table row or a card header
 */
export function JourneyStrip({ step, name, compact }: { step: JourneyStep; name?: string; compact?: boolean }) {
  const progress = (step / (JOURNEY.length - 1)) * 100
  return (
    <div className={`${styles.strip} ${compact ? styles.compact : ''}`}>
      <div className={styles.road} aria-hidden="true">
        <div className={styles.roadDone} style={{ width: `${progress}%` }} />
        <span className={styles.rider} style={{ left: `${progress}%` }}>
          <AmbulanceIcon size={22} />
        </span>
      </div>
      <ol className={styles.stops} aria-label="Where things stand">
        {JOURNEY.map((s, i) => {
          const state = i < step ? styles.done : i === step ? styles.current : styles.upcoming
          return (
            <li key={s.key} className={`${styles.stop} ${state}`} aria-current={i === step ? 'step' : undefined}>
              <span className={styles.bubble}>{ICONS[i]}</span>
              <span className={styles.label}>{s.label}</span>
            </li>
          )
        })}
      </ol>
      {!compact && <p className={styles.sentence} aria-live="polite">{journeySentence(step, name)}</p>}
    </div>
  )
}
