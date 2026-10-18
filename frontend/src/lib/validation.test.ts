import { describe, expect, it } from 'vitest';
import { normalizeUrl, validateAlias } from './validation';

describe('normalizeUrl', () => {
  it.each([
    ['https://example.com/a?b=1', 'https://example.com/a?b=1'],
    ['  http://example.com  ', 'http://example.com'],
    ['example.com/docs', 'https://example.com/docs'],
    ['http://localhost:3000', 'http://localhost:3000'],
  ])('accepts %s', (input, expected) => {
    expect(normalizeUrl(input)).toBe(expected);
  });

  it.each(['', '   ', 'hello', 'javascript:alert(1)', 'ftp://example.com', 'mailto:a@b.com', 'https://exa mple.com'])(
    'rejects %j',
    (input) => {
      expect(normalizeUrl(input)).toBeNull();
    },
  );

  it('rejects URLs longer than the backend limit', () => {
    expect(normalizeUrl(`https://example.com/${'a'.repeat(2048)}`)).toBeNull();
  });
});

describe('validateAlias', () => {
  it('treats an empty alias as "none"', () => {
    expect(validateAlias('')).toBeNull();
  });

  it('accepts the backend pattern', () => {
    expect(validateAlias('my_link-2026')).toBeNull();
  });

  it('explains what is wrong', () => {
    expect(validateAlias('ab')).toMatch(/at least 3/i);
    expect(validateAlias('a'.repeat(33))).toMatch(/at most 32/i);
    expect(validateAlias('no/slash')).toMatch(/only letters/i);
  });
});
