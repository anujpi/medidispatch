import { describe, expect, it, vi } from 'vitest'
import { fakeFetch } from '../test/fakes'
import { ApiError, del, getJson, postJson } from './client'

describe('the API client', () => {
  it('reads a JSON answer', async () => {
    vi.stubGlobal('fetch', fakeFetch({ '/api/facilities': [200, [{ id: 'blr-h-jayanagar' }]] }))

    await expect(getJson('/api/facilities')).resolves.toEqual([{ id: 'blr-h-jayanagar' }])
  })

  it('turns an error answer into an ApiError with the ProblemDetail text', async () => {
    vi.stubGlobal('fetch', fakeFetch({
      '/api/trips/t-1/arrive': [409, { status: 409, detail: 'Trip t-1 is DONE' }],
    }))

    const error = await postJson('/api/trips/t-1/arrive').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409, message: 'Trip t-1 is DONE', unreachable: false })
  })

  it('says "unreachable" (status 0) when no answer comes back at all', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))

    await expect(getJson('/api/live')).rejects.toMatchObject({ status: 0, unreachable: true })
  })

  it('treats the dev proxy\'s 502 (service down) as unreachable too', async () => {
    vi.stubGlobal('fetch', fakeFetch({ '/api/facilities': [502, undefined] }))

    await expect(getJson('/api/facilities')).rejects.toMatchObject({ status: 0 })
  })

  it('sends JSON bodies and accepts an empty answer', async () => {
    const fetch = fakeFetch({ '/api/incidents': [201, undefined], '/api/incidents/x': [204, undefined] })
    vi.stubGlobal('fetch', fetch)

    await expect(postJson('/api/incidents', { id: 'x' })).resolves.toBeUndefined()
    await expect(del('/api/incidents/x')).resolves.toBeUndefined()

    const [, init] = fetch.mock.calls[0] as unknown as [string, RequestInit]
    expect(init.method).toBe('POST')
    expect(init.body).toBe('{"id":"x"}')
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json')
  })

  it('gives a plain message when the error body is not a ProblemDetail', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('oops', { status: 500 })))

    await expect(getJson('/api/x')).rejects.toMatchObject({ status: 500, message: 'The service answered 500' })
  })
})
