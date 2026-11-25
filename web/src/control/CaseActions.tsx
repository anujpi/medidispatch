import { useState, type FormEvent } from 'react'
import { ApiError, postJson } from '../api/client'
import type { CaseInfo, LiveSnapshot } from '../api/types'
import { UNIT_TYPE } from '../labels'
import css from './control.module.css'

type Mode = 'none' | 'send' | 'cancel'

/**
 * The dispatcher's overrides for the picked case: send a unit of their choice, or cancel the case. Each asks for a
 * reason (it goes on the record) and shows the service's own words when it refuses. The live feed shows the result
 * within a second, so nothing is updated here by hand.
 */
export function CaseActions({ snapshot, openCase }: { snapshot: LiveSnapshot; openCase: CaseInfo }) {
  const [mode, setMode] = useState<Mode>('none')
  const [unitId, setUnitId] = useState('')
  const [reason, setReason] = useState('')
  const call = useCall(() => {
    setMode('none')
    setUnitId('')
    setReason('')
  })

  const canSend = openCase.status === 'ALERTED' || openCase.status === 'RESPONDING'
  const canCancel = canSend || openCase.status === 'MONITORING'
  if (!canSend && !canCancel) {
    return null                                       // on the way to hospital: nothing to override
  }

  if (mode === 'none') {
    return (
      <div className={css.actions}>
        {canSend && <button type="button" className={css.small} onClick={() => setMode('send')}>Send another unit</button>}
        {canCancel && (
          <button type="button" className={`${css.small} ${css.danger}`} onClick={() => setMode('cancel')}>Cancel case</button>
        )}
      </div>
    )
  }

  // free units, nearest first as the crow flies; the service refuses one that cannot serve this case, and says why
  const free = snapshot.units
    .filter((u) => u.status === 'AVAILABLE')
    .map((u) => ({ unit: u, km: kmBetween(u.latitude, u.longitude, openCase.latitude, openCase.longitude) }))
    .sort((a, b) => a.km - b.km)
  const id = encodeURIComponent(openCase.id)

  const submit = (e: FormEvent) => {
    e.preventDefault()
    call.run(mode === 'send'
      ? () => postJson(`/api/cases/${id}/send`, { unitId, reason })
      : () => postJson(`/api/cases/${id}/cancel`, { reason }))
  }

  return (
    <form className={css.actionForm} onSubmit={submit}>
      <h3>{mode === 'send' ? 'Send another unit' : 'Cancel this case'}</h3>
      {mode === 'send' && (
        <select className={css.field} required value={unitId} onChange={(e) => setUnitId(e.target.value)}
                aria-label="Unit to send">
          <option value="">{free.length === 0 ? 'No unit is free' : 'Choose a free unit'}</option>
          {free.map((f) => (
            <option key={f.unit.id} value={f.unit.id}>
              {f.unit.id}, {UNIT_TYPE[f.unit.type]}, {f.km.toFixed(1)} km
            </option>
          ))}
        </select>
      )}
      {mode === 'send' && (
        <p className={css.formNote}>The unit on the way now, if any, is freed.</p>
      )}
      {mode === 'cancel' && (
        <p className={css.formNote}>Every unit on this case is freed. If the readings stay bad, a new case opens.</p>
      )}
      <input className={css.field} required maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)}
             aria-label="Reason" placeholder="Reason, for the record" />
      {call.error && <p role="alert" className={css.formError}>{call.error}</p>}
      <div className={css.actions}>
        <button type="submit" disabled={call.busy} className={mode === 'cancel' ? css.dangerSolid : css.primary}>
          {mode === 'send' ? 'Send unit' : 'Cancel case'}
        </button>
        <button type="button" className={css.small} onClick={() => { setMode('none'); call.clear() }}>Back</button>
      </div>
    </form>
  )
}

/**
 * Takes one unit off duty, from its row in the case card: a link that opens a one-line form asking why. Not offered
 * while the unit carries a patient (the service would refuse).
 */
export function OutOfService({ unitId }: { unitId: string }) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const call = useCall(() => {
    setOpen(false)
    setReason('')
  })

  if (!open) {
    return <button type="button" className={css.link} onClick={() => setOpen(true)}>Out of service</button>
  }
  return (
    <form className={css.inlineForm} onSubmit={(e) => {
      e.preventDefault()
      call.run(() => postJson(`/api/units/${encodeURIComponent(unitId)}/out-of-service`, { reason }))
    }}>
      <input className={css.field} required maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)}
             aria-label={`Why ${unitId} is out of service`} placeholder="Why, e.g. flat tyre" />
      <button type="submit" disabled={call.busy} className={css.dangerSolid}>Take out</button>
      <button type="button" className={css.small} onClick={() => { setOpen(false); call.clear() }}>Back</button>
      {call.error && <p role="alert" className={css.formError}>{call.error}</p>}
    </form>
  )
}

// one request at a time: busy while it runs, the service's message if it refuses, `done` when it succeeds
function useCall(done: () => void) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  return {
    busy,
    error,
    clear: () => setError(null),
    run: (request: () => Promise<unknown>) => {
      setBusy(true)
      setError(null)
      request().then(
        () => {
          setBusy(false)
          done()
        },
        (e: unknown) => {
          setBusy(false)
          setError(e instanceof ApiError ? e.message : String(e))
        },
      )
    },
  }
}

// straight-line km, good enough to order a short list (a degree of longitude shrinks away from the equator)
function kmBetween(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const dLat = (lat2 - lat1) * 111.32
  const dLon = (lon2 - lon1) * 111.32 * Math.cos((lat1 * Math.PI) / 180)
  return Math.sqrt(dLat * dLat + dLon * dLon)
}
