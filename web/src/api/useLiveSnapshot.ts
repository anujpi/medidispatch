import { useEffect, useState } from 'react'
import type { LiveSnapshot } from './types'

/** What a live screen gets: the latest picture (null until the first arrives) and whether the stream is up. */
export interface LiveState {
  snapshot: LiveSnapshot | null
  connected: boolean
}

/**
 * Listens to the service's live feed (GET /api/live, Server-Sent Events): a "snapshot" event with the whole picture
 * once a second. The browser's EventSource reconnects by itself after a drop; meanwhile {@code connected} is false
 * and the last picture stays on screen. The stream is closed when the component using this goes away.
 *
 * <p>Every screen that calls this opens its own stream. ponytail: fine for one screen per tab; share one stream
 * through a React context if a page ever shows several live widgets.
 */
export function useLiveSnapshot(): LiveState {
  const [state, setState] = useState<LiveState>({ snapshot: null, connected: false })

  useEffect(() => {
    const feed = new EventSource('/api/live')
    const onSnapshot = (e: MessageEvent<string>) => {
      try {
        setState({ snapshot: JSON.parse(e.data) as LiveSnapshot, connected: true })
      } catch {
        // a broken picture is skipped; the next one comes in a second
      }
    }
    feed.addEventListener('snapshot', onSnapshot)
    feed.onerror = () => {
      setState((before) => ({ ...before, connected: false }))
    }
    return () => {
      feed.removeEventListener('snapshot', onSnapshot)
      feed.close()
    }
  }, [])

  return state
}
