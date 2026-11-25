import { CircleAlert } from 'lucide-react'
import type { ApiError } from '../api/client'
import styles from './components.module.css'

/**
 * An API failure in words. "Cannot reach the dispatch service" (it is down, or the network is) is a different
 * problem from "not found" (a wrong id), and whoever looks at the screen should know which.
 *
 * @param notFound what to say for a 404, for example 'No hospital with the id "blr-h-x"'
 */
export function ErrorPanel({ error, notFound }: { error: ApiError; notFound?: string }) {
  let title: string
  let detail: string
  if (error.unreachable) {
    title = 'Cannot reach the dispatch service'
    detail = 'Retrying automatically. Check that the service is running.'
  } else if (error.status === 404) {
    title = notFound ?? 'Not found'
    detail = 'Check the id in the address.'
  } else {
    title = `The service refused the request (${error.status})`
    detail = error.message
  }
  return (
    <div className={styles.error} role="alert">
      <CircleAlert size={18} className={styles.errorIcon} aria-hidden="true" />
      <div>
        <strong>{title}</strong>
        <p>{detail}</p>
      </div>
    </div>
  )
}
