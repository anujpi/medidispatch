import { useState } from 'react'
import styles from './components.module.css'
import { THEMES, applyTheme, savedTheme, type ThemeId } from './theme'

/** Three round swatches in the header, one per theme; the chosen one is ringed. */
export function ThemePicker() {
  const [theme, setTheme] = useState<ThemeId>(savedTheme)
  return (
    <div className={styles.themes} role="group" aria-label="Colour theme">
      {THEMES.map((t) => (
        <button
          key={t.id}
          type="button"
          className={`${styles.swatch} ${styles[t.id]}`}
          aria-label={t.name}
          title={t.name}
          aria-pressed={theme === t.id}
          onClick={() => {
            applyTheme(t.id)
            setTheme(t.id)
          }}
        />
      ))}
    </div>
  )
}
