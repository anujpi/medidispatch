import type { CSSProperties } from 'react'

/*
  Small hand-drawn pictures that make the screens feel human: five step icons for the journey strip and four little
  scenes for the people on the start page. Plain inline SVG (no image files, no library), coloured with the theme's
  own variables so they follow the light and dark themes. All are decorative (aria-hidden): the words next to them
  say the same thing.
*/

const line: CSSProperties = { fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round' }

function fill(color: string): CSSProperties {
  return { fill: color }
}

function stroke(color: string, width = 2): CSSProperties {
  return { fill: 'none', stroke: color, strokeWidth: width, strokeLinecap: 'round', strokeLinejoin: 'round' }
}

/** Step 1: a heart with a jumpy pulse line, the wearable noticing something is wrong. */
export function HeartIcon({ size = 22 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path style={line} d="M12 20s-7-4.4-8.6-9.1C2.3 7.6 4.4 4.5 7.6 4.5c2 0 3.4 1.2 4.4 2.6 1-1.4 2.4-2.6 4.4-2.6 3.2 0 5.3 3.1 4.2 6.4C19 15.6 12 20 12 20z" />
      <path style={line} d="M6.5 11.5h3l1.2-2.2 1.8 4.2 1.3-2h3.7" />
    </svg>
  )
}

/** Step 2: help is coming, a small siren light with rays. */
export function SirenIcon({ size = 22 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path style={line} d="M7 18v-5a5 5 0 0 1 10 0v5" />
      <path style={line} d="M5 18h14v2.5H5z" />
      <path style={line} d="M12 3v2M4.5 6.5 6 8M19.5 6.5 18 8M2.5 13h2M19.5 13h2" />
    </svg>
  )
}

/** Step 3: the crew is there, two people side by side. */
export function CrewIcon({ size = 22 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <circle style={line} cx="8.5" cy="8" r="3" />
      <circle style={line} cx="16" cy="9" r="2.5" />
      <path style={line} d="M3 19.5c.6-3.3 2.8-5.2 5.5-5.2s4.9 1.9 5.5 5.2" />
      <path style={line} d="M14.5 14.4c.5-.1 1-.2 1.5-.2 2.3 0 4.2 1.6 4.8 4.4" />
    </svg>
  )
}

/** Step 4: on the way to hospital, an ambulance. */
export function AmbulanceIcon({ size = 22 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path style={line} d="M2.5 16.5V8.5a1.5 1.5 0 0 1 1.5-1.5h9.5v9.5" />
      <path style={line} d="M13.5 10h3.8l3.2 3.5v3h-1.5" />
      <path style={line} d="M2.5 16.5h2M9.5 16.5h5" />
      <circle style={line} cx="7" cy="17" r="1.8" />
      <circle style={line} cx="17" cy="17" r="1.8" />
      <path style={line} d="M8 9.5v4M6 11.5h4" />
    </svg>
  )
}

/** Step 5: safe and cared for, a hospital with a heart over the door. */
export function SafeIcon({ size = 22 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path style={line} d="M4 20.5V9l8-5 8 5v11.5" />
      <path style={line} d="M2.5 20.5h19" />
      <path style={line} d="M10 20.5v-4h4v4" />
      <path style={line} d="M12 13s-2.6-1.6-2.6-3.3c0-.9.7-1.6 1.5-1.6.5 0 .9.3 1.1.6.2-.3.6-.6 1.1-.6.8 0 1.5.7 1.5 1.6C14.6 11.4 12 13 12 13z" />
    </svg>
  )
}

/** The dispatcher's scene: a soft city map with pins, a route and a watching eye of a radar ring. */
export function CityScene() {
  return (
    <svg viewBox="0 0 160 100" width="100%" height="100%" aria-hidden="true" focusable="false">
      <rect x="0" y="0" width="160" height="100" rx="14" style={fill('var(--surface-2)')} />
      <path d="M118 14c14-3 26 5 27 15s-9 16-20 14-17-8-16-16 2-11 9-13z" style={fill('var(--busy-soft)')} />
      <path style={stroke('var(--surface)', 5)} d="M-4 64C40 56 80 66 120 58s30-8 44-6" />
      <path style={stroke('var(--surface)', 5)} d="M64 -4c-4 30 6 60 2 108" />
      <path style={stroke('var(--surface)', 3)} d="M14 22c20 10 34 14 50 12M90 96c4-16 14-26 32-30" />
      <path d="M36 74 L52 66 L66 60 L84 50 L104 42" style={{ ...stroke('var(--busy)', 3), strokeDasharray: '1 6' }} />
      <rect x="30" y="68" width="11" height="11" rx="3" style={{ fill: 'var(--busy)', stroke: 'var(--surface)', strokeWidth: 2 }} />
      <circle cx="104" cy="42" r="14" style={{ fill: 'var(--critical)', opacity: 0.12 }} />
      <circle cx="104" cy="42" r="6.5" style={{ fill: 'var(--critical)', stroke: 'var(--surface)', strokeWidth: 2.5 }} />
      <circle cx="128" cy="78" r="5.5" style={{ fill: 'var(--high)', stroke: 'var(--surface)', strokeWidth: 2 }} />
      <circle cx="22" cy="40" r="5.5" style={{ fill: 'var(--medium)', stroke: 'var(--surface)', strokeWidth: 2 }} />
      <path d="M88 76h5v5h5v5h-5v5h-5v-5h-5v-5h5z" style={{ fill: 'var(--text-2)', stroke: 'var(--surface)', strokeWidth: 1.5 }} />
    </svg>
  )
}

/** The hospital's scene: a friendly building with a lit doorway and a heart, an ambulance pulling up. */
export function HospitalScene() {
  return (
    <svg viewBox="0 0 160 100" width="100%" height="100%" aria-hidden="true" focusable="false">
      <rect x="0" y="0" width="160" height="100" rx="14" style={fill('var(--surface-2)')} />
      <circle cx="132" cy="22" r="9" style={fill('var(--medium-soft)')} />
      <rect x="38" y="28" width="64" height="56" rx="6" style={{ fill: 'var(--surface)', stroke: 'var(--line-strong)', strokeWidth: 2 }} />
      <rect x="58" y="16" width="24" height="16" rx="4" style={{ fill: 'var(--surface)', stroke: 'var(--line-strong)', strokeWidth: 2 }} />
      <path d="M68 19h4v4h4v4h-4v4h-4v-4h-4v-4h4z" style={fill('var(--critical)')} />
      <rect x="46" y="38" width="10" height="9" rx="2" style={fill('var(--busy-soft)')} />
      <rect x="84" y="38" width="10" height="9" rx="2" style={fill('var(--busy-soft)')} />
      <rect x="46" y="54" width="10" height="9" rx="2" style={fill('var(--busy-soft)')} />
      <rect x="84" y="54" width="10" height="9" rx="2" style={fill('var(--busy-soft)')} />
      <rect x="62" y="62" width="16" height="22" rx="3" style={fill('var(--medium-soft)')} />
      <path d="M70 50s-5-3-5-6.4c0-1.8 1.3-3 2.9-3 1 0 1.7.6 2.1 1.2.4-.6 1.1-1.2 2.1-1.2 1.6 0 2.9 1.2 2.9 3C75 47 70 50 70 50z"
            style={fill('var(--critical)')} />
      <path style={stroke('var(--line-strong)', 2)} d="M8 84h144" />
      <g transform="translate(108 64)">
        <rect x="0" y="4" width="22" height="14" rx="3" style={{ fill: 'var(--surface)', stroke: 'var(--text-2)', strokeWidth: 1.8 }} />
        <path d="M22 9h7l4 4.5V18h-11z" style={{ fill: 'var(--surface)', stroke: 'var(--text-2)', strokeWidth: 1.8, strokeLinejoin: 'round' }} />
        <path d="M9 8h3v3h3v3h-3v3H9v-3H6v-3h3z" style={fill('var(--critical)')} />
        <rect x="8" y="0" width="6" height="4" rx="1.5" style={fill('var(--busy)')} />
        <circle cx="7" cy="19" r="3" style={{ fill: 'var(--text-2)', stroke: 'var(--surface)', strokeWidth: 1.5 }} />
        <circle cx="26" cy="19" r="3" style={{ fill: 'var(--text-2)', stroke: 'var(--surface)', strokeWidth: 1.5 }} />
      </g>
    </svg>
  )
}

/** The crew's scene: an ambulance on a winding road, a phone showing the way, a pin at the end. */
export function CrewScene() {
  return (
    <svg viewBox="0 0 160 100" width="100%" height="100%" aria-hidden="true" focusable="false">
      <rect x="0" y="0" width="160" height="100" rx="14" style={fill('var(--surface-2)')} />
      <path style={stroke('var(--surface)', 12)} d="M-6 84C30 84 40 56 72 56s44 22 72-8" />
      <path style={{ ...stroke('var(--line-strong)', 1.5), strokeDasharray: '4 6' }} d="M-6 84C30 84 40 56 72 56s44 22 72-8" />
      <circle cx="140" cy="40" r="11" style={{ fill: 'var(--critical)', opacity: 0.14 }} />
      <path d="M140 46s-7-7-7-12a7 7 0 0 1 14 0c0 5-7 12-7 12z" style={{ fill: 'var(--critical)', stroke: 'var(--surface)', strokeWidth: 2 }} />
      <circle cx="140" cy="34" r="2.5" style={fill('var(--surface)')} />
      <g transform="translate(36 52)">
        <rect x="0" y="6" width="30" height="18" rx="4" style={{ fill: 'var(--surface)', stroke: 'var(--text-2)', strokeWidth: 2 }} />
        <path d="M30 12h9l5 6v6H30z" style={{ fill: 'var(--surface)', stroke: 'var(--text-2)', strokeWidth: 2, strokeLinejoin: 'round' }} />
        <path d="M13 10h4v4h4v4h-4v4h-4v-4H9v-4h4z" style={fill('var(--critical)')} />
        <rect x="11" y="1" width="8" height="5" rx="2" style={fill('var(--busy)')} />
        <path style={stroke('var(--busy)', 1.6)} d="M8 2.5 5 0M22 2.5 25 0" />
        <circle cx="9" cy="25" r="3.5" style={{ fill: 'var(--text-2)', stroke: 'var(--surface)', strokeWidth: 1.5 }} />
        <circle cx="36" cy="25" r="3.5" style={{ fill: 'var(--text-2)', stroke: 'var(--surface)', strokeWidth: 1.5 }} />
      </g>
      <g transform="translate(108 8)">
        <rect x="0" y="0" width="24" height="38" rx="5" style={{ fill: 'var(--text)' }} />
        <rect x="3" y="5" width="18" height="26" rx="2" style={fill('var(--surface)')} />
        <path d="M6 26 L10 18 L15 16 L18 9" style={stroke('var(--busy)', 2)} />
        <circle cx="18" cy="9" r="2" style={fill('var(--critical)')} />
      </g>
    </svg>
  )
}

/** The patient's and family's scene: a home with a warm window, a person waving, a heart above. */
export function HomeScene() {
  return (
    <svg viewBox="0 0 160 100" width="100%" height="100%" aria-hidden="true" focusable="false">
      <rect x="0" y="0" width="160" height="100" rx="14" style={fill('var(--surface-2)')} />
      <circle cx="28" cy="22" r="10" style={fill('var(--medium-soft)')} />
      <path d="M50 84V50l30-22 30 22v34z" style={{ fill: 'var(--surface)', stroke: 'var(--line-strong)', strokeWidth: 2, strokeLinejoin: 'round' }} />
      <path d="M44 54 80 27l36 27" style={stroke('var(--accent)', 4)} />
      <rect x="60" y="56" width="14" height="12" rx="2" style={fill('var(--medium-soft)')} />
      <rect x="86" y="60" width="13" height="24" rx="2" style={fill('var(--accent-soft)')} />
      <path d="M80 46s-5.5-3.4-5.5-7c0-1.9 1.4-3.2 3.1-3.2 1.1 0 1.9.6 2.4 1.3.5-.7 1.3-1.3 2.4-1.3 1.7 0 3.1 1.3 3.1 3.2 0 3.6-5.5 7-5.5 7z"
            style={fill('var(--critical)')} />
      <path style={stroke('var(--line-strong)', 2)} d="M8 84h144" />
      <g transform="translate(122 52)">
        <circle cx="8" cy="6" r="5" style={{ fill: 'var(--high-soft)', stroke: 'var(--text-2)', strokeWidth: 1.8 }} />
        <path d="M0 32c0-9 3.5-15 8-15s8 6 8 15" style={{ fill: 'var(--busy-soft)', stroke: 'var(--text-2)', strokeWidth: 1.8 }} />
        <path d="M14 20 21 10" style={stroke('var(--text-2)', 1.8)} />
      </g>
      <path style={stroke('var(--ok)', 2)} d="M134 34c3-3 7-3 10 0M131 30c5-5 11-5 16 0" />
    </svg>
  )
}
