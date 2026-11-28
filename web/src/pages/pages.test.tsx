import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../routes'
import { FakeEventSource, fakeFetch, sampleSnapshot } from '../test/fakes'
import type { FacilityInfo, InboundPatient, PatientInfo, VitalsReading } from '../api/types'
import { routeQuery } from '../map/routes'
import { STORY_STEP_MS } from './HomePage'

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

const READING: VitalsReading = {
  patientId: 'p001', timeStamp: '2026-11-24T12:59:30Z', heartRate: 124, spo2: 92, systolicBp: 92, diastolicBp: 58,
  respRate: 26, temperature: 38.7, consciousness: 'CONFUSED',
}

const ASHA_RECORD: PatientInfo = {
  id: 'p001', latitude: 12.94, longitude: 77.61, name: 'Asha Rao', age: 68, sex: 'FEMALE',
  emergencyContact: { name: 'Rohan Rao', phone: '+91 98451 77302' },
}

describe('the start page', () => {
  const CLINIC: FacilityInfo = { ...JAYANAGAR, id: 'blr-c-indiranagar', name: 'Indiranagar Clinic', kind: 'CLINIC',
    capabilities: [], accepting: false }

  it('asks who you are, with one card for each kind of person', async () => {
    vi.stubGlobal('fetch', fakeFetch({ '/api/facilities': [200, [JAYANAGAR]] }))

    openAt('/')

    expect(screen.getByRole('heading', { level: 1, name: 'From the first warning sign to the hospital door.' }))
      .toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Who are you today?' })).toBeInTheDocument()
    expect(screen.getAllByRole('article').map((a) => a.getAttribute('aria-label'))).toEqual(
      ["I watch over the city", 'I work at a hospital', "I'm on an ambulance crew", "I'm a patient or family"])
    expect(within(screen.getByRole('article', { name: 'I watch over the city' }))
      .getByRole('link', { name: 'Open the control room' })).toHaveAttribute('href', '/control')
    expect(await screen.findByRole('combobox', { name: 'Your hospital' })).toBeInTheDocument()
  })

  it('plays the story of a call on a journey strip', () => {
    vi.useFakeTimers()
    try {
      vi.stubGlobal('fetch', fakeFetch({ '/api/facilities': [200, []] }))
      openAt('/')
      const story = within(screen.getByRole('figure', { name: 'How a call unfolds' }))
      expect(story.getByText('Help is on its way to Asha.')).toBeInTheDocument()

      act(() => vi.advanceTimersByTime(STORY_STEP_MS))

      expect(story.getByText('The crew is with Asha now.')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('offers only hospitals, not clinics, and opens the chosen one', async () => {
    const OTHER: FacilityInfo = { ...JAYANAGAR, id: 'blr-h-hebbal', name: 'Hebbal Hospital' }
    vi.stubGlobal('fetch', fakeFetch({
      '/api/facilities': [200, [JAYANAGAR, CLINIC, OTHER]],
      '/api/facilities/blr-h-hebbal': [200, OTHER],
      '/api/facilities/blr-h-hebbal/inbound': [200, []],
    }))
    openAt('/')

    const picker = await screen.findByRole('combobox', { name: 'Your hospital' })
    expect(within(picker).getAllByRole('option').map((o) => o.textContent))
      .toEqual(['Jayanagar Heart & Stroke Centre', 'Hebbal Hospital'])
    fireEvent.change(picker, { target: { value: 'blr-h-hebbal' } })
    fireEvent.click(screen.getByRole('button', { name: 'See who is coming' }))

    expect(await screen.findByRole('heading', { name: 'Hebbal Hospital' })).toBeInTheDocument()
  })

  it('says so kindly when no hospital has joined yet', async () => {
    vi.stubGlobal('fetch', fakeFetch({ '/api/facilities': [200, [CLINIC]] }))

    openAt('/')

    expect(await screen.findByText('No hospitals have joined yet.')).toBeInTheDocument()
  })

  it('opens a crew phone by the unit typed in', async () => {
    vi.stubGlobal('fetch', fakeFetch({ '/api/facilities': [200, []] }))
    openAt('/')

    fireEvent.change(screen.getByRole('textbox', { name: 'Your unit' }), { target: { value: ' als-99 ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Start my shift' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('No unit with the id "als-99"')
  })
})

describe('the hospital page', () => {
  it('shows the hospital and each arrival with its countdown, needs and vitals', async () => {
    vi.stubGlobal('fetch', fakeFetch({
      '/api/facilities/blr-h-jayanagar': [200, JAYANAGAR],
      '/api/facilities/blr-h-jayanagar/inbound': [200, [{ ...ASHA, latestVitals: READING }]],
    }))

    openAt('/hospital/blr-h-jayanagar')

    expect(await screen.findByRole('heading', { name: 'Jayanagar Heart & Stroke Centre' })).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Accepting new patients' })).toHaveAttribute('aria-checked', 'true')
    const card = within(screen.getByRole('article', { name: 'Asha Rao, arriving in 7 minutes' }))
    expect(card.getByText('68 F')).toBeInTheDocument()
    expect(card.getByText('Critical')).toBeInTheDocument()
    expect(card.getByText('Needs stroke care')).toBeInTheDocument()
    expect(card.getByText('als-1, ALS ambulance, 3.2 km away')).toBeInTheDocument()
    expect(card.getByText('124')).toBeInTheDocument()
  })

  it('puts the hospital on diversion with the switch', async () => {
    const fetchMock = fakeFetch({
      '/api/facilities/blr-h-jayanagar': [200, JAYANAGAR],
      '/api/facilities/blr-h-jayanagar/inbound': [200, []],
      '/api/facilities/blr-h-jayanagar/accepting': [200, { ...JAYANAGAR, accepting: false }],
    })
    vi.stubGlobal('fetch', fetchMock)
    openAt('/hospital/blr-h-jayanagar')
    expect(await screen.findByText('No patients on the way')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('switch', { name: 'Accepting new patients' }))

    expect(await screen.findByText('On diversion')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Accepting new patients' })).toHaveAttribute('aria-checked', 'false')
    const put = (fetchMock.mock.calls as unknown as [string, RequestInit][]).find((c) => c[1]?.method === 'PUT')
    expect(put?.[0]).toBe('/api/facilities/blr-h-jayanagar/accepting')
    expect(JSON.parse(String(put?.[1].body))).toEqual({ accepting: false })
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
  const snapshot = sampleSnapshot()
  const callFor = (trip: unknown) => fakeFetch({
    '/api/units/als-1': [200, snapshot.units[0]],
    '/api/units/als-1/trip': trip === undefined ? [204, undefined] : [200, trip],
    '/api/cases/c-critical': [200, snapshot.openCases[1]],
    '/api/patients/p001/vitals?hours=1': [200, [READING]],
  })

  it('shows where to go, the patient and their vitals, and sends the next step', async () => {
    const fetchMock = callFor(snapshot.activeTrips[0])               // EN_ROUTE to Asha Rao
    vi.stubGlobal('fetch', fetchMock)

    openAt('/crew/als-1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Asha Rao' })).toBeInTheDocument()
    expect(screen.getByText('Go to')).toBeInTheDocument()
    expect(screen.getByText('124')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Arrived at the patient' }))

    await waitFor(() => {
      const posts = (fetchMock.mock.calls as unknown as [string, RequestInit][]).filter((c) => c[1]?.method === 'POST')
      expect(posts.map((c) => c[0])).toEqual(['/api/trips/t-1/arrive'])
    })
  })

  it('asks what the patient needs before taking them to hospital', async () => {
    const fetchMock = callFor({ ...snapshot.activeTrips[0], status: 'ARRIVED' })
    vi.stubGlobal('fetch', fetchMock)
    openAt('/crew/als-1')

    fireEvent.click(await screen.findByRole('checkbox', { name: 'Stroke' }))
    fireEvent.click(screen.getByRole('button', { name: 'Take to hospital' }))

    await waitFor(() => {
      const post = (fetchMock.mock.calls as unknown as [string, RequestInit][]).find((c) => c[1]?.method === 'POST')
      expect(post?.[0]).toBe('/api/trips/t-1/transport')
      expect(JSON.parse(String(post?.[1].body))).toEqual({ needs: ['STROKE'] })
    })
    expect(screen.getByRole('button', { name: 'Treated here, finish the call' })).toBeInTheDocument()
  })

  it('says when there is no call', async () => {
    vi.stubGlobal('fetch', callFor(undefined))

    openAt('/crew/als-1')

    expect(await screen.findByText('No call')).toBeInTheDocument()
  })

  it('says when there is no such unit', async () => {
    vi.stubGlobal('fetch', fakeFetch({}))

    openAt('/crew/als-99')

    expect(await screen.findByRole('alert')).toHaveTextContent('No unit with the id "als-99"')
  })
})

describe('the patient page', () => {
  beforeEach(() => FakeEventSource.install())

  it('a patient without an open case is fine, not an error', async () => {
    vi.stubGlobal('fetch', fakeFetch({
      '/api/patients/p001': [200, ASHA_RECORD],
      '/api/patients/p001/vitals?hours=1': [200, [READING]],
    }))

    openAt('/patient/p001')

    expect(await screen.findByText('Your readings look normal')).toBeInTheDocument()
    expect(screen.getByText('Hello, Asha')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('tells the patient an ambulance is coming', async () => {
    vi.stubGlobal('fetch', fakeFetch({
      '/api/patients/p001': [200, ASHA_RECORD],
      '/api/patients/p001/case': [200, sampleSnapshot().openCases[1]],
      '/api/patients/p001/vitals?hours=1': [200, [READING]],
    }))
    openAt('/patient/p001')
    act(() => FakeEventSource.latest().emit('snapshot', JSON.stringify(sampleSnapshot())))

    expect(await screen.findByRole('heading', { name: 'An ambulance is coming to you' })).toBeInTheDocument()
    expect(screen.getByText('als-1, driven by Ravi Kumar')).toBeInTheDocument()
    expect(screen.getByText('While you wait')).toBeInTheDocument()
  })
})

describe('the patient file', () => {
  beforeEach(() => FakeEventSource.install())

  it("shows who the patient is, the open case, the vitals with what is outside normal, and the case's events", async () => {
    vi.stubGlobal('fetch', fakeFetch({
      '/api/patients/p001': [200, ASHA_RECORD],
      '/api/patients/p001/vitals?hours=6': [200, [{ ...READING, heartRate: 90 }, READING]],
    }))

    openAt('/control/patients/p001')
    act(() => FakeEventSource.latest().emit('snapshot', JSON.stringify(sampleSnapshot())))

    expect(await screen.findByRole('heading', { level: 1, name: 'Asha Rao' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Rohan Rao, \+91 98451 77302/ })).toHaveAttribute('href', 'tel:+919845177302')
    expect(screen.getByRole('region', { name: 'Open case' })).toHaveTextContent('Open since')
    const vitals = section('Vitals now')
    expect(vitals.getByText('124')).toHaveClass(/off/)
    expect(vitals.getByText('Outside normal. Normal 51–90.')).toBeInTheDocument()
    expect(section('What has happened in this case').getByText('als-1 sent')).toBeInTheDocument()
    expect(section('Medical record').getByText('Not recorded yet')).toBeInTheDocument()
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
