import { useState } from 'react';
import type { ShortUrl } from '../api/client';
import { RecentLinks } from '../components/RecentLinks';
import { ResultCard } from '../components/ResultCard';
import { ShortenForm } from '../components/ShortenForm';
import { useLinkHistory } from '../hooks/useLinkHistory';

export function HomePage() {
  const history = useLinkHistory();
  const [latest, setLatest] = useState<ShortUrl | null>(null);

  function handleCreated(link: ShortUrl) {
    setLatest(link);
    history.add(link);
  }

  return (
    <>
      <section className="pb-10 pt-10 text-center sm:pb-12 sm:pt-16">
        <h1 className="font-display text-[52px] leading-[0.95] tracking-tight text-ink sm:text-[76px]">
          Long links,
          <br />
          <em className="text-accent">brought into focus.</em>
        </h1>
        <p className="mx-auto mt-5 max-w-md text-[15px] leading-relaxed text-ink-soft sm:text-base">
          Shorten any URL in a keystroke. Pick your own alias, set it to expire, and see who’s clicking.
        </p>
      </section>

      <div className="space-y-4">
        <ShortenForm onCreated={handleCreated} />
        {latest && <ResultCard key={latest.shortCode} link={latest} onDismiss={() => setLatest(null)} />}
      </div>

      <div className="mt-16 pb-16">
        <RecentLinks
          items={history.items}
          refreshing={history.refreshing}
          highlight={latest?.shortCode}
          onRefresh={history.refresh}
          onRemove={history.remove}
          onClear={history.clear}
        />
      </div>
    </>
  );
}
