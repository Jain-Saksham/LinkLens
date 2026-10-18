export type ExpiryPreset = 'never' | '1h' | '1d' | '7d' | '30d' | 'custom';

export const EXPIRY_OPTIONS: { value: ExpiryPreset; label: string }[] = [
  { value: 'never', label: 'Never' },
  { value: '1h', label: '1 hour' },
  { value: '1d', label: '1 day' },
  { value: '7d', label: '7 days' },
  { value: '30d', label: '30 days' },
  { value: 'custom', label: 'Custom' },
];

const HOUR = 3_600_000;
const PRESET_MS: Record<Exclude<ExpiryPreset, 'never' | 'custom'>, number> = {
  '1h': HOUR,
  '1d': 24 * HOUR,
  '7d': 7 * 24 * HOUR,
  '30d': 30 * 24 * HOUR,
};

/**
 * Resolves the chosen preset to an ISO instant for the API, or undefined for "never".
 * `customLocal` is a datetime-local value ("2026-10-20T09:30"), interpreted in the user's timezone.
 */
export function resolveExpiry(preset: ExpiryPreset, customLocal: string, now = new Date()): string | undefined {
  if (preset === 'never') return undefined;
  if (preset === 'custom') {
    return customLocal ? new Date(customLocal).toISOString() : undefined;
  }
  return new Date(now.getTime() + PRESET_MS[preset]).toISOString();
}

export function validateCustomExpiry(customLocal: string, now = new Date()): string | null {
  if (!customLocal) return 'Pick a date and time';
  const date = new Date(customLocal);
  if (Number.isNaN(date.getTime())) return 'Invalid date';
  if (date.getTime() <= now.getTime()) return 'Must be in the future';
  return null;
}

/** datetime-local value for `now + 1 day`, used to pre-fill and as the picker's minimum. */
export function toLocalInputValue(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
