import * as L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { useEffect, useRef } from 'react'
import type { LiveSnapshot } from '../api/types'
import { BAND, UNIT_TYPE } from '../labels'
import css from './map.module.css'
import { ahead, askRoute, type LatLon, type Leg } from './routes'

/**
 * Esri's grey canvas maps: no labels and no colour, so the markers carry the meaning (ISA-101: a neutral base).
 * Light for the cream theme, dark for navy and charcoal. They stop at zoom 16; closer in their tiles are enlarged.
 */
const ESRI = 'https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/'
const LIGHT = ESRI + 'World_Light_Gray_Base/MapServer/tile/{z}/{y}/{x}'
const DARK = ESRI + 'World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}'
const CREDIT = 'Tiles &copy; Esri &mdash; Esri, HERE, Garmin, &copy; OpenStreetMap contributors'

const BENGALURU: LatLon = [12.97, 77.59]
const CROSS = '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M5.5 1h5v4.5H15v5h-4.5V15h-5v-4.5H1v-5h4.5z"/></svg>'

function tileUrl(): string {
  const theme = document.documentElement.dataset.theme
  return theme === 'navy' || theme === 'charcoal' ? DARK : LIGHT
}

/** One thing to show as a marker: where, how it looks (CSS classes), its size, and its hover text. */
interface Spot {
  id: string
  at: LatLon
  look: string
  size: number
  tip: string
  html?: string
  z: number
}

interface Shown {
  marker: L.Marker
  look: string
}

/** Everything on the map, kept between snapshots so markers move instead of being redrawn. */
interface Drawn {
  map: L.Map
  facilities: Map<string, Shown>
  cases: Map<string, Shown>
  units: Map<string, Shown>
  lines: Map<string, L.Polyline>
  legs: Map<string, Leg>
  fitted: boolean
  panned: string | null
}

/**
 * The live city on a map: hospitals and clinics as crosses (faded on diversion), each open case as a dot in its
 * urgency's colour (pulsing while it waits for a unit), units away from base as small squares (filled while on a
 * trip; a free unit parked at its base is counted in the base's hover text instead), and each
 * trip's road still ahead. Clicking a case selects it; selecting one elsewhere brings it into view.
 */
export function LiveMap({ snapshot, selectedCaseId, onSelectCase }: {
  snapshot: LiveSnapshot
  selectedCaseId: string | null
  onSelectCase: (caseId: string) => void
}) {
  const box = useRef<HTMLDivElement>(null)
  const drawn = useRef<Drawn | null>(null)
  const select = useRef(onSelectCase)

  useEffect(() => {
    select.current = onSelectCase
  })

  // the map itself: made once, its base map following the theme
  useEffect(() => {
    const map = L.map(box.current!, { zoomSnap: 0.5 }).setView(BENGALURU, 12)
    const tiles = L.tileLayer(tileUrl(), { maxZoom: 19, maxNativeZoom: 16, attribution: CREDIT }).addTo(map)
    const themeWatch = new MutationObserver(() => {
      tiles.setUrl(tileUrl())
    })
    themeWatch.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
    drawn.current = {
      map, facilities: new Map(), cases: new Map(), units: new Map(), lines: new Map(), legs: new Map(),
      fitted: false, panned: null,
    }
    return () => {
      themeWatch.disconnect()
      map.remove()
      drawn.current = null
    }
  }, [])

  // every snapshot: move, add and remove what changed
  useEffect(() => {
    if (drawn.current) {
      draw(drawn.current, snapshot, selectedCaseId, (id) => select.current(id))
    }
  }, [snapshot, selectedCaseId])

  return <div ref={box} className={css.map} />
}

function draw(d: Drawn, s: LiveSnapshot, selectedCaseId: string | null, onSelect: (id: string) => void) {
  // free units parked at their own base are not drawn (they would sit on its cross); the base says how many
  const facilityById = new Map(s.facilities.map((f) => [f.id, f]))
  const parked = new Map<string, number>()
  const parkedUnits = new Set<string>()
  for (const u of s.units) {
    const base = facilityById.get(u.baseId ?? '')
    if (u.status === 'AVAILABLE' && base && near(u.latitude, u.longitude, base.latitude, base.longitude)) {
      parkedUnits.add(u.id)
      parked.set(base.id, (parked.get(base.id) ?? 0) + 1)
    }
  }

  const facilitySpots: Spot[] = []
  for (const f of s.facilities) {
    const free = parked.get(f.id) ?? 0
    const hospital = f.kind === 'HOSPITAL'
    facilitySpots.push({
      id: f.id, at: [f.latitude, f.longitude], size: hospital ? 18 : 12, html: CROSS, z: 0,
      look: `${css.facility} ${f.accepting ? '' : css.diverted}`,
      tip: f.name + (f.accepting ? '' : ' (on diversion)')
        + (free === 0 ? '' : free === 1 ? ', 1 unit free here' : `, ${free} units free here`),
    })
  }
  place(d.map, d.facilities, facilitySpots)

  const caseSpots: Spot[] = []
  for (const c of s.openCases) {
    const selected = c.id === selectedCaseId
    caseSpots.push({
      id: c.id, at: [c.latitude, c.longitude], size: selected ? 20 : 14,
      z: (selected ? 2000 : 1000) + (c.band === 'CRITICAL' ? 100 : 0),
      look: `${css.case} ${css[c.band]} ${c.status === 'ALERTED' ? css.waiting : ''} ${selected ? css.selected : ''}`,
      tip: `${c.patientName ?? c.patientId}, ${BAND[c.band]}`,
    })
  }
  place(d.map, d.cases, caseSpots, onSelect)

  const unitSpots: Spot[] = []
  for (const u of s.units) {
    if (parkedUnits.has(u.id)) {
      continue
    }
    const state = u.status === 'ASSIGNED' ? css.busy : u.status === 'OFFLINE' ? css.offline : ''
    unitSpots.push({
      id: u.id, at: [u.latitude, u.longitude], size: 10, z: 500, look: `${css.unit} ${state}`,
      tip: `${u.id}, ${UNIT_TYPE[u.type]}`,
    })
  }
  place(d.map, d.units, unitSpots)

  drawTrips(d, s)

  // zoom once to everything there is: hospitals, units and patients
  if (!d.fitted && s.facilities.length > 0) {
    const everything: LatLon[] = []
    for (const one of [...s.facilities, ...s.units, ...s.openCases]) {
      everything.push([one.latitude, one.longitude])
    }
    const bounds = L.latLngBounds(everything)
    d.map.invalidateSize()
    d.map.fitBounds(bounds, { padding: [32, 32], maxZoom: 13 })
    d.fitted = true
  }

  // a case selected (in the list or here) is brought into view, once
  if (selectedCaseId !== d.panned) {
    d.panned = selectedCaseId
    const c = s.openCases.find((one) => one.id === selectedCaseId)
    if (c && !d.map.getBounds().contains([c.latitude, c.longitude])) {
      d.map.panTo([c.latitude, c.longitude])
    }
  }
}

// one line per trip leg: the road still ahead of the unit, to the patient or (while transporting) to the hospital
function drawTrips(d: Drawn, s: LiveSnapshot) {
  const units = new Map(s.units.map((u) => [u.id, u]))
  const cases = new Map(s.openCases.map((c) => [c.id, c]))
  const facilities = new Map(s.facilities.map((f) => [f.id, f]))
  const seen = new Set<string>()

  for (const t of s.activeTrips) {
    const toHospital = t.status === 'TRANSPORTING'
    const unit = units.get(t.unitId)
    const target = toHospital ? facilities.get(t.hospitalId ?? '') : cases.get(t.caseId)
    if (!unit || !target) {
      continue
    }
    const key = t.id + (toHospital ? '|hospital' : '|patient')
    seen.add(key)
    const from: LatLon = [unit.latitude, unit.longitude]
    const to: LatLon = [target.latitude, target.longitude]
    if (!d.legs.has(key)) {
      d.legs.set(key, askRoute(from, to))
    }
    const points = ahead(d.legs.get(key), from, to)
    const line = d.lines.get(key)
    if (line) {
      line.setLatLngs(points)
    } else {
      const look = `${css.trip} ${toHospital ? css.toHospital : ''} ${t.role === 'SUPPORT' ? css.support : ''}`
      d.lines.set(key, L.polyline(points, { className: look, interactive: false }).addTo(d.map))
    }
  }

  for (const [key, line] of d.lines) {
    if (!seen.has(key)) {          // the leg is over
      line.remove()
      d.lines.delete(key)
      d.legs.delete(key)
    }
  }
}

// adds, moves, restyles and removes markers so the map shows exactly these spots
function place(map: L.Map, shown: Map<string, Shown>, spots: Spot[], onClick?: (id: string) => void) {
  const seen = new Set<string>()
  for (const spot of spots) {
    seen.add(spot.id)
    const existing = shown.get(spot.id)
    if (!existing) {
      const marker = L.marker(spot.at, { icon: icon(spot), keyboard: false, zIndexOffset: spot.z }).addTo(map)
      marker.bindTooltip(text(spot.tip), { direction: 'top', offset: [0, -spot.size / 2] })
      if (onClick) {
        marker.on('click', () => onClick(spot.id))
      }
      shown.set(spot.id, { marker, look: spot.look })
      continue
    }
    existing.marker.setLatLng(spot.at)
    existing.marker.setTooltipContent(text(spot.tip))
    if (existing.look !== spot.look) {        // only rebuild the icon when it changes, so hovering is not lost
      existing.marker.setIcon(icon(spot))
      existing.marker.setZIndexOffset(spot.z)
      existing.look = spot.look
    }
  }
  for (const [id, one] of shown) {
    if (!seen.has(id)) {
      one.marker.remove()
      shown.delete(id)
    }
  }
}

// within about 100 m: a unit this close to its base is parked there
function near(lat1: number, lon1: number, lat2: number, lon2: number): boolean {
  return Math.abs(lat1 - lat2) < 0.001 && Math.abs(lon1 - lon2) < 0.001
}

function icon(spot: Spot): L.DivIcon {
  return L.divIcon({ className: spot.look, iconSize: [spot.size, spot.size], html: spot.html ?? '' })
}

// names come from the API: shown as text, never read as HTML (Leaflet would insert a string as markup)
function text(value: string): HTMLElement {
  const span = document.createElement('span')
  span.textContent = value
  return span
}
