import type { ShortUrl } from '../api/client';
import { displayUrl, formatCount, formatRelative, linkStatus } from '../lib/format';
import { CopyButton } from './CopyButton';
import { ArrowUpRightIcon, RefreshIcon, XIcon } from './icons';

interface Props {
  items: ShortUrl[];
  refreshing: boolean;
  highlight?: string;
  onRefresh: () => void;
  onRemove: (shortCode: string) => void;
  onClear: () => void;
}

export function RecentLinks({ items, refreshing, highlight, onRefresh, onRemove, onClear }: Props) {
  const totalClicks = items.reduce((sum, l) => sum + l.clickCount, 0);

  return (
    <section aria-labelledby="recent-heading">
      <div className="mb-4 flex items-end justify-between gap-4 px-1">
        <div>
          <h2 id="recent-heading" className="font-display text-3xl leading-none text-ink">
            Your links
          </h2>
          {items.length > 0 && (
            <p className="mt-2 text-sm text-muted">
              {items.length} {items.length === 1 ? 'link' : 'links'} · {formatCount(totalClicks)}{' '}
              {totalClicks === 1 ? 'click' : 'clicks'} total
            </p>
          )}
        </div>
        {items.length > 0 && (
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={onRefresh}
              disabled={refreshing}
              className="inline-flex h-9 items-center gap-1.5 rounded-full px-3 text-[13px] font-medium text-ink-soft transition hover:bg-surface hover:text-ink disabled:opacity-60"
            >
              <RefreshIcon className={refreshing ? 'animate-spin' : ''} width={14} height={14} />
              Refresh
            </button>
            <button
              type="button"
              onClick={() => {
                if (confirm('Clear your link history from this browser? The links keep working.')) onClear();
              }}
              className="inline-flex h-9 items-center rounded-full px-3 text-[13px] font-medium text-muted transition hover:bg-surface hover:text-ink"
            >
              Clear
            </button>
          </div>
        )}
      </div>

      {items.length === 0 ? (
        <div className="rounded-[28px] border border-dashed border-line-strong px-6 py-12 text-center">
          <div aria-hidden className="mx-auto mb-4 grid size-12 place-items-center rounded-full border border-line">
            <div className="size-4 rounded-full border-2 border-line-strong" />
          </div>
          <p className="text-sm text-ink-soft">Links you shorten will appear here.</p>
          <p className="mt-1 text-xs text-muted">Stored only in this browser — no account needed.</p>
        </div>
      ) : (
        <ul className="divide-y divide-line overflow-hidden rounded-[28px] border border-line bg-surface">
          {items.map((link) => (
            <LinkRow
              key={link.shortCode}
              link={link}
              highlighted={link.shortCode === highlight}
              onRemove={() => onRemove(link.shortCode)}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

function LinkRow({ link, highlighted, onRemove }: { link: ShortUrl; highlighted: boolean; onRemove: () => void }) {
  const status = linkStatus(link.expiresAt);
  const expired = status.kind === 'expired';

  return (
    <li
      className={`group grid grid-cols-[1fr_auto] items-center gap-x-4 gap-y-1 px-5 py-4 transition sm:grid-cols-[minmax(0,1fr)_auto_auto] sm:px-6 ${
        highlighted ? 'bg-accent-soft/50' : 'hover:bg-paper/50'
      }`}
    >
      <div className="min-w-0">
        <div className="flex min-w-0 items-center gap-2">
          <a
            href={link.shortUrl}
            target="_blank"
            rel="noreferrer"
            className={`truncate font-mono text-[15px] font-medium transition hover:text-accent ${expired ? 'text-muted line-through decoration-1' : 'text-ink'}`}
          >
            /{link.shortCode}
          </a>
          <StatusBadge status={status} />
        </div>
        <p className="mt-1 truncate text-[13px] text-muted" title={link.originalUrl}>
          {displayUrl(link.originalUrl)}
        </p>
      </div>

      {/* Clicks: right column on desktop, under the actions on mobile */}
      <div className="col-start-1 row-start-2 flex items-baseline gap-1.5 sm:col-start-2 sm:row-start-1 sm:block sm:text-right">
        <p className="font-mono text-sm tabular-nums text-ink sm:text-lg">{formatCount(link.clickCount)}</p>
        <p className="text-[11px] uppercase tracking-wider text-muted">
          {link.clickCount === 1 ? 'click' : 'clicks'}
          <span className="normal-case tracking-normal sm:hidden">
            {' · '}
            {status.kind === 'expiring' ? status.label : formatRelative(link.createdAt)}
          </span>
        </p>
      </div>

      <div className="col-start-2 row-span-2 row-start-1 flex items-center gap-0.5 sm:col-start-3 sm:row-span-1">
        <CopyButton text={link.shortUrl} />
        <a
          href={link.shortUrl}
          target="_blank"
          rel="noreferrer"
          aria-label={`Open ${link.shortUrl}`}
          title="Open"
          className="grid size-8 place-items-center rounded-full text-muted transition hover:bg-surface-sunk hover:text-ink"
        >
          <ArrowUpRightIcon />
        </a>
        <button
          type="button"
          onClick={onRemove}
          aria-label={`Remove ${link.shortCode} from history`}
          title="Remove from history"
          className="grid size-8 place-items-center rounded-full text-muted transition hover:bg-surface-sunk hover:text-ink sm:opacity-0 sm:group-hover:opacity-100 sm:focus-visible:opacity-100"
        >
          <XIcon width={14} height={14} />
        </button>
      </div>
    </li>
  );
}

function StatusBadge({ status }: { status: ReturnType<typeof linkStatus> }) {
  if (status.kind === 'expired') {
    return <span className="shrink-0 rounded-full bg-danger-soft px-2 py-0.5 text-[11px] font-medium text-danger">Expired</span>;
  }
  if (status.kind === 'expiring') {
    return (
      <span className="hidden shrink-0 rounded-full bg-warn-soft px-2 py-0.5 text-[11px] font-medium text-warn sm:inline">
        {status.label}
      </span>
    );
  }
  return null;
}
