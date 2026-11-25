import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../routes'
import { FakeEventSource, fakeFetch, sampleSnapshot } from '../test/fakes'
import type { FacilityInfo, InboundPatient } from '../api/types'
import { routeQuery } from '../map/routes'

// renders the whole app at one address, the way the browser would open it
function openAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

// the panel with this heading
function section(heading: string) {
  return within(screen.getByRole('region', { name: heading }))
}

// a panel's table rows, without the header row
function bodyRows(heading: string) {
  return section(heading).getAllByRole('row').slice(1)
}

const JAYANAGAR: FacilityInfo = {
  id: 'blr-h-jayanagar', name: 'Jayanagar Heart & Stroke Centre', kind: 'HOSPITAL', latitude: 12.925,
  longitude: 77.5938, capabilities: ['STROKE', 'CARDIAC'], accepting: true, phone: '+91 80 4100 1002',
}

const ASHA: InboundPatient = {
  tripId: 't-1', caseId: 'c-1', patientId: 'p001', patientName: 'Asha Rao', age: 68, sex: 'FEMALE',
  band: 'CRITICAL', needs: ['STROKE'], unitId: 'als-1', unitType: 'AMBULANCE_ALS', latitude: 12.93,
  longitude: 77.6, distanceKm: 3.2, etaMinutes: 7, latestVitals: null,
}

describe('the hospital page', () => {
  it('shows the hospital and its arrivals board', async () => {
    vi.stubGlobal('fetch', fakeFetch({
      '/api/facilities/blr-h-jayanagar': [200, JAYANAGAR],
      '/api/facilities/blr-h-jayanagar/inbound': [200, [ASHA]],
    }))

    openAt('/hospital/blr-h-jayanagar')

    expect(await screen.findByRole('heading', { name: 'Jayanagar Heart & Stroke Centre' })).toBeInTheDocument()
    expect(screen.getByText('Accepting patients')).toBeInTheDocument()
    const [row] = bodyRows('Arrivals')
    expect(row).toHaveTextContent('Asha Rao')
    expect(row).toHaveTextContent('68 F')
    expect(within(row).getByTitle('Estimated arrival')).toHaveTextContent('7min')
    expect(row).toHaveTextContent('Critical')
    expect(row).toHaveTextContent('Stroke')
  })

  it('says so for a hospital that does not exist', async () => {
    vi.stubGlobal('fetch', fakeFetch({}))

    openAt('/hospital/nowhere')

    expect(await screen.findByRole('alert')).toHaveTextContent('No hospital with the id "nowhere"')
  })

  it('says the service is unreachable when it is down', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))

    openAt('/hospital/blr-h-jayanagar')

    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the dispatch service')
  })
})

describe('the control room', () => {
  beforeEach(() => FakeEventSource.install())

  it('lists the most urgent case first, with how long it has waited and its unit', () => {
    openAt('/control')
    expect(screen.getByText('Connecting to the live feed')).toBeInTheDocument()

    act(() => FakeEventSource.latest().emit('snapshot', JSON.stringify(sampleSnapshot())))

    expect(screen.getByRole('status')).toHaveTextContent('Live')
    const rows = bodyRows('Open cases')
    expect(rows[0]).toHaveTextContent('Asha Rao')            // Critical, opened later
    expect(rows[0]).toHaveTextContent('als-1')
    expect(rows[0]).toHaveTextContent('5min')                // opened 12:55, picture taken 13:00
    expect(rows[1]).toHaveTextContent('Meena Iyer')          // Medium, opened earlier
    expect(rows[1]).toHaveTextContent('Awaiting unit')
  })

  it('sums up the city and lists the fleet, busy units first', () => {
    openAt('/control')
    act(() => FakeEventSource.latest().emit('snapshot', JSON.stringify(sampleSnapshot())))

    const summary = screen.getByLabelText('City summary')
    expect(summary).toHaveTextContent('Units free1of 2')
    expect(summary).toHaveTextContent('Critical1')
    expect(summary).toHaveTextContent('Awaiting a unit1')
    const fleet = bodyRows('Fleet')
    expect(fleet[0]).toHaveTextContent('als-1')
    expect(fleet[0]).toHaveTextContent('On a trip')
    expect(fleet[1]).toHaveTextContent('Free')
  })

  it('shows the city on a map: a dot per case, a square per unit, a cross per hospital', () => {
    const { container } = openAt('/control')
    act(() => FakeEventSource.latest().emit('snapshot', JSON.stringify(sampleSnapshot())))

    const snapshot = sampleSnapshot()
    expect(screen.getByRole('region', { name: 'City map' })).toBeInTheDocument()
    expect(container.querySelectorAll('.leaflet-marker-icon')).toHaveLength(
      snapshot.openCases.length + snapshot.units.length + snapshot.facilities.length)
  })

  it('picks a case from the list', () => {
    openAt('/control')
    act(() => FakeEventSource.latest().emit('snapshot', JSON.stringify(sampleSnapshot())))

    const asha = screen.getByRole('button', { name: 'Asha Rao' })
    expect(asha).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(asha)

    expect(asha).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Meena Iyer' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('a picked case opens as a card: the patient, the unit, its drive time and its driver', async () => {
    const eta = '/api/route?' + routeQuery([12.93, 77.62], [12.94, 77.61])     // als-1 to Asha Rao
    vi.stubGlobal('fetch', fakeFetch({ [eta]: [200, { seconds: 400, metres: 3000, points: [] }] }))
    openAt('/control')
    act(() => FakeEventSource.latest().emit('snapshot', JSON.stringify(sampleSnapshot())))

    fireEvent.click(screen.getByRole('button', { name: 'Asha Rao' }))

    const card = within(screen.getByRole('complementary', { name: 'Asha Rao' }))
    expect(card.getByText('68, female')).toBeInTheDocument()
    expect(card.getByText('Unit on the way')).toBeInTheDocument()
    expect(card.getByText('als-1')).toBeInTheDocument()
    expect(card.getByText('En route')).toBeInTheDocument()
    expect(card.getByRole('link', { name: /Ravi Kumar/ })).toHaveAttribute('href', 'tel:+91 90000 00001')
    expect(await card.findByTitle('Estimated drive time')).toHaveTextContent('7min')     // 400 s, rounded up

    fireEvent.click(card.getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  })

  it('raises an alert when a unit goes offline, until it is acknowledged', () => {
    openAt('/control')
    act(() => FakeEventSource.latest().emit('snapshot', JSON.stringify(sampleSnapshot())))
    expect(section('Alerts').getByText('No alerts. New ones appear here.')).toBeInTheDocument()

    const offline = sampleSnapshot()
    offline.at = '2026-11-24T13:00:01Z'
    offline.units[1].status = 'OFFLINE'
    act(() => FakeEventSource.latest().emit('snapshot', JSON.stringify(offline)))

    expect(section('Alerts').getByText('bls-1 went offline')).toBeInTheDocument()
    fireEvent.click(section('Alerts').getByRole('button', { name: 'Acknowledge: bls-1 went offline' }))
    expect(section('Alerts').getByText('No alerts. New ones appear here.')).toBeInTheDocument()
  })

  it('an alert about a case opens that case', () => {
    openAt('/control')
    act(() => FakeEventSource.latest().emit('snapshot', JSON.stringify(sampleSnapshot())))
    const worse = sampleSnapshot()
    worse.at = '2026-11-24T13:00:01Z'
    worse.openCases[0].band = 'CRITICAL'
    act(() => FakeEventSource.latest().emit('snapshot', JSON.stringify(worse)))

    fireEvent.click(section('Alerts').getByRole('button', { name: 'Meena Iyer is critical' }))

    expect(screen.getByRole('complementary', { name: 'Meena Iyer' })).toBeInTheDocument()
  })
})

describe('the crew page', () => {
  beforeEach(() => FakeEventSource.install())

  it('shows the unit and its trip', () => {
    openAt('/crew/als-1')
    act(() => FakeEventSource.latest().emit('snapshot', JSON.stringify(sampleSnapshot())))

    expect(screen.getByRole('heading', { name: 'als-1' })).toBeInTheDocument()
    expect(screen.getByText('Go to the patient')).toBeInTheDocument()
    expect(screen.getByText('Asha Rao')).toBeInTheDocument()
  })

  it('says when there is no such unit', () => {
    openAt('/crew/als-99')
    act(() => FakeEventSource.latest().emit('snapshot', JSON.stringify(sampleSnapshot())))

    expect(screen.getByText('No unit with the id "als-99"')).toBeInTheDocument()
  })
})

describe('the patient page', () => {
  it('a patient without an open case is fine, not an error', async () => {
    vi.stubGlobal('fetch', fakeFetch({
      '/api/patients/p001': [200, { id: 'p001', latitude: 12.9, longitude: 77.6, name: 'Asha Rao', age: 68,
        sex: 'FEMALE', emergencyContact: null }],
    }))

    openAt('/patient/p001')

    expect(await screen.findByText('No alerts')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('addresses', () => {
  it('an unknown address shows "page not found"', () => {
    openAt('/nowhere/at/all')

    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})

describe('the colour theme', () => {
  it('switches when a swatch is clicked, and is remembered', () => {
    openAt('/nowhere')

    fireEvent.click(screen.getByRole('button', { name: 'Navy, white and hot pink' }))

    expect(document.documentElement.dataset.theme).toBe('navy')
    expect(localStorage.getItem('medidispatch.theme')).toBe('navy')
    expect(screen.getByRole('button', { name: 'Navy, white and hot pink' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Cream, ink and burnt orange' })).toHaveAttribute('aria-pressed', 'false')
  })
})
