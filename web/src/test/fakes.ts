import { vi } from 'vitest'
import type { LiveSnapshot } from '../api/types'

/**
 * A stand-in for the browser's EventSource (jsdom has none): tests push events with emit() and drop the stream with
 * fail(). Every instance created is kept in {@link FakeEventSource.instances}.
 */
export class FakeEventSource {
  static instances: FakeEventSource[] = []

  readonly url: string
  closed = false
  onerror: (() => void) | null = null
  private listeners = new Map<string, ((e: MessageEvent<string>) => void)[]>()

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: (e: MessageEvent<string>) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }

  removeEventListener(type: string, listener: (e: MessageEvent<string>) => void) {
    this.listeners.set(type, (this.listeners.get(type) ?? []).filter((l) => l !== listener))
  }

  close() {
    this.closed = true
  }

  /** Delivers one event, as the service would. */
  emit(type: string, data: string) {
    for (const listener of this.listeners.get(type) ?? []) {
      listener(new MessageEvent(type, { data }))
    }
  }

  /** The connection drops. */
  fail() {
    this.onerror?.()
  }

  /** Replaces the browser's EventSource with this fake for the current test. */
  static install() {
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
  }

  /** @returns the stream most recently opened */
  static latest(): FakeEventSource {
    return FakeEventSource.instances[FakeEventSource.instances.length - 1]
  }
}

/** A small live picture: one free and one busy unit, two open cases, one trip. */
export function sampleSnapshot(): LiveSnapshot {
  return {
    at: '2026-11-24T13:00:00Z',
    units: [
      { id: 'als-1', type: 'AMBULANCE_ALS', status: 'ASSIGNED', latitude: 12.93, longitude: 77.62,
        lastSeenAt: null, baseId: 'blr-h-koramangala', driver: { name: 'Ravi Kumar', phone: '+91 90000 00001' } },
      { id: 'bls-1', type: 'AMBULANCE_BLS', status: 'AVAILABLE', latitude: 12.92, longitude: 77.59,
        lastSeenAt: null, baseId: 'blr-h-jayanagar', driver: null },
    ],
    openCases: [
      { id: 'c-medium', patientId: 'p002', status: 'ALERTED', band: 'MEDIUM', openedAt: '2026-11-24T12:50:00Z',
        latitude: 12.95, longitude: 77.6, patientName: 'Meena Iyer', age: 41, sex: 'FEMALE' },
      { id: 'c-critical', patientId: 'p001', status: 'RESPONDING', band: 'CRITICAL', openedAt: '2026-11-24T12:55:00Z',
        latitude: 12.94, longitude: 77.61, patientName: 'Asha Rao', age: 68, sex: 'FEMALE' },
    ],
    activeTrips: [
      { id: 't-1', caseId: 'c-critical', unitId: 'als-1', role: 'PRIMARY', status: 'EN_ROUTE',
        assignedAt: '2026-11-24T12:56:00Z', hospitalId: null, needs: [] },
    ],
    facilities: [],
  }
}

/** A fetch() stand-in answering from a table of path -> [status, body]; anything else is a 404. */
export function fakeFetch(answers: Record<string, [number, unknown]>) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input)
    const answer = answers[path]
    if (!answer) {
      return new Response(JSON.stringify({ status: 404, detail: `No ${path}` }), { status: 404 })
    }
    // no body at all (null, not ''): a 204 answer may not have one
    return new Response(answer[1] === undefined ? null : JSON.stringify(answer[1]), { status: answer[0] })
  })
}
