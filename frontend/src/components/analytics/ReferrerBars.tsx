import type { Analytics } from '../../api/client';
import { formatCount } from '../../lib/format';

interface Props {
  referrers: Analytics['referrers'];
}

/** Ranked horizontal bars: magnitude in one hue, the label text carries identity. */
export function ReferrerBars({ referrers }: Props) {
  if (referrers.length === 0) {
    return <p className="py-6 text-center text-sm text-muted">No clicks in this period yet</p>;
  }
  const max = Math.max(...referrers.map((r) => r.clicks));

  return (
    <ul className="space-y-3.5">
      {referrers.map((r) => (
        <li key={r.source}>
          <div className="mb-1.5 flex items-baseline justify-between gap-3 text-[13px]">
            <span className={`truncate ${r.source === 'Direct' || r.source === 'Other' ? 'text-ink-soft italic' : 'text-ink'}`}>
              {r.source}
            </span>
            <span className="shrink-0 font-mono text-xs tabular-nums text-muted">
              {formatCount(r.clicks)} · {Math.round(r.share * 100)}%
            </span>
          </div>
          <div className="h-2 rounded-full bg-surface-sunk">
            <div
              className="h-full rounded-full bg-chart"
              style={{ width: `${Math.max(2, (r.clicks / max) * 100)}%` }}
              title={`${r.source}: ${r.clicks} clicks`}
            />
          </div>
        </li>
      ))}
    </ul>
  );
}
