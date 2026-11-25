// Runs before every test file: adds DOM matchers such as toBeInTheDocument() to expect(), and unmounts whatever a
// test rendered so the next test starts from an empty page.
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
