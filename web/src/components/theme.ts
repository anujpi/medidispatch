/**
 * The colour theme: which of the three palettes in tokens.css is shown. It is an attribute on <html>
 * (data-theme="navy"), so the whole page switches at once, and it is remembered in this browser.
 */

export const THEMES = [
  { id: 'cream', name: 'Cream, ink and burnt orange' },
  { id: 'navy', name: 'Navy, white and hot pink' },
  { id: 'charcoal', name: 'Charcoal, sand and electric blue' },
] as const

export type ThemeId = (typeof THEMES)[number]['id']

const KEY = 'medidispatch.theme'

/** @returns the remembered theme, or cream; storage can be blocked (private windows), so it is guarded */
export function savedTheme(): ThemeId {
  try {
    const value = localStorage.getItem(KEY)
    const known = THEMES.find((t) => t.id === value)
    return known ? known.id : 'cream'
  } catch {
    return 'cream'
  }
}

/** Shows a theme and remembers it. */
export function applyTheme(id: ThemeId) {
  document.documentElement.dataset.theme = id
  try {
    localStorage.setItem(KEY, id)
  } catch {
    // not remembered, that is all
  }
}
