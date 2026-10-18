import type { Analytics, InsightSeverity } from '../../api/client';

const STYLE: Record<InsightSeverity, { label: string; icon: string; className: string }> = {
  POSITIVE: { label: 'Good news', icon: '↗', className: 'bg-ok-soft text-ok' },
  WARNING: { label: 'Heads up', icon: '!', className: 'bg-warn-soft text-warn' },
  NEUTRAL: { label: 'Note', icon: 'i', className: 'bg-surface-sunk text-ink-soft' },
};

/** Status is never color-alone: each insight carries an icon and a text label too. */
export function InsightList({ insights }: { insights: Analytics['insights'] }) {
  return (
    <ul className="grid gap-3">
      {insights.map((insight) => {
        const style = STYLE[insight.severity];
        return (
          <li key={insight.type} className="flex gap-3 rounded-2xl border border-line bg-surface p-4">
            <span
              aria-hidden
              className={`grid size-8 shrink-0 place-items-center rounded-full font-mono text-sm font-semibold ${style.className}`}
            >
              {style.icon}
            </span>
            <div className="min-w-0">
              <p className="text-[11px] font-medium uppercase tracking-wider text-muted">{style.label}</p>
              <p className="mt-0.5 font-medium text-ink">{insight.title}</p>
              <p className="mt-1 text-[13px] leading-relaxed text-ink-soft">{insight.message}</p>
            </div>
          </li>
        );
      })}
    </ul>
  );
}
