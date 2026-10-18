/** Pure helpers for the click chart, kept out of the component so they can be unit-tested. */

/** A "nice" axis maximum and tick step (1, 2, 5 × 10^n) so ticks read as round numbers. */
export function niceScale(max: number, targetTicks = 4): { max: number; step: number } {
  if (max <= 0) return { max: targetTicks, step: 1 };
  const rough = max / targetTicks;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 5, 10].map((m) => m * magnitude).find((s) => s >= rough) ?? 10 * magnitude;
  const integerStep = Math.max(1, step); // click counts are integers: never a 0.5 tick
  return { max: Math.ceil(max / integerStep) * integerStep, step: integerStep };
}

/** Indices of the x-axis labels to show: first, last and evenly spaced ones between. */
export function labelIndices(count: number, maxLabels: number): number[] {
  if (count <= maxLabels) return Array.from({ length: count }, (_, i) => i);
  const every = Math.ceil((count - 1) / (maxLabels - 1));
  const indices: number[] = [];
  for (let i = 0; i < count - 1; i += every) indices.push(i);
  // Drop a label that would crowd the final one.
  if (count - 1 - indices[indices.length - 1] < every / 2) indices.pop();
  indices.push(count - 1);
  return indices;
}

/** Column path: 4px rounded data-end, square at the baseline. */
export function columnPath(x: number, y: number, width: number, height: number, radius = 4): string {
  if (height <= 0) return '';
  const r = Math.min(radius, height, width / 2);
  const bottom = y + height;
  return [
    `M${x},${bottom}`,
    `V${y + r}`,
    `Q${x},${y} ${x + r},${y}`,
    `H${x + width - r}`,
    `Q${x + width},${y} ${x + width},${y + r}`,
    `V${bottom}`,
    'Z',
  ].join(' ');
}

const SHORT_DATE = new Intl.DateTimeFormat('en', { month: 'short', day: 'numeric', timeZone: 'UTC' });
const LONG_DATE = new Intl.DateTimeFormat('en', { weekday: 'short', month: 'short', day: 'numeric', timeZone: 'UTC' });

/** Dates are UTC calendar days ("2026-10-18"); format them in UTC so they never shift a day. */
export function formatDay(isoDate: string, long = false): string {
  const date = new Date(`${isoDate}T00:00:00Z`);
  return (long ? LONG_DATE : SHORT_DATE).format(date);
}
