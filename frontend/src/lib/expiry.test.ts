import { describe, expect, it } from 'vitest';
import { resolveExpiry, toLocalInputValue, validateCustomExpiry } from './expiry';

const NOW = new Date('2026-01-01T12:00:00Z');

describe('resolveExpiry', () => {
  it('returns undefined for "never" so the field is omitted', () => {
    expect(resolveExpiry('never', '', NOW)).toBeUndefined();
  });

  it('adds the preset duration to now', () => {
    expect(resolveExpiry('1h', '', NOW)).toBe('2026-01-01T13:00:00.000Z');
    expect(resolveExpiry('7d', '', NOW)).toBe('2026-01-08T12:00:00.000Z');
  });

  it('converts a local datetime-local value to an ISO instant', () => {
    const local = toLocalInputValue(new Date('2026-03-01T09:30:00Z'));
    expect(resolveExpiry('custom', local, NOW)).toBe('2026-03-01T09:30:00.000Z');
  });
});

describe('validateCustomExpiry', () => {
  it('requires a future date', () => {
    expect(validateCustomExpiry('', NOW)).toMatch(/pick/i);
    expect(validateCustomExpiry(toLocalInputValue(new Date('2025-12-31T00:00:00Z')), NOW)).toMatch(/future/i);
    expect(validateCustomExpiry(toLocalInputValue(new Date('2026-02-01T00:00:00Z')), NOW)).toBeNull();
  });
});
