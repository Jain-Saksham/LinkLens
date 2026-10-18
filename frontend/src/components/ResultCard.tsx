import { previewUrl, type ShortUrl } from '../api/client';
import { useLiveClicks } from '../hooks/useLiveClicks';
import { LiveDot } from './LiveDot';
import { displayUrl, formatRelative } from '../lib/format';
import { CopyButton } from './CopyButton';
import { analyticsPath, onLinkClick } from '../lib/router';
import { ArrowUpRightIcon, ChartIcon, CheckIcon, XIcon } from './icons';

interface Props {
  link: ShortUrl;
  onDismiss: () => void;
}

export function ResultCard({ link, onDismiss }: Props) {
  const { protocol, host } = new URL(link.shortUrl);
  // The "wow" moment: share the link and watch the count move.
  const live = useLiveClicks(link.shortCode);
  const clicks = live.count ?? link.clickCount;

  return (
    <section
      aria-live="polite"
      className="relative animate-rise overflow-hidden rounded-[28px] border border-line bg-surface p-6 sm:p-8"
    >
      {/* Concentric "lens" rings in the corner */}
      <div aria-hidden className="pointer-events-none absolute -right-16 -top-16 size-56 rounded-full border border-line" />
      <div aria-hidden className="pointer-events-none absolute -right-6 -top-6 size-36 rounded-full border border-line" />

      <button
        type="button"
        onClick={onDismiss}
        aria-label="Dismiss"
        className="absolute right-4 top-4 z-10 grid size-8 place-items-center rounded-full text-muted transition hover:bg-surface-sunk hover:text-ink"
      >
        <XIcon />
      </button>

      <div className="relative">
        <p className="mb-4 inline-flex items-center gap-1.5 rounded-full bg-ok-soft px-2.5 py-1 text-xs font-medium text-ok">
          <CheckIcon width={13} height={13} strokeWidth={2.4} />
          Link ready
        </p>

        <a
          href={link.shortUrl}
          target="_blank"
          rel="noreferrer"
          className="block font-mono text-2xl tracking-tight text-ink transition hover:text-accent sm:text-[32px] sm:leading-tight"
        >
          {/* <wbr> lets long links wrap after the slash instead of mid-code */}
          <span className="text-muted">{protocol}//{host}/</span>
          <wbr />
          <span className="[overflow-wrap:anywhere]">{link.shortCode}</span>
        </a>

        <p className="mt-3 flex min-w-0 items-center gap-2 text-sm text-muted">
          <span className="shrink-0">→</span>
          <span className="truncate" title={link.originalUrl}>
            {displayUrl(link.originalUrl, 80)}
          </span>
        </p>

        <div className="mt-6 flex flex-wrap items-center gap-2">
          <CopyButton text={link.shortUrl} variant="solid" label="Copy link" />
          <a
            href={link.shortUrl}
            target="_blank"
            rel="noreferrer"
            className="inline-flex h-11 items-center gap-2 rounded-full border border-line px-5 text-sm font-medium text-ink transition hover:border-line-strong"
          >
            Open
            <ArrowUpRightIcon />
          </a>
          <a
            href={analyticsPath(link.shortCode)}
            onClick={onLinkClick(analyticsPath(link.shortCode))}
            className="inline-flex h-11 items-center gap-2 rounded-full px-4 text-sm font-medium text-ink-soft transition hover:text-ink"
          >
            <ChartIcon />
            Analytics
          </a>
          <a
            href={previewUrl(link.shortUrl)}
            target="_blank"
            rel="noreferrer"
            className="inline-flex h-11 items-center rounded-full px-2 text-sm font-medium text-ink-soft transition hover:text-ink"
          >
            Preview
          </a>
          <span className="ml-auto flex items-center gap-3 text-xs text-muted">
            <span className="inline-flex items-center gap-2 font-mono text-ink" aria-live="polite">
              {clicks} {clicks === 1 ? 'click' : 'clicks'}
              {live.live && <LiveDot />}
            </span>
            <span>{link.expiresAt ? `Expires ${formatRelative(link.expiresAt)}` : 'Never expires'}</span>
          </span>
        </div>
      </div>
    </section>
  );
}
