import { Bell, Check, CircleAlert, TriangleAlert } from 'lucide-react'
import { clockTime } from '../labels'
import styles from '../pages/pages.module.css'
import css from './control.module.css'
import type { Alert } from './alerts'

/**
 * The alarm list: always in the same place and the same height, so rows never jump under the dispatcher's cursor.
 * Newest first; an alert about a case opens that case.
 */
export function AlertsPanel({ alerts, onAcknowledge, onAcknowledgeAll, onSelectCase }: {
  alerts: Alert[]
  onAcknowledge: (id: string) => void
  onAcknowledgeAll: () => void
  onSelectCase: (caseId: string) => void
}) {
  return (
    <section className={styles.panel} aria-labelledby="alerts-title">
      <div className={styles.panelHead}>
        <h2 id="alerts-title">Alerts</h2>
        <span className={`${styles.count} ${alerts.length > 0 ? css.countAlarm : ''}`}>{alerts.length}</span>
        {alerts.length > 1 && (
          <button type="button" className={`${css.small} ${styles.panelMeta}`} onClick={onAcknowledgeAll}>
            Acknowledge all
          </button>
        )}
      </div>
      <div className={css.alertList}>
        {alerts.length === 0 && (
          <p className={css.noAlerts}>
            <Bell size={16} aria-hidden="true" /> No alerts. New ones appear here.
          </p>
        )}
        {alerts.length > 0 && (
          <ul>
            {alerts.map((a) => (
              <li key={a.id} className={css.alert}>
                {a.level === 'critical'
                  ? <CircleAlert size={16} className={css.critical} aria-label="Critical" />
                  : <TriangleAlert size={16} className={css.warning} aria-label="Warning" />}
                {a.caseId
                  ? <button type="button" className={css.alertText} onClick={() => onSelectCase(a.caseId!)}>{a.text}</button>
                  : <span className={css.alertText}>{a.text}</span>}
                <span className={css.alertTime}>{clockTime(a.at)}</span>
                <button type="button" className={css.iconButton} aria-label={`Acknowledge: ${a.text}`}
                        title="Acknowledge" onClick={() => onAcknowledge(a.id)}>
                  <Check size={16} aria-hidden="true" />
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}
