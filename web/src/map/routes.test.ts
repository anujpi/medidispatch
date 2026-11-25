import { describe, expect, it } from 'vitest'
import { ahead, type LatLon, type Leg } from './routes'

const UNIT: LatLon = [12.9, 77.6]
const TARGET: LatLon = [12.95, 77.6]

describe('the road still ahead of a unit', () => {
  it('is the straight line until the route arrives', () => {
    expect(ahead(undefined, UNIT, TARGET)).toEqual([UNIT, TARGET])
    expect(ahead({ points: null, next: 0 }, UNIT, TARGET)).toEqual([UNIT, TARGET])
  })

  it('starts at the unit and keeps only the route after the point it is nearest to', () => {
    const leg: Leg = { points: [[12.9, 77.6], [12.91, 77.6], [12.92, 77.6], [12.95, 77.6]], next: 0 }

    expect(ahead(leg, [12.911, 77.6], TARGET)).toEqual([[12.911, 77.6], [12.92, 77.6], [12.95, 77.6]])
    expect(leg.next).toBe(1)
  })

  it('never jumps back to an earlier part of a road that loops near itself', () => {
    // the route goes north, then comes back south past its own start
    const leg: Leg = { points: [[12.9, 77.6], [12.93, 77.6], [12.93, 77.61], [12.9, 77.61], [12.89, 77.61]], next: 3 }

    // the unit is nearest to point 0 as the crow flies, but it has already driven past it
    expect(ahead(leg, [12.9, 77.6], TARGET)).toEqual([[12.9, 77.6], [12.89, 77.61]])
    expect(leg.next).toBe(3)
  })
})
