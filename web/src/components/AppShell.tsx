import type { ReactNode } from 'react'
import { ChevronRight } from 'lucide-react'
import { Link } from 'react-router'
import { Clock } from './Clock'
import { ThemePicker } from './ThemePicker'
import styles from './components.module.css'

/**
 * The frame around every page: a quiet sticky header (the MediDispatch mark, where you are, Bangalore time, an
 * optional status item, the theme picker) and the page.
 *
 * @param app  where this page is ("Control room"), shown as a breadcrumb after the mark
 * @param wide   use a wider column (the control room)
 * @param narrow use a phone-width column (the crew and patient screens)
 */
export function AppShell({ app, headerEnd, wide, narrow, children }:
                           { app?: string; headerEnd?: ReactNode; wide?: boolean; narrow?: boolean; children: ReactNode }) {
  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <Link to="/" className={styles.brand}>
          <img src="/favicon.svg" alt="" className={styles.logo} />
          <span className={styles.brandText}>MediDispatch</span>
        </Link>
        {app && (
          <span className={styles.crumb}>
            <ChevronRight size={14} className={styles.crumbIcon} aria-hidden="true" />
            {app}
          </span>
        )}
        <div className={styles.headerEnd}>
          <Clock />
          {headerEnd}
          <ThemePicker />
        </div>
      </header>
      <main className={`${styles.main} ${wide ? styles.wide : ''} ${narrow ? styles.narrowMain : ''}`}>{children}</main>
    </div>
  )
}
