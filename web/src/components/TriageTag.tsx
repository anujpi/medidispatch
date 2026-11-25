import type { RiskBand } from '../api/types'
import { BAND } from '../labels'
import styles from './components.module.css'

/**
 * How urgent a patient is, as the edge of a row: a solid block of the triage colour with the word on it, like the
 * paper tags tied to patients at a scene. It fills the row's height, so a column of tags reads as a column of urgency.
 *
 * @param waiting the case still has no unit: the tag breathes (the only motion on these screens)
 */
export function TriageTag({ band, waiting }: { band: RiskBand; waiting?: boolean }) {
  return (
    <span className={`${styles.tag} ${styles[band]} ${waiting ? styles.waiting : ''}`}>{BAND[band]}</span>
  )
}
