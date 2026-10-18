import { describe, expect, it } from 'vitest';
import { columnPath, formatDay, labelIndices, niceScale } from './chart';

describe('niceScale', () => {
  it('rounds the axis up to 1/2/5 steps', () => {
    expect(niceScale(7)).toEqual({ max: 8, step: 2 });
    expect(niceScale(130)).toEqual({ max: 150, step: 50 });
    expect(niceScale(1000)).toEqual({ max: 1000, step: 500 });
  });

  it('never produces fractional ticks for small counts, and handles an empty chart', () => {
    expect(niceScale(3)).toEqual({ max: 3, step: 1 });
    expect(niceScale(0)).toEqual({ max: 4, step: 1 });
  });
});

describe('labelIndices', () => {
  it('labels every bar when they fit', () => {
    expect(labelIndices(5, 7)).toEqual([0, 1, 2, 3, 4]);
  });

  it('always includes first and last, evenly spaced, without crowding the end', () => {
    const idx = labelIndices(30, 7);
    expect(idx[0]).toBe(0);
    expect(idx.at(-1)).toBe(29);
    expect(idx.length).toBeLessThanOrEqual(7);
    expect(29 - idx.at(-2)!).toBeGreaterThanOrEqual(3);
  });
});

describe('columnPath', () => {
  it('rounds the top corners and keeps the baseline square', () => {
    expect(columnPath(0, 10, 20, 50)).toBe('M0,60 V14 Q0,10 4,10 H16 Q20,10 20,14 V60 Z');
  });

  it('draws nothing for a zero-height bar and clamps the radius for tiny ones', () => {
    expect(columnPath(0, 10, 20, 0)).toBe('');
    expect(columnPath(0, 10, 20, 2)).toContain('Q0,10 2,10');
  });
});

describe('formatDay', () => {
  it('formats UTC calendar days without timezone drift', () => {
    expect(formatDay('2026-01-01')).toBe('Jan 1');
    expect(formatDay('2026-03-12', true)).toBe('Thu, Mar 12');
  });
});
