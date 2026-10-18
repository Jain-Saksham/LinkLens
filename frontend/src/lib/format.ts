const relative = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 365 * 24 * 3600],
  ['month', 30 * 24 * 3600],
  ['week', 7 * 24 * 3600],
  ['day', 24 * 3600],
  ['hour', 3600],
  ['minute', 60],
];

/** "in 3 days", "2 hours ago", "now". */
export function formatRelative(iso: string, now = new Date()): string {
  const seconds = Math.round((new Date(iso).getTime() - now.getTime()) / 1000);
  for (const [unit, size] of UNITS) {
    if (Math.abs(seconds) >= size) return relative.format(Math.trunc(seconds / size), unit);
  }
  return 'just now';
}

export type LinkStatus = { kind: 'active' } | { kind: 'expiring'; label: string } | { kind: 'expired' };

export function linkStatus(expiresAt: string | null, now = new Date()): LinkStatus {
  if (!expiresAt) return { kind: 'active' };
  if (new Date(expiresAt).getTime() <= now.getTime()) return { kind: 'expired' };
  return { kind: 'expiring', label: `Expires ${formatRelative(expiresAt, now)}` };
}

/** "example.com/docs/…" — readable host + path without the scheme noise. */
export function displayUrl(url: string, maxLength = 60): string {
  try {
    const { host, pathname, search } = new URL(url);
    const text = host.replace(/^www\./, '') + (pathname === '/' ? '' : pathname) + search;
    return text.length > maxLength ? `${text.slice(0, maxLength - 1)}…` : text;
  } catch {
    return url;
  }
}

export function formatCount(n: number): string {
  return new Intl.NumberFormat('en', { notation: n >= 10_000 ? 'compact' : 'standard' }).format(n);
}
