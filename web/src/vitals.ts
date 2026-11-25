import type { Consciousness, VitalsReading } from './api/types'

/**
 * The vital signs the screens show, how to read each from a reading, and its normal adult range (the ranges where
 * NEWS2 gives 0 points). A value outside its range is drawn in the warning colour; this is a display aid, not the
 * engine's scoring.
 */
export interface VitalSign {
  key: string
  label: string
  unit: string
  /** the number to compare and to draw the trend line with; null when not measured */
  number: (r: VitalsReading) => number | null
  /** the value as written on screen (blood pressure is "systolic/diastolic") */
  text: (r: VitalsReading) => string
  /** the normal range, inclusive */
  low: number
  high: number
  normalText: string
}

const whole = (n: number | null) => (n === null ? '–' : String(n))

export const VITAL_SIGNS: VitalSign[] = [
  { key: 'hr', label: 'Heart rate', unit: 'bpm', number: (r) => r.heartRate, text: (r) => whole(r.heartRate),
    low: 51, high: 90, normalText: 'Normal 51–90' },
  { key: 'rr', label: 'Breathing rate', unit: '/min', number: (r) => r.respRate, text: (r) => whole(r.respRate),
    low: 12, high: 20, normalText: 'Normal 12–20' },
  { key: 'spo2', label: 'SpO2', unit: '%', number: (r) => r.spo2, text: (r) => whole(r.spo2),
    low: 96, high: 100, normalText: 'Normal 96 or more' },
  { key: 'bp', label: 'Blood pressure', unit: 'mmHg', number: (r) => r.systolicBp,
    text: (r) => (r.systolicBp === null ? '–' : `${r.systolicBp}/${r.diastolicBp ?? '–'}`),
    low: 111, high: 219, normalText: 'Normal systolic 111–219' },
  { key: 'temp', label: 'Temperature', unit: '°C', number: (r) => r.temperature,
    text: (r) => (r.temperature === null ? '–' : r.temperature.toFixed(1)),
    low: 36.1, high: 38.0, normalText: 'Normal 36.1–38.0' },
]

export const CONSCIOUSNESS: Record<Consciousness, string> = {
  ALERT: 'Alert',
  CONFUSED: 'Confused',
  VOICE: 'Responds to voice',
  PAIN: 'Responds to pain',
  UNRESPONSIVE: 'Unresponsive',
}

/** @returns true when the value is measured and outside the normal range */
export function outOfRange(sign: VitalSign, r: VitalsReading): boolean {
  const n = sign.number(r)
  return n !== null && (n < sign.low || n > sign.high)
}

/** @returns true unless the patient is fully alert (or it was not measured) */
export function alertnessAbnormal(r: VitalsReading): boolean {
  return r.consciousness !== null && r.consciousness !== 'ALERT'
}

/**
 * The points of a small trend line: x spread evenly across the width, y scaled between the series' own minimum and
 * maximum (a flat series sits in the middle). Unmeasured readings are skipped.
 *
 * @returns an SVG polyline "points" string, empty when there are fewer than two values
 */
export function trendPoints(values: (number | null)[], width: number, height: number): string {
  const known: number[] = []
  for (const v of values) {
    if (v !== null) known.push(v)
  }
  if (known.length < 2) return ''
  let min = Math.min(...known)
  let max = Math.max(...known)
  if (min === max) {
    min -= 1
    max += 1
  }
  const pad = 2
  const points: string[] = []
  for (let i = 0; i < known.length; i++) {
    const x = pad + (i * (width - 2 * pad)) / (known.length - 1)
    const y = height - pad - ((known[i] - min) * (height - 2 * pad)) / (max - min)
    points.push(`${x.toFixed(1)},${y.toFixed(1)}`)
  }
  return points.join(' ')
}
