import type { VitalsReading } from '../api/types'
import { CONSCIOUSNESS, VITAL_SIGNS, alertnessAbnormal, outOfRange, trendPoints } from '../vitals'
import css from './vitals.module.css'

/**
 * A patient's vital signs as large numbers, one cell each: heart rate, breathing, SpO2, blood pressure, temperature
 * and alertness. A value outside its normal range is drawn in the warning colour and says so. With `history`, each
 * cell also draws the trend over that history.
 *
 * @param latest  the newest reading (null: nothing measured yet)
 * @param history readings oldest first, for the trend lines
 * @param size    "large" for the patient file, "small" for cards and phones
 */
export function VitalsGrid({ latest, history, size = 'large' }: {
  latest: VitalsReading | null
  history?: VitalsReading[]
  size?: 'large' | 'small'
}) {
  if (!latest) {
    return <p className={css.none}>No readings yet.</p>
  }
  return (
    <dl className={`${css.grid} ${size === 'small' ? css.small : ''}`}>
      {VITAL_SIGNS.map((sign) => {
        const off = outOfRange(sign, latest)
        const points = history ? trendPoints(history.map((r) => sign.number(r)), 160, 34) : ''
        return (
          <div key={sign.key} className={css.cell}>
            <dt className={css.label}>{sign.label}</dt>
            <dd className={`${css.value} ${off ? css.off : ''}`}>
              {sign.text(latest)}<span className={css.unit}>{sign.unit}</span>
            </dd>
            {points && (
              <dd className={css.trend}>
                <svg viewBox="0 0 160 34" preserveAspectRatio="none" aria-hidden="true">
                  <polyline points={points} />
                </svg>
              </dd>
            )}
            {size === 'large' && <dd className={css.note}>{off ? `Outside normal. ${sign.normalText}.` : sign.normalText}</dd>}
          </div>
        )
      })}
      <div className={css.cell}>
        <dt className={css.label}>Alertness</dt>
        <dd className={`${css.value} ${css.word} ${alertnessAbnormal(latest) ? css.off : ''}`}>
          {latest.consciousness ? CONSCIOUSNESS[latest.consciousness] : '–'}
        </dd>
        {size === 'large' && <dd className={css.note}>{alertnessAbnormal(latest) ? 'Not fully alert' : 'Fully alert'}</dd>}
      </div>
    </dl>
  )
}
