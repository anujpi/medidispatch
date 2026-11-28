/**
 * The MediDispatch mark: a red rounded square with a white heartbeat line that runs out flat on the right, like a
 * road: the warning sign turning into a journey. Drawn inline (not an <img>) so it stays sharp at any size.
 *
 * @param size the width and height in pixels
 */
export function Logo({ size = 22 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true" focusable="false">
      <rect width="32" height="32" rx="8" fill="#c62d33" />
      <path d="M5 17h5l2.5-6 4 11 3-8 2 3H27" fill="none" stroke="#fff" strokeWidth="2.6"
            strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}
