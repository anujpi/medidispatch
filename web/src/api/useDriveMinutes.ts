import { useEffect, useRef } from 'react'
import { getJson } from './client'
import type { RouteInfo } from './types'
import { usePolling } from './usePolling'
import { routeQuery, type LatLon } from '../map/routes'

/** How often the drive time is asked again. */
const EVERY_MS = 10_000

/**
 * The drive time from a moving unit to where it is going, by road with the siren on, asked every 10 s. The
 * positions are read through a ref, so a unit moving every second does not restart the polling; `key` (the trip
 * and its leg) does.
 *
 * @returns whole minutes, at least 1, or null until the first answer (or when there is no route)
 */
export function useDriveMinutes(key: string, from: LatLon | null, to: LatLon | null): number | null {
  const where = useRef({ from, to })
  useEffect(() => {
    where.current = { from, to }
  })
  const route = usePolling(key, () => {
    const { from: a, to: b } = where.current
    if (!a || !b) return Promise.resolve(null)
    return getJson<RouteInfo>('/api/route?' + routeQuery(a, b))
  }, EVERY_MS)
  return route.data ? Math.max(1, Math.ceil(route.data.seconds / 60)) : null
}
