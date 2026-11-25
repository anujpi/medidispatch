import { describe, expect, it } from 'vitest'
import type { LiveSnapshot } from '../api/types'
import { sampleSnapshot } from '../test/fakes'
import { conditions, freshAlerts } from './alerts'

// the sample city a while later, changed by `change`
function later(change: (s: LiveSnapshot) => void, at = '2026-11-24T13:01:00Z'): LiveSnapshot {
  const s = sampleSnapshot()
  s.at = at
  change(s)
  return s
}

function keys(s: LiveSnapshot) {
  return new Set(conditions(s).keys())
}

describe('alerts', () => {
  it('the first picture raises nothing that the lists already show', () => {
    const s = sampleSnapshot()                  // a critical case with a unit on the way
    expect(freshAlerts(null, conditions(s), s.at)).toEqual([])
  })

  it('a case turning critical is raised once, not again on every picture', () => {
    const before = sampleSnapshot()
    const after = later((s) => { s.openCases[0].band = 'CRITICAL' })     // Meena Iyer was medium

    // she has also waited 11 min with no unit, so that is raised too
    const fresh = freshAlerts(keys(before), conditions(after), after.at)
    expect(fresh.map((a) => a.text)).toEqual(['Meena Iyer is critical', 'Meena Iyer has waited 11 min for a unit'])
    expect(fresh[0]).toMatchObject({ level: 'critical', caseId: 'c-medium' })

    expect(freshAlerts(keys(after), conditions(after), after.at)).toEqual([])
  })

  it('a critical patient waiting too long for a unit is raised, even on the first picture', () => {
    // Asha Rao, critical, opened 12:55, no unit yet; the picture is from 13:00
    const s = later((one) => { one.openCases[1].status = 'ALERTED' }, '2026-11-24T13:00:00Z')

    const fresh = freshAlerts(null, conditions(s), s.at)
    expect(fresh.map((a) => a.text)).toEqual(['Asha Rao has waited 5 min for a unit'])
  })

  it('a unit going offline and a hospital going on diversion are warnings', () => {
    const before = later((s) => {
      s.facilities = [{ id: 'h-1', name: 'Jayanagar General', kind: 'HOSPITAL', latitude: 12.9, longitude: 77.6,
        capabilities: [], accepting: true, phone: null }]
    })
    const after = later((s) => {
      s.units[1].status = 'OFFLINE'
      s.facilities = [{ ...before.facilities[0], accepting: false }]
    })

    const fresh = freshAlerts(keys(before), conditions(after), after.at)
    expect(fresh.map((a) => [a.level, a.text])).toEqual([
      ['warning', 'bls-1 went offline'],
      ['warning', 'Jayanagar General went on diversion'],
    ])
    expect(fresh[0].caseId).toBeNull()
  })

  it('the same cause coming back later is a new alert', () => {
    const offline = later((s) => { s.units[1].status = 'OFFLINE' })
    const back = sampleSnapshot()

    const again = freshAlerts(keys(back), conditions(offline), '2026-11-24T13:05:00Z')
    expect(again).toHaveLength(1)
    expect(again[0].id).toBe('offline:bls-1@2026-11-24T13:05:00Z')
  })
})
