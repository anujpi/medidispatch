import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { JourneyStrip } from './JourneyStrip'
import { journeySentence, journeyStep } from './journey'

describe('the journey step of a case', () => {
  it('starts at "feeling unwell" while the case is only watched or waiting for a unit', () => {
    expect(journeyStep('MONITORING')).toBe(0)
    expect(journeyStep('ALERTED')).toBe(0)
  })

  it('follows the primary trip while a unit is responding', () => {
    expect(journeyStep('RESPONDING', 'ASSIGNED')).toBe(1)
    expect(journeyStep('RESPONDING', 'EN_ROUTE')).toBe(1)
    expect(journeyStep('RESPONDING', 'ARRIVED')).toBe(2)
    expect(journeyStep('RESPONDING', null)).toBe(1)
  })

  it('is on the way to hospital while transporting, and safe once stabilised or closed', () => {
    expect(journeyStep('TRANSPORTING', 'TRANSPORTING')).toBe(3)
    expect(journeyStep('RESPONDING', 'TRANSPORTING')).toBe(3)
    expect(journeyStep('STABILISED')).toBe(4)
    expect(journeyStep('CLOSED', 'DONE')).toBe(4)
  })

  it('has no journey for a cancelled case', () => {
    expect(journeyStep('CANCELLED')).toBeNull()
  })
})

describe('the journey sentence', () => {
  it('names the person', () => {
    expect(journeySentence(1, 'Asha')).toBe('Help is on its way to Asha.')
    expect(journeySentence(3, 'Asha')).toBe('Asha is on the way to hospital. They know Asha is coming.')
  })

  it('says "the patient" without a name, capitalised at the start of a sentence', () => {
    expect(journeySentence(4)).toBe('The patient is safe and being cared for.')
    expect(journeySentence(0, '  ')).toBe('The wearable noticed that the patient is unwell.')
  })
})

describe('the journey strip', () => {
  it('lists the five moments and marks the current one', () => {
    render(<JourneyStrip step={2} name="Asha" />)

    const stops = within(screen.getByRole('list', { name: 'Where things stand' })).getAllByRole('listitem')
    expect(stops.map((s) => s.textContent)).toEqual(
      ['Feeling unwell', 'Help is coming', 'Crew is there', 'Off to hospital', 'Safe and cared for'])
    expect(stops[2]).toHaveAttribute('aria-current', 'step')
    expect(stops[1]).not.toHaveAttribute('aria-current')
    expect(screen.getByText('The crew is with Asha now.')).toBeInTheDocument()
  })

  it('leaves the sentence out when compact', () => {
    render(<JourneyStrip step={0} name="Asha" compact />)

    expect(screen.queryByText(/wearable noticed/)).not.toBeInTheDocument()
  })
})
