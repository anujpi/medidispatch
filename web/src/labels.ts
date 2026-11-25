import type { CaseStatus, ResourceType, RiskBand, TripStatus } from './api/types'

/**
 * The service's enum values in the words a dispatcher or a nurse uses. The screens never show raw enum names
 * ("EN_ROUTE"); they show these.
 */

export const BAND: Record<RiskBand, string> = {
  CRITICAL: 'Critical',
  HIGH: 'High',
  MEDIUM: 'Medium',
  LOW: 'Low',
}

export const CASE_STATUS: Record<CaseStatus, string> = {
  MONITORING: 'Monitoring',
  ALERTED: 'Awaiting unit',
  RESPONDING: 'Unit on the way',
  TRANSPORTING: 'To hospital',
  STABILISED: 'Stabilised',
  CLOSED: 'Closed',
  CANCELLED: 'Cancelled',
}

export const TRIP_STATUS: Record<TripStatus, string> = {
  ASSIGNED: 'Assigned',
  EN_ROUTE: 'En route',
  ARRIVED: 'On scene',
  TRANSPORTING: 'To hospital',
  DONE: 'Done',
  CANCELLED: 'Cancelled',
  TIMED_OUT: 'No answer',
  FAILED: 'Failed',
}

export const UNIT_TYPE: Record<ResourceType, string> = {
  AMBULANCE_ALS: 'ALS ambulance',
  AMBULANCE_BLS: 'BLS ambulance',
  NURSE: 'Nurse',
  DOCTOR: 'Doctor',
  COURIER: 'Courier',
}

/** "Asha Rao, 68 F" (or the id for an anonymous patient). */
export function personLine(name: string | null, fallbackId: string, age: number | null, sex: string | null): string {
  const who = name ?? fallbackId
  if (age === null) return who
  return `${who}, ${age}${sex ? ' ' + sex.charAt(0) : ''}`
}

/** Whole minutes from one ISO time to another, never negative. */
export function minutesBetween(fromIso: string, toIso: string): number {
  return Math.max(0, Math.floor((Date.parse(toIso) - Date.parse(fromIso)) / 60_000))
}
