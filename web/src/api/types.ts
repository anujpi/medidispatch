/**
 * The service's JSON, as TypeScript types. Written by hand to match the Java records in dispatch-service
 * (LiveSnapshot, UnitInfo, CaseInfo, ...); a field renamed there must be renamed here.
 * Times are ISO-8601 strings ("2026-11-24T13:05:00Z"); a Java Integer/String that may be null is `| null`.
 */

export type RiskBand = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
export type ResourceType = 'AMBULANCE_ALS' | 'AMBULANCE_BLS' | 'NURSE' | 'COURIER' | 'DOCTOR'
export type UnitStatus = 'AVAILABLE' | 'ASSIGNED' | 'OFFLINE'
export type TripStatus =
  | 'ASSIGNED' | 'EN_ROUTE' | 'ARRIVED' | 'TRANSPORTING' | 'DONE' | 'CANCELLED' | 'TIMED_OUT' | 'FAILED'
export type TripRole = 'PRIMARY' | 'SUPPORT'
export type CaseStatus =
  | 'MONITORING' | 'ALERTED' | 'RESPONDING' | 'TRANSPORTING' | 'STABILISED' | 'CLOSED' | 'CANCELLED'
export type Capability = 'CARDIAC' | 'STROKE' | 'TRAUMA' | 'PAEDIATRIC'
export type FacilityKind = 'HOSPITAL' | 'CLINIC'
export type Sex = 'FEMALE' | 'MALE' | 'OTHER'
export type Consciousness = 'ALERT' | 'CONFUSED' | 'VOICE' | 'PAIN' | 'UNRESPONSIVE'

export interface Contact {
  name: string
  phone: string
}

/** A unit (ambulance, nurse, doctor) as the live feed and GET /api/units/{id} show it. */
export interface UnitInfo {
  id: string
  type: ResourceType
  status: UnitStatus
  latitude: number
  longitude: number
  lastSeenAt: string | null
  baseId: string | null
  driver: Contact | null
}

/** An open case: a patient in trouble. */
export interface CaseInfo {
  id: string
  patientId: string
  status: CaseStatus
  band: RiskBand
  openedAt: string
  latitude: number
  longitude: number
  patientName: string | null
  age: number | null
  sex: Sex | null
}

/** A trip: one unit sent to one case. */
export interface TripInfo {
  id: string
  caseId: string
  unitId: string
  role: TripRole
  status: TripStatus
  assignedAt: string
  hospitalId: string | null
  needs: Capability[]
}

/** A hospital or clinic. */
export interface FacilityInfo {
  id: string
  name: string
  kind: FacilityKind
  latitude: number
  longitude: number
  capabilities: Capability[]
  accepting: boolean
  phone: string | null
}

/** GET /api/live, event "snapshot": the whole picture, once a second. */
export interface LiveSnapshot {
  at: string
  units: UnitInfo[]
  openCases: CaseInfo[]
  activeTrips: TripInfo[]
  facilities: FacilityInfo[]
}

/** One vitals reading from a patient's wearable. Measurements the device did not take are null. */
export interface VitalsReading {
  patientId: string
  timeStamp: string
  heartRate: number | null
  spo2: number | null
  systolicBp: number | null
  diastolicBp: number | null
  respRate: number | null
  temperature: number | null
  consciousness: Consciousness | null
}

/** One line of a hospital's pre-alert (GET /api/facilities/{id}/inbound). */
export interface InboundPatient {
  tripId: string
  caseId: string
  patientId: string
  patientName: string | null
  age: number | null
  sex: Sex | null
  band: RiskBand
  needs: Capability[]
  unitId: string
  unitType: ResourceType
  latitude: number
  longitude: number
  distanceKm: number
  etaMinutes: number
  latestVitals: VitalsReading | null
}

/** GET /api/patients/{id}: the patient's record. */
export interface PatientInfo {
  id: string
  latitude: number
  longitude: number
  name: string | null
  age: number | null
  sex: Sex | null
  emergencyContact: Contact | null
}

/** GET /api/route: the way by road, points as [latitude, longitude]. */
export interface RouteInfo {
  seconds: number
  metres: number
  points: [number, number][]
}

/** GET /api/incidents: a jam or closure (speedFactor 0 = closed). */
export interface Incident {
  id: string
  centre: { latitude: number; longitude: number }
  radiusMetres: number
  speedFactor: number
}

/** The body of every error answer (RFC 9457), from the service's ApiErrors. */
export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
}
