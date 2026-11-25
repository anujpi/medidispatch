import { useEffect, useState } from 'react'
import styles from './components.module.css'

const BANGALORE_TIME = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Kolkata', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
})

/**
 * Bangalore time, to the second, whatever time zone the screen's computer is set to: dispatchers and hospitals
 * read and log times in local time.
 */
export function Clock() {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(timer)
  }, [])
  return (
    <time className={styles.clock} dateTime={now.toISOString()}>
      {BANGALORE_TIME.format(now)}
      <span className={styles.zone}>IST</span>
    </time>
  )
}
