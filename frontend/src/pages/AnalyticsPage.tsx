import { useCallback, useEffect, useState } from 'react';
import { type Analytics, ApiError, getAnalytics } from '../api/client';
import { ClicksChart } from '../components/analytics/ClicksChart';
import { InsightList } from '../components/analytics/InsightList';
import { ReferrerBars } from '../components/analytics/ReferrerBars';
import { CopyButton } from '../components/CopyButton';
import { ArrowUpRightIcon, RefreshIcon } from '../components/icons';
import { formatDay } from '../lib/chart';
import { displayUrl, formatCount, formatRelative, linkStatus } from '../lib/format';
import { onLinkClick } from '../lib/router';

const PERIODS = [7, 30, 90] as const;
type Period = (typeof PERIODS)[number];

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; data: Analytics }
  | { kind: 'error'; status: number; message: string };

export function AnalyticsPage({ code }: { code: string }) {
  const [period, setPeriod] = useState<Period>(30);
  const [state, setState] = useState<State>({ kind: 'loading' });
  const [refreshing, setRefreshing] = useState(false);
  const [showTable, setShowTable] = useState(false);

  const load = useCallback(async () => {
    setRefreshing(true);
    try {
      setState({ kind: 'ready', data: await getAnalytics(code, period) });
    } catch (err) {
      setState(
        err instanceof ApiError
          ? { kind: 'error', status: err.status, message: err.message }
          : { kind: 'error', status: 0, message: 'Something went wrong.' },
      );
    } finally {
      setRefreshing(false);
    }
  }, [code, period]);

  useEffect(() => {
    void load();
  }, [load]);

  if (state.kind === 'error') {
    return (
      <div className="py-20 text-center">
        <p className="font-display text-4xl text-ink">{state.status === 404 ? 'Link not found' : 'Couldn’t load stats'}</p>
        <p className="mt-3 text-sm text-muted">{state.status === 404 ? `There is no link with the code “${code}”.` : state.message}</p>
        <a href="/" onClick={onLinkClick('/')} className="mt-6 inline-block text-sm font-medium text-accent hover:underline">
          ← Back to your links
        </a>
      </div>
    );
  }

  const data = state.kind === 'ready' ? state.data : null;
  const status = data ? linkStatus(data.expiresAt) : null;
  const topSource = data?.referrers.find((r) => r.source !== 'Other');

  return (
    <div className="animate-rise pb-16 pt-4">
      <a href="/" onClick={onLinkClick('/')} className="text-[13px] font-medium text-muted transition hover:text-ink">
        ← All links
      </a>

      {/* Header */}
      <header className="mt-5 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div className="min-w-0">
          <p className="text-[11px] font-medium uppercase tracking-wider text-muted">Link analytics</p>
          <h1 className="mt-1 flex items-center gap-2 font-mono text-2xl text-ink sm:text-3xl">
            <span className="truncate">/{code}</span>
            {data && <CopyButton text={data.shortUrl} />}
          </h1>
          {data && (
            <p className="mt-2 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-[13px] text-muted">
              <a href={data.originalUrl} target="_blank" rel="noreferrer" className="inline-flex min-w-0 items-center gap-1 hover:text-ink">
                <span className="truncate">{displayUrl(data.originalUrl, 56)}</span>
                <ArrowUpRightIcon width={12} height={12} className="shrink-0" />
              </a>
              <span>Created {formatRelative(data.createdAt)}</span>
              {status?.kind === 'expired' && <span className="rounded-full bg-danger-soft px-2 py-0.5 text-[11px] font-medium text-danger">Expired</span>}
              {status?.kind === 'expiring' && <span className="rounded-full bg-warn-soft px-2 py-0.5 text-[11px] font-medium text-warn">{status.label}</span>}
            </p>
          )}
        </div>

        {/* Filters: one row, above the charts */}
        <div className="flex items-center gap-2">
          <div role="radiogroup" aria-label="Time range" className="flex rounded-full border border-line bg-surface p-0.5">
            {PERIODS.map((p) => (
              <button
                key={p}
                type="button"
                role="radio"
                aria-checked={period === p}
                onClick={() => setPeriod(p)}
                className={`h-8 rounded-full px-3.5 text-[13px] font-medium transition ${
                  period === p ? 'bg-ink text-paper' : 'text-ink-soft hover:text-ink'
                }`}
              >
                {p}d
              </button>
            ))}
          </div>
          <button
            type="button"
            onClick={() => void load()}
            disabled={refreshing}
            aria-label="Refresh"
            className="grid size-9 place-items-center rounded-full border border-line bg-surface text-muted transition hover:text-ink disabled:opacity-60"
          >
            <RefreshIcon className={refreshing ? 'animate-spin' : ''} width={14} height={14} />
          </button>
        </div>
      </header>

      {/* Stat tiles */}
      <section className="mt-8 grid grid-cols-2 gap-3 lg:grid-cols-4" aria-label="Summary">
        <StatTile label="Total clicks" value={data ? formatCount(data.totalClicks) : null} hint="All time" hero />
        <StatTile label={`Last ${period} days`} value={data ? formatCount(data.periodClicks) : null} hint="UTC days, incl. today" />
        <StatTile
          label="Daily average"
          value={data ? (data.periodClicks / period).toFixed(data.periodClicks / period < 10 ? 1 : 0) : null}
          hint={`Over ${period} days`}
        />
        <StatTile label="Top source" value={data ? (topSource?.source ?? '—') : null} hint={topSource ? `${Math.round(topSource.share * 100)}% of clicks` : 'No clicks yet'} small />
      </section>

      {/* Trend */}
      <section className="mt-4 rounded-[24px] border border-line bg-surface p-5 sm:p-6" aria-labelledby="trend-heading">
        <div className="mb-4 flex items-baseline justify-between gap-4">
          <h2 id="trend-heading" className="font-medium text-ink">
            Clicks per day
          </h2>
          <button type="button" onClick={() => setShowTable((v) => !v)} className="text-[13px] font-medium text-muted hover:text-ink">
            {showTable ? 'Show chart' : 'Show table'}
          </button>
        </div>
        {!data ? (
          <div className="h-[220px] animate-pulse rounded-xl bg-surface-sunk" />
        ) : showTable ? (
          <DailyTable daily={data.daily} />
        ) : (
          <ClicksChart daily={data.daily} />
        )}
      </section>

      <div className="mt-6 grid gap-4 lg:grid-cols-[1.2fr_1fr]">
        <section aria-labelledby="insights-heading">
          <h2 id="insights-heading" className="mb-3 px-1 font-display text-2xl text-ink">
            Insights
          </h2>
          {data ? <InsightList insights={data.insights} /> : <div className="h-32 animate-pulse rounded-2xl bg-surface-sunk" />}
        </section>
        <section className="rounded-[24px] border border-line bg-surface p-5 sm:p-6" aria-labelledby="sources-heading">
          <h2 id="sources-heading" className="mb-5 font-medium text-ink">
            Top sources
          </h2>
          {data ? <ReferrerBars referrers={data.referrers} /> : <div className="h-32 animate-pulse rounded-xl bg-surface-sunk" />}
          <p className="mt-5 text-xs leading-relaxed text-muted">
            Only the referring site is recorded — never full URLs, IP addresses or browser details.
          </p>
        </section>

      </div>
    </div>
  );
}

function StatTile({ label, value, hint, hero = false, small = false }: { label: string; value: string | null; hint: string; hero?: boolean; small?: boolean }) {
  return (
    <div className="min-w-0 rounded-[20px] border border-line bg-surface p-4 sm:p-5">
      <p className="text-[13px] text-ink-soft">{label}</p>
      {value === null ? (
        <div className="mt-2 h-9 w-20 animate-pulse rounded-lg bg-surface-sunk" />
      ) : (
        <p
          className={`mt-1 font-semibold tracking-tight text-ink ${hero ? 'text-4xl sm:text-5xl' : small ? 'text-lg leading-tight [overflow-wrap:anywhere] sm:text-xl' : 'text-3xl sm:text-4xl'}`}
          title={value}
        >
          {small ? withDotBreaks(value) : value}
        </p>
      )}
      <p className="mt-1 truncate text-xs text-muted">{hint}</p>
    </div>
  );
}

/** Hostnames wrap after a dot ("news." / "ycombinator.com") instead of mid-word. */
function withDotBreaks(text: string) {
  return text.split('.').map((part, i, all) => (
    <span key={i}>
      {part}
      {i < all.length - 1 && (
        <>
          .<wbr />
        </>
      )}
    </span>
  ));
}

function DailyTable({ daily }: { daily: Analytics['daily'] }) {
  return (
    <div className="max-h-[220px] overflow-y-auto">
      <table className="w-full text-sm">
        <thead className="sticky top-0 bg-surface text-left text-xs text-muted">
          <tr>
            <th className="py-2 font-medium">Date (UTC)</th>
            <th className="py-2 text-right font-medium">Clicks</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {[...daily].reverse().map((d) => (
            <tr key={d.date}>
              <td className="py-1.5 text-ink-soft">{formatDay(d.date, true)}</td>
              <td className="py-1.5 text-right font-mono tabular-nums text-ink">{d.clicks}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
