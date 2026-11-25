import { useState } from 'react'
import type { LiveSnapshot } from '../api/types'
import { minutesBetween } from '../labels'

/** A critical case still waiting for a unit after this many minutes raises an alert. */
export const LATE_MINUTES = 2

/** Something the dispatcher should see, until they acknowledge it. */
export interface Alert {
  /** the condition and the moment it began, e.g. "offline:als-1@2026-11-24T13:00:00Z" */
  id: string
  level: 'critical' | 'warning'
  text: string
  /** the snapshot time it was raised at */
  at: string
  /** the case it is about, so the dispatcher can jump to it; null for units and hospitals */
  caseId: string | null
}

type Condition = Omit<Alert, 'id' | 'at'>

/**
 * Everything alarming in one picture of the city, keyed by what it is about: "critical:c-1", "late:c-1",
 * "offline:als-1", "diversion:blr-h-x". An alert is raised when a key appears that the previous picture did not have.
 */
export function conditions(s: LiveSnapshot): Map<string, Condition> {
  const found = new Map<string, Condition>()
  for (const c of s.openCases) {
    const name = c.patientName ?? c.patientId
    if (c.band === 'CRITICAL') {
      found.set('critical:' + c.id, { level: 'critical', text: `${name} is critical`, caseId: c.id })
      const waited = minutesBetween(c.openedAt, s.at)
      if (c.status === 'ALERTED' && waited >= LATE_MINUTES) {
        found.set('late:' + c.id,
          { level: 'critical', text: `${name} has waited ${waited} min for a unit`, caseId: c.id })
      }
    }
  }
  for (const u of s.units) {
    if (u.status === 'OFFLINE') {
      found.set('offline:' + u.id, { level: 'warning', text: `${u.id} went offline`, caseId: null })
    }
  }
  for (const f of s.facilities) {
    if (f.kind === 'HOSPITAL' && !f.accepting) {
      found.set('diversion:' + f.id, { level: 'warning', text: `${f.name} went on diversion`, caseId: null })
    }
  }
  return found
}

/**
 * The new alerts in this picture: conditions the previous picture did not have. On the first picture (previous is
 * null) everything is already on screen in the lists, so only a critical patient left waiting is raised.
 *
 * @returns the new alerts, newest-first order is up to the caller
 */
export function freshAlerts(previous: Set<string> | null, now: Map<string, Condition>, at: string): Alert[] {
  const fresh: Alert[] = []
  for (const [key, condition] of now) {
    const isNew = previous === null ? key.startsWith('late:') : !previous.has(key)
    if (isNew) {
      fresh.push({ ...condition, id: key + '@' + at, at })
    }
  }
  return fresh
}

const KEEP = 50

/**
 * The control room's unacknowledged alerts, raised by comparing each live picture with the one before. An alert
 * stays until acknowledged, even if its cause has gone (a case that closed): the dispatcher may not have seen it.
 */
export function useAlerts(snapshot: LiveSnapshot | null) {
  const [state, setState] = useState<{ seen: LiveSnapshot | null; active: Set<string> | null; alerts: Alert[] }>(
    { seen: null, active: null, alerts: [] })

  // a new picture: work out what is new while rendering (React's way of deriving state from a changed input)
  if (snapshot && snapshot !== state.seen) {
    const now = conditions(snapshot)
    const fresh = freshAlerts(state.active, now, snapshot.at)
    setState({ seen: snapshot, active: new Set(now.keys()), alerts: [...fresh, ...state.alerts].slice(0, KEEP) })
  }

  return {
    alerts: state.alerts,
    acknowledge: (id: string) => {
      setState((before) => ({ ...before, alerts: before.alerts.filter((a) => a.id !== id) }))
    },
    acknowledgeAll: () => {
      setState((before) => ({ ...before, alerts: [] }))
    },
  }
}
