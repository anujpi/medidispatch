import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../routes'
import { FakeEventSource, fakeFetch, sampleSnapshot } from '../test/fakes'

// the control room with the sample city, Asha Rao's case open as a card; `answers` are what the service replies
function openAshasCard(answers: Record<string, [number, unknown]>) {
  const fetchMock = fakeFetch(answers)
  vi.stubGlobal('fetch', fetchMock)
  render(
    <MemoryRouter initialEntries={['/control']}>
      <AppRoutes />
    </MemoryRouter>,
  )
  act(() => FakeEventSource.latest().emit('snapshot', JSON.stringify(sampleSnapshot())))
  fireEvent.click(screen.getByRole('button', { name: 'Asha Rao' }))
  return { card: within(screen.getByRole('complementary', { name: 'Asha Rao' })), fetchMock }
}

// the POSTs the page made: [path, parsed body]
function posts(fetchMock: ReturnType<typeof fakeFetch>) {
  const sent: [string, unknown][] = []
  // fetch is called with (path, options); the fake only declares the path, so the options are read through unknown
  for (const [input, init] of fetchMock.mock.calls as unknown as [RequestInfo | URL, RequestInit | undefined][]) {
    if (init?.method === 'POST') {
      sent.push([String(input), JSON.parse(String(init.body))])
    }
  }
  return sent
}

describe("the dispatcher's overrides in the case card", () => {
  beforeEach(() => FakeEventSource.install())

  it('sends the unit the dispatcher chose, with the reason', async () => {
    const { card, fetchMock } = openAshasCard({ '/api/cases/c-critical/send': [200, {}] })

    fireEvent.click(card.getByRole('button', { name: 'Send another unit' }))
    const choice = card.getByRole('combobox', { name: 'Unit to send' })
    expect(within(choice).getByRole('option', { name: /bls-1, BLS ambulance, \d+\.\d km/ })).toBeInTheDocument()
    fireEvent.change(choice, { target: { value: 'bls-1' } })
    fireEvent.change(card.getByRole('textbox', { name: 'Reason' }), { target: { value: 'closer by road' } })
    fireEvent.click(card.getByRole('button', { name: 'Send unit' }))

    expect(await card.findByRole('button', { name: 'Send another unit' })).toBeInTheDocument()   // form closed
    expect(posts(fetchMock)).toEqual([['/api/cases/c-critical/send', { unitId: 'bls-1', reason: 'closer by road' }]])
  })

  it("shows the service's reason when it refuses, and keeps the form open", async () => {
    const { card } = openAshasCard({
      '/api/cases/c-critical/cancel': [409, { status: 409, detail: 'Case c-critical is TRANSPORTING and cannot be cancelled' }],
    })

    fireEvent.click(card.getByRole('button', { name: 'Cancel case' }))
    expect(card.getByText(/If the readings stay bad, a new case opens/)).toBeInTheDocument()
    fireEvent.change(card.getByRole('textbox', { name: 'Reason' }), { target: { value: 'false alarm' } })
    fireEvent.click(card.getByRole('button', { name: 'Cancel case' }))

    expect(await card.findByRole('alert')).toHaveTextContent('Case c-critical is TRANSPORTING and cannot be cancelled')
    expect(card.getByRole('textbox', { name: 'Reason' })).toHaveValue('false alarm')
  })

  it('takes a unit on the case out of service', async () => {
    const { card, fetchMock } = openAshasCard({ '/api/units/als-1/out-of-service': [200, {}] })

    fireEvent.click(card.getByRole('button', { name: 'Out of service' }))
    fireEvent.change(card.getByRole('textbox', { name: 'Why als-1 is out of service' }), { target: { value: 'flat tyre' } })
    fireEvent.click(card.getByRole('button', { name: 'Take out' }))

    expect(await card.findByRole('button', { name: 'Out of service' })).toBeInTheDocument()
    expect(posts(fetchMock)).toEqual([['/api/units/als-1/out-of-service', { reason: 'flat tyre' }]])
  })

  it('a reason is required before anything is sent', () => {
    const { card, fetchMock } = openAshasCard({})

    fireEvent.click(card.getByRole('button', { name: 'Cancel case' }))
    fireEvent.click(card.getByRole('button', { name: 'Cancel case' }))

    expect(posts(fetchMock)).toEqual([])
  })
})
