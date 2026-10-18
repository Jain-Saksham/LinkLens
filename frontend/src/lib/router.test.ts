import { describe, expect, it } from 'vitest';
import { analyticsPath, parseRoute } from './router';

describe('parseRoute', () => {
  it('maps paths to routes', () => {
    expect(parseRoute('/')).toEqual({ name: 'home' });
    expect(parseRoute('/links/abc_12-x')).toEqual({ name: 'analytics', code: 'abc_12-x' });
    expect(parseRoute('/links/abc/')).toEqual({ name: 'analytics', code: 'abc' });
  });

  it('rejects anything that is not a valid code', () => {
    expect(parseRoute('/links/ab')).toEqual({ name: 'not-found' });
    expect(parseRoute('/links/a.b.c')).toEqual({ name: 'not-found' });
    expect(parseRoute('/elsewhere')).toEqual({ name: 'not-found' });
  });

  it('round-trips analyticsPath', () => {
    expect(parseRoute(analyticsPath('my-link'))).toEqual({ name: 'analytics', code: 'my-link' });
  });
});
