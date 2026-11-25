import styles from './components.module.css'

/** "Live" while the live feed streams; "Reconnecting" while it does not (the browser retries by itself). */
export function ConnectionBadge({ connected }: { connected: boolean }) {
  return (
    <span className={`${styles.connection} ${connected ? styles.live : ''}`} role="status">
      {connected ? 'Live' : 'Reconnecting'}
    </span>
  )
}
