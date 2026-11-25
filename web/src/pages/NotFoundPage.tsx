import { Link } from 'react-router'
import { AppShell } from '../components/AppShell'
import styles from './pages.module.css'

/** Any address that is not one of the screens. */
export function NotFoundPage() {
  return (
    <AppShell>
      <div className={styles.pageHeader}>
        <h1>Page not found</h1>
        <p className={styles.subtitle}>Nothing lives at this address.</p>
      </div>
      <div>
        <Link to="/" className={`${styles.button} ${styles.buttonQuiet}`}>Go to the start page</Link>
      </div>
    </AppShell>
  )
}
