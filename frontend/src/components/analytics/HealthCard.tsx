import { useState } from 'react';
import { ApiError, checkHealth, type HealthState, type LinkHealth } from '../../api/client';
import { displayUrl, formatRelative } from '../../lib/format';
import { RefreshIcon } from '../icons';

const BADGE: Record<HealthState, { label: string; icon: string; className: string }> = {
  HEALTHY: { label: 'Working', icon: '✓', className: 'bg-ok-soft text-ok' },
  WARNING: { label: 'Needs attention', icon: '!', className: 'bg-warn-soft text-warn' },
  BROKEN: { label: 'Broken', icon: '✕', className: 'bg-danger-soft text-danger' },
  PENDING: { label: 'Not checked yet', icon: '…', className: 'bg-surface-sunk text-ink-soft' },
};

export function HealthBadge({ state }: { state: HealthState }) {
  const b = BADGE[state];
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ${b.className}`}>
      <span aria-hidden className="font-mono">{b.icon}</span>
      {b.label}
    </span>
  );
}

interface Props {
  code: string;
  health: LinkHealth | null;
  onUpdate: (health: LinkHealth) => void;
}

/** "Is the destination still alive?": state, cause, what was found, and a manual re-check. */
export function HealthCard({ code, health, onUpdate }: Props) {
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function recheck() {
    setChecking(true);
    setError(null);
    try {
      onUpdate(await checkHealth(code));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Check failed.');
    } finally {
      setChecking(false);
    }
  }

  return (
    <section className="rounded-[24px] border border-line bg-surface p-5 sm:p-6" aria-labelledby="health-heading">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h2 id="health-heading" className="font-medium text-ink">
          Destination health
        </h2>
        <button
          type="button"
          onClick={() => void recheck()}
          disabled={checking}
          className="inline-flex h-8 items-center gap-1.5 rounded-full border border-line px-3 text-[13px] font-medium text-ink-soft transition hover:text-ink disabled:opacity-60"
        >
          <RefreshIcon width={13} height={13} className={checking ? 'animate-spin' : ''} />
          {checking ? 'Checking…' : 'Check now'}
        </button>
      </div>

      {!health ? (
        <div className="h-24 animate-pulse rounded-xl bg-surface-sunk" />
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-2">
            <HealthBadge state={health.state} />
            {health.finalUrl && (
              <span className={`rounded-full px-2.5 py-1 text-xs font-medium ${health.https ? 'bg-ok-soft text-ok' : 'bg-warn-soft text-warn'}`}>
                {health.https ? 'HTTPS' : 'Not encrypted'}
              </span>
            )}
          </div>
          <p className="mt-3 text-sm leading-relaxed text-ink-soft">{health.message}</p>

          <dl className="mt-4 space-y-2.5 text-[13px]">
            {health.title && <Row label="Page title" value={health.title} />}
            {health.finalUrl && <Row label="Final URL" value={displayUrl(health.finalUrl, 70)} title={health.finalUrl} mono />}
            {health.redirectChain.length > 1 && (
              <Row label="Redirects" value={`${health.redirectChain.length - 1} (${health.redirectChain.map((h) => h.status ?? '—').join(' → ')})`} mono />
            )}
            <Row label="Last checked" value={health.checkedAt ? formatRelative(health.checkedAt) : 'Not yet'} />
            {health.nextCheckAt && <Row label="Next check" value={formatRelative(health.nextCheckAt)} />}
          </dl>
          {error && <p className="mt-3 text-[13px] text-danger">{error}</p>}
        </>
      )}
    </section>
  );
}

function Row({ label, value, title, mono = false }: { label: string; value: string; title?: string; mono?: boolean }) {
  return (
    <div className="grid grid-cols-[96px_1fr] gap-3">
      <dt className="text-muted">{label}</dt>
      <dd className={`min-w-0 break-words text-ink ${mono ? 'font-mono text-xs leading-5' : ''}`} title={title}>
        {value}
      </dd>
    </div>
  );
}
