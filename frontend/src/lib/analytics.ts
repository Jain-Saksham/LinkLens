import type { Analytics } from '../api/client';

/**
 * Folds a live all-time count into a loaded analytics snapshot: the clicks since the snapshot are
 * added to the total, the period and today's bar. Never subtracts (a stale event can't shrink data).
 */
export function applyLiveCount(data: Analytics, liveTotal: number | null): Analytics {
  if (liveTotal === null || liveTotal <= data.totalClicks || data.daily.length === 0) return data;
  const delta = liveTotal - data.totalClicks;
  const daily = data.daily.slice();
  const today = daily[daily.length - 1];
  daily[daily.length - 1] = { ...today, clicks: today.clicks + delta };
  return { ...data, totalClicks: liveTotal, periodClicks: data.periodClicks + delta, daily };
}
