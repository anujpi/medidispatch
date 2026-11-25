import { describe, expect, it } from 'vitest'
import type { VitalsReading } from './api/types'
import { VITAL_SIGNS, alertnessAbnormal, outOfRange, trendPoints } from './vitals'

const NORMAL: VitalsReading = {
  patientId: 'p1', timeStamp: '2026-11-25T12:00:00Z', heartRate: 75, spo2: 98, systolicBp: 120, diastolicBp: 80,
  respRate: 14, temperature: 36.8, consciousness: 'ALERT',
}

const sign = (key: string) => VITAL_SIGNS.find((s) => s.key === key)!

describe('vital signs', () => {
  it('a normal reading is inside every range', () => {
    for (const s of VITAL_SIGNS) {
      expect(outOfRange(s, NORMAL)).toBe(false)
    }
    expect(alertnessAbnormal(NORMAL)).toBe(false)
  })

  it('the range edges count as normal, one step past them does not', () => {
    expect(outOfRange(sign('hr'), { ...NORMAL, heartRate: 90 })).toBe(false)
    expect(outOfRange(sign('hr'), { ...NORMAL, heartRate: 91 })).toBe(true)
    expect(outOfRange(sign('hr'), { ...NORMAL, heartRate: 50 })).toBe(true)
    expect(outOfRange(sign('spo2'), { ...NORMAL, spo2: 95 })).toBe(true)
    expect(outOfRange(sign('temp'), { ...NORMAL, temperature: 38.1 })).toBe(true)
  })

  it('a value that was not measured is never flagged, and is written as a dash', () => {
    const missing = { ...NORMAL, heartRate: null, systolicBp: null }
    expect(outOfRange(sign('hr'), missing)).toBe(false)
    expect(sign('hr').text(missing)).toBe('–')
    expect(sign('bp').text(missing)).toBe('–')
    expect(sign('bp').text(NORMAL)).toBe('120/80')
    expect(sign('temp').text(NORMAL)).toBe('36.8')
  })

  it('anything but fully alert is flagged', () => {
    expect(alertnessAbnormal({ ...NORMAL, consciousness: 'CONFUSED' })).toBe(true)
  })
})

describe('trend lines', () => {
  it('spread the values across the width, the highest at the top', () => {
    expect(trendPoints([10, 20, 30], 104, 24)).toBe('2.0,22.0 52.0,12.0 102.0,2.0')
  })

  it('skip unmeasured values and need at least two', () => {
    expect(trendPoints([10, null, 30], 104, 24)).toBe('2.0,22.0 102.0,2.0')
    expect(trendPoints([10], 104, 24)).toBe('')
    expect(trendPoints([], 104, 24)).toBe('')
  })

  it('draw a flat series across the middle', () => {
    expect(trendPoints([5, 5], 104, 24)).toBe('2.0,12.0 102.0,12.0')
  })
})
