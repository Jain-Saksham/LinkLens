import { describe, expect, it } from 'vitest';
import { displayUrl, formatRelative, linkStatus } from './format';

const NOW = new Date('2026-01-01T12:00:00Z');

describe('formatRelative', () => {
  it('formats past and future times', () => {
    expect(formatRelative('2026-01-04T12:00:00Z', NOW)).toBe('in 3 days');
    expect(formatRelative('2026-01-01T10:00:00Z', NOW)).toBe('2 hours ago');
    expect(formatRelative('2026-01-01T12:00:20Z', NOW)).toBe('just now');
  });
});

describe('linkStatus', () => {
  it('distinguishes never-expiring, expiring and expired links', () => {
    expect(linkStatus(null, NOW)).toEqual({ kind: 'active' });
    expect(linkStatus('2026-01-02T12:00:00Z', NOW)).toEqual({ kind: 'expiring', label: 'Expires tomorrow' });
    expect(linkStatus('2026-01-01T12:00:00Z', NOW)).toEqual({ kind: 'expired' });
  });
});

describe('displayUrl', () => {
  it('drops scheme and www, and truncates long URLs', () => {
    expect(displayUrl('https://www.example.com/')).toBe('example.com');
    expect(displayUrl('https://example.com/a?b=1')).toBe('example.com/a?b=1');
    expect(displayUrl(`https://example.com/${'x'.repeat(100)}`, 20)).toHaveLength(20);
  });
});
