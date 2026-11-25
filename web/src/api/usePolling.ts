import { useEffect, useState } from 'react'
import { ApiError } from './client'

/** What a polling screen gets. {@code data} keeps the last good answer while a later call fails. */
export interface PollState<T> {
  data: T | null
  error: ApiError | null
  loading: boolean
}

/**
 * Calls {@code load} now and then every {@code everyMs}, and stops when the component goes away or {@code key}
 * changes (for example another hospital id in the address). Answers that come back after that are ignored, so an old
 * hospital's list can never overwrite the new one's.
 *
 * @param key     what is being watched; a new key starts over
 * @param load    fetches the data (usually a getJson call)
 * @param everyMs how often to ask again
 */
export function usePolling<T>(key: string, load: () => Promise<T>, everyMs: number): PollState<T> {
  const [state, setState] = useState<PollState<T> & { key: string }>({ data: null, error: null, loading: true, key })

  // a new key starts from "loading", decided while rendering (React's way of resetting state when an input changes)
  if (state.key !== key) {
    setState({ data: null, error: null, loading: true, key })
  }

  useEffect(() => {
    let current = true
    const tick = () => {
      load().then(
        (data) => { if (current) setState({ data, error: null, loading: false, key }) },
        (e: unknown) => {
          const error = e instanceof ApiError ? e : new ApiError(0, String(e))
          if (current) setState((before) => ({ ...before, error, loading: false }))
        },
      )
    }
    tick()
    const timer = setInterval(tick, everyMs)
    return () => {
      current = false
      clearInterval(timer)
    }
    // `load` is a new function on every render; the key says when it really asks for something else
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, everyMs])

  return { data: state.data, error: state.error, loading: state.loading }
}
