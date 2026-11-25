import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { FakeEventSource, sampleSnapshot } from '../test/fakes'
import { useLiveSnapshot } from './useLiveSnapshot'

describe('useLiveSnapshot', () => {
  beforeEach(() => FakeEventSource.install())

  it('starts empty, then shows each picture as it arrives', () => {
    const { result } = renderHook(() => useLiveSnapshot())
    expect(result.current).toEqual({ snapshot: null, connected: false })
    expect(FakeEventSource.latest().url).toBe('/api/live')

    act(() => FakeEventSource.latest().emit('snapshot', JSON.stringify(sampleSnapshot())))

    expect(result.current.connected).toBe(true)
    expect(result.current.snapshot?.units).toHaveLength(2)
  })

  it('keeps the last picture while reconnecting', () => {
    const { result } = renderHook(() => useLiveSnapshot())
    act(() => FakeEventSource.latest().emit('snapshot', JSON.stringify(sampleSnapshot())))

    act(() => FakeEventSource.latest().fail())

    expect(result.current.connected).toBe(false)
    expect(result.current.snapshot).not.toBeNull()
  })

  it('skips a broken picture', () => {
    const { result } = renderHook(() => useLiveSnapshot())

    act(() => FakeEventSource.latest().emit('snapshot', '{not json'))

    expect(result.current.snapshot).toBeNull()
  })

  it('closes the stream when the screen goes away', () => {
    const { unmount } = renderHook(() => useLiveSnapshot())

    unmount()

    expect(FakeEventSource.latest().closed).toBe(true)
  })
})
