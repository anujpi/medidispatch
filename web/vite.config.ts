import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // The app always calls relative paths (/api/...). In development the browser only ever talks to this dev server
    // (port 5173), which forwards /api to the Spring Boot service, server to server. To the browser everything is one
    // origin, so no CORS is needed. In production the service (or one reverse proxy) serves both: also one origin.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',             // a fake browser (document, window) for components and hooks
    setupFiles: ['./src/test/setup.ts'],
  },
})
