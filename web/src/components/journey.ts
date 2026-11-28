import type { CaseStatus, TripStatus } from '../api/types'

/*
  The five moments every emergency goes through, told the way a patient's family would tell it. The engine has
  more states than this (ALERTED, EN_ROUTE, STABILISED...); journeyStep() folds them into these five so that every
  screen, and anyone looking at it, sees the same simple story.
*/

/** The five steps, in order; a step is its position in this list (0 to 4). */
export const JOURNEY = [
  { key: 'unwell', label: 'Feeling unwell' },
  { key: 'coming', label: 'Help is coming' },
  { key: 'with', label: 'Crew is there' },
  { key: 'hospital', label: 'Off to hospital' },
  { key: 'safe', label: 'Safe and cared for' },
] as const

export type JourneyStep = 0 | 1 | 2 | 3 | 4

/**
 * Which of the five moments a case is in.
 *
 * @param caseStatus the case's status
 * @param tripStatus the status of the case's PRIMARY trip, if it has one (a support responder does not move the story)
 * @returns the step, or null for a cancelled case (there is no journey to show)
 */
export function journeyStep(caseStatus: CaseStatus, tripStatus?: TripStatus | null): JourneyStep | null {
  switch (caseStatus) {
    case 'CANCELLED':
      return null
    case 'STABILISED':
    case 'CLOSED':
      return 4
    case 'TRANSPORTING':
      return 3
    case 'RESPONDING':
      // the trip says whether the crew is still driving or already at the door
      if (tripStatus === 'ARRIVED') {
        return 2
      }
      if (tripStatus === 'TRANSPORTING') {
        return 3
      }
      return 1
    case 'MONITORING':
    case 'ALERTED':
      return 0
  }
}

/**
 * One plain sentence for a step, for the line under the strip.
 *
 * @param step the step
 * @param name who it is about ("Asha"); without one the sentence says "the patient"
 */
export function journeySentence(step: JourneyStep, name?: string): string {
  const who = name && name.trim() !== '' ? name.trim() : 'the patient'
  const Who = who.charAt(0).toUpperCase() + who.slice(1)
  switch (step) {
    case 0:
      return `The wearable noticed that ${who} is unwell.`
    case 1:
      return `Help is on its way to ${who}.`
    case 2:
      return `The crew is with ${who} now.`
    case 3:
      return `${Who} is on the way to hospital. They know ${who} is coming.`
    case 4:
      return `${Who} is safe and being cared for.`
  }
}
