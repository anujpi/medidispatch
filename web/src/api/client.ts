import type { ProblemDetail } from './types'

/**
 * A request that did not succeed. {@code status} is the HTTP status, or 0 when the service could not be reached at
 * all (it is down, or the network is), so screens can tell "not found" from "offline".
 */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }

  /** @returns true when no answer came back at all */
  get unreachable(): boolean {
    return this.status === 0
  }
}

/**
 * Sends one request to the service and reads the JSON answer. Paths are relative ("/api/..."): the dev server's
 * proxy, or the same origin in production, takes them to the service.
 *
 * @throws ApiError for any answer that is not 2xx (with the ProblemDetail's "detail" as the message), or with status
 *         0 when the service cannot be reached
 */
async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers: body === undefined ? { Accept: 'application/json' }
        : { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'The dispatch service cannot be reached')
  }
  // a dev proxy whose target is down answers 502/503/504 itself: to the screen that is "unreachable" too
  if (response.status === 502 || response.status === 503 || response.status === 504) {
    throw new ApiError(0, 'The dispatch service cannot be reached')
  }
  const text = await response.text()
  if (!response.ok) {
    throw new ApiError(response.status, problemMessage(text, response.status))
  }
  return (text === '' ? undefined : JSON.parse(text)) as T
}

// the ProblemDetail's "detail" if the body is one, else a plain message
function problemMessage(text: string, status: number): string {
  try {
    const problem = JSON.parse(text) as ProblemDetail
    if (problem.detail) return problem.detail
    if (problem.title) return problem.title
  } catch {
    // not JSON: fall through
  }
  return `The service answered ${status}`
}

/** GET a JSON resource. */
export function getJson<T>(path: string): Promise<T> {
  return request<T>('GET', path)
}

/** POST a JSON body; resolves to the JSON answer, or undefined when there is none (201/204 with no body). */
export function postJson<T = void>(path: string, body?: unknown): Promise<T> {
  return request<T>('POST', path, body)
}

/** PUT a JSON body. */
export function putJson<T = void>(path: string, body: unknown): Promise<T> {
  return request<T>('PUT', path, body)
}

/** DELETE a resource. */
export function del(path: string): Promise<void> {
  return request<void>('DELETE', path)
}
