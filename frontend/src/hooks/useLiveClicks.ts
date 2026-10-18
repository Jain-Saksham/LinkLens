import { useEffect, useState } from 'react';
import { clickStreamUrl } from '../api/client';

export interface LiveClicks {
  /** Latest all-time click count pushed by the server; null until the first event. */
  count: number | null;
  /** True while the stream is open. */
  live: boolean;
}

/**
 * Subscribes to a link's live click count (Server-Sent Events). EventSource reconnects by itself after
 * network blips; a 404 or 503 closes it for good. Use sparingly: browsers allow ~6 concurrent HTTP/1.1
 * connections per origin, so streams belong on focused views, not on every row of a list.
 */
export function useLiveClicks(shortCode: string | null): LiveClicks {
  const [state, setState] = useState<LiveClicks>({ count: null, live: false });

  useEffect(() => {
    if (!shortCode || typeof EventSource === 'undefined') return;
    const source = new EventSource(clickStreamUrl(shortCode));
    source.addEventListener('clicks', (event) => {
      try {
        const { clickCount } = JSON.parse((event as MessageEvent<string>).data) as { clickCount: number };
        setState({ count: clickCount, live: true });
      } catch {
        // ignore malformed events
      }
    });
    source.onopen = () => setState((s) => ({ ...s, live: true }));
    source.onerror = () => setState((s) => ({ ...s, live: source.readyState === EventSource.OPEN }));
    return () => source.close();
  }, [shortCode]);

  return state;
}
