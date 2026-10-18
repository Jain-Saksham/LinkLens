import { describe, expect, it } from 'vitest';
import type { Analytics } from '../api/client';
import { applyLiveCount } from './analytics';

const base: Analytics = {
  shortCode: 'abc', shortUrl: 'http://l/abc', originalUrl: 'https://e.com', createdAt: '', expiresAt: null,
  totalClicks: 10, periodDays: 2, periodClicks: 6,
  daily: [{ date: '2026-01-01', clicks: 4 }, { date: '2026-01-02', clicks: 2 }],
  referrers: [], insights: [], generatedAt: '',
};

describe('applyLiveCount', () => {
  it('adds new clicks to the total, the period and today', () => {
    const next = applyLiveCount(base, 13);
    expect(next.totalClicks).toBe(13);
    expect(next.periodClicks).toBe(9);
    expect(next.daily.map((d) => d.clicks)).toEqual([4, 5]);
    expect(base.daily[1].clicks).toBe(2); // input untouched
  });

  it('ignores missing, equal or stale counts', () => {
    expect(applyLiveCount(base, null)).toBe(base);
    expect(applyLiveCount(base, 10)).toBe(base);
    expect(applyLiveCount(base, 7)).toBe(base);
  });
});
