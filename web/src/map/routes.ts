import { getJson } from '../api/client'
import type { RouteInfo } from '../api/types'

/** A point as [latitude, longitude], the way Leaflet and /api/route both write it. */
export type LatLon = [number, number]

/** One leg of a trip (to the patient, or to the hospital): its road once the service has answered. */
export interface Leg {
  /** the route's points, null until the answer comes (or if none does: then the straight line stays) */
  points: LatLon[] | null
  /** the route point the unit was last nearest to; the search never goes back before it */
  next: number
}

/**
 * Asks the service once for the way by road from the unit to its target, with the siren on (the same question the
 * crew's phone asks), and fills in the leg when the answer comes.
 *
 * @returns a new leg, empty until the answer arrives
 */
export function askRoute(from: LatLon, to: LatLon): Leg {
  const leg: Leg = { points: null, next: 0 }
  getJson<RouteInfo>('/api/route?' + routeQuery(from, to))
    .then((route) => {
      leg.points = route.points
    })
    .catch(() => {
      // no route: the straight line stays
    })
  return leg
}

/** The question for /api/route: from here to there, siren on. */
export function routeQuery(from: LatLon, to: LatLon): string {
  return new URLSearchParams({
    fromLat: String(from[0]), fromLon: String(from[1]), toLat: String(to[0]), toLon: String(to[1]), emergency: 'true',
  }).toString()
}

/**
 * The part of the route still ahead of the unit: from the unit, through the route points after the one it is
 * nearest to. The search only moves forward, so a road that passes near itself cannot make the line jump back.
 *
 * @returns the line to draw; the straight line from unit to target while there is no route
 */
export function ahead(leg: Leg | undefined, unit: LatLon, target: LatLon): LatLon[] {
  if (!leg || !leg.points) {
    return [unit, target]
  }
  const points = leg.points
  const cos = Math.cos((unit[0] * Math.PI) / 180)       // a degree of longitude is shorter away from the equator
  let best = leg.next
  let bestDistance = Infinity
  for (let i = leg.next; i < points.length; i++) {
    const dLat = points[i][0] - unit[0]
    const dLon = (points[i][1] - unit[1]) * cos
    const distance = dLat * dLat + dLon * dLon
    if (distance < bestDistance) {
      bestDistance = distance
      best = i
    }
  }
  leg.next = best
  return [unit, ...points.slice(best + 1)]
}
