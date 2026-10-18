import { useState } from 'react';
import type { ShortUrl } from './api/client';
import { ApertureLogo } from './components/icons';
import { RecentLinks } from './components/RecentLinks';
import { ResultCard } from './components/ResultCard';
import { ShortenForm } from './components/ShortenForm';
import { useLinkHistory } from './hooks/useLinkHistory';

export default function App() {
  const history = useLinkHistory();
  const [latest, setLatest] = useState<ShortUrl | null>(null);

  function handleCreated(link: ShortUrl) {
    setLatest(link);
    history.add(link);
  }

  return (
    <div className="relative min-h-dvh overflow-x-hidden">
      <div aria-hidden className="paper-grid pointer-events-none absolute inset-x-0 top-0 h-[560px]" />
      {/* Soft accent glow behind the hero */}
      <div
        aria-hidden
        className="pointer-events-none absolute left-1/2 top-[-180px] size-[520px] -translate-x-1/2 rounded-full bg-accent opacity-[0.07] blur-3xl"
      />

      <div className="relative mx-auto flex min-h-dvh max-w-3xl flex-col px-4 sm:px-6">
        <header className="flex items-center justify-between py-6">
          <a href="/" className="flex items-center gap-2.5" aria-label="LinkLens home">
            <ApertureLogo />
            <span className="text-[17px] font-semibold tracking-tight text-ink">LinkLens</span>
          </a>
          <span className="rounded-full border border-line bg-surface/70 px-3 py-1 font-mono text-[11px] text-muted backdrop-blur">
            v0.1
          </span>
        </header>

        <main className="flex-1 pb-16">
          <section className="pb-10 pt-10 text-center sm:pb-12 sm:pt-16">
            <h1 className="font-display text-[52px] leading-[0.95] tracking-tight text-ink sm:text-[76px]">
              Long links,
              <br />
              <em className="text-accent">brought into focus.</em>
            </h1>
            <p className="mx-auto mt-5 max-w-md text-[15px] leading-relaxed text-ink-soft sm:text-base">
              Shorten any URL in a keystroke. Pick your own alias, set it to expire, and see how often it’s
              clicked.
            </p>
          </section>

          <div className="space-y-4">
            <ShortenForm onCreated={handleCreated} />
            {latest && <ResultCard key={latest.shortCode} link={latest} onDismiss={() => setLatest(null)} />}
          </div>

          <div className="mt-16">
            <RecentLinks
              items={history.items}
              refreshing={history.refreshing}
              highlight={latest?.shortCode}
              onRefresh={history.refresh}
              onRemove={history.remove}
              onClear={history.clear}
            />
          </div>
        </main>

        <footer className="flex flex-col items-center justify-between gap-2 border-t border-line py-6 text-xs text-muted sm:flex-row">
          <span>LinkLens — a small, focused URL shortener.</span>
          <span className="font-mono">Spring Boot · PostgreSQL · React</span>
        </footer>
      </div>
    </div>
  );
}
