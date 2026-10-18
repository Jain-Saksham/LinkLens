/** Same rule as the backend's @Pattern on customAlias. */
export const ALIAS_PATTERN = /^[A-Za-z0-9_-]{3,32}$/;

const MAX_URL_LENGTH = 2048;

/**
 * Accepts what people actually paste ("example.com/x") by assuming https when no scheme is given.
 * Returns null if the result still isn't a valid http(s) URL with a host.
 */
export function normalizeUrl(input: string): string | null {
  const trimmed = input.trim();
  if (!trimmed) return null;

  const hasScheme = /^[a-z][a-z0-9+.-]*:/i.test(trimmed);
  const candidate = hasScheme ? trimmed : `https://${trimmed}`;

  try {
    const url = new URL(candidate);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
    // A bare word ("hello") parses as a host; require a dot or localhost to catch typos.
    if (!url.hostname.includes('.') && url.hostname !== 'localhost') return null;
    if (candidate.length > MAX_URL_LENGTH) return null;
    return candidate;
  } catch {
    return null;
  }
}

export function validateAlias(alias: string): string | null {
  if (!alias) return null;
  if (alias.length < 3) return 'At least 3 characters';
  if (alias.length > 32) return 'At most 32 characters';
  if (!ALIAS_PATTERN.test(alias)) return "Only letters, digits, '-' and '_'";
  return null;
}
