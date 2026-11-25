import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { AppRoutes } from './routes'
import { applyTheme, savedTheme } from './components/theme'
import './styles/tokens.css'
import './styles/base.css'

// the remembered colour theme, set before the first paint so the page never flashes the default
applyTheme(savedTheme())

// The browser's address decides which of the four apps shows (see routes.tsx). StrictMode runs effects twice in
// development, which surfaces effects that forget to clean up (an EventSource left open, a timer left running).
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AppRoutes />
    </BrowserRouter>
  </StrictMode>,
)
