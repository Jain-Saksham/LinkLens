import { ApertureLogo } from './components/icons';
import { onLinkClick, useRoute } from './lib/router';
import { AnalyticsPage } from './pages/AnalyticsPage';
import { HomePage } from './pages/HomePage';

export default function App() {
  const route = useRoute();
  const wide = route.name === 'analytics';

  return (
    <div className="relative min-h-dvh overflow-x-hidden">
      <div aria-hidden className="paper-grid pointer-events-none absolute inset-x-0 top-0 h-[560px]" />
      {/* Soft accent glow behind the hero */}
      <div
        aria-hidden
        className="pointer-events-none absolute left-1/2 top-[-180px] size-[520px] -translate-x-1/2 rounded-full bg-accent opacity-[0.07] blur-3xl"
      />

      <div className={`relative mx-auto flex min-h-dvh flex-col px-4 sm:px-6 ${wide ? 'max-w-5xl' : 'max-w-3xl'}`}>
        <header className="flex items-center justify-between py-6">
          <a href="/" onClick={onLinkClick('/')} className="flex items-center gap-2.5" aria-label="LinkLens home">
            <ApertureLogo />
            <span className="text-[17px] font-semibold tracking-tight text-ink">LinkLens</span>
          </a>
          <span className="rounded-full border border-line bg-surface/70 px-3 py-1 font-mono text-[11px] text-muted backdrop-blur">
            v0.2
          </span>
        </header>

        <main className="flex-1">
          {route.name === 'home' && <HomePage />}
          {route.name === 'analytics' && <AnalyticsPage key={route.code} code={route.code} />}
          {route.name === 'not-found' && (
            <div className="py-24 text-center">
              <p className="font-display text-5xl text-ink">Page not found</p>
              <a href="/" onClick={onLinkClick('/')} className="mt-6 inline-block text-sm font-medium text-accent hover:underline">
                ← Back home
              </a>
            </div>
          )}
        </main>

        <footer className="flex flex-col items-center justify-between gap-2 border-t border-line py-6 text-xs text-muted sm:flex-row">
          <span>LinkLens — a small, focused URL shortener.</span>
          <span className="font-mono">Spring Boot · PostgreSQL · Redis · React</span>
        </footer>
      </div>
    </div>
  );
}
