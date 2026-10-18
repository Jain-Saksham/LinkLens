import { type MouseEvent, useEffect, useState } from 'react';

/** Two routes don't justify a routing library: the History API and one hook are enough. */
export type Route = { name: 'home' } | { name: 'analytics'; code: string } | { name: 'not-found' };

export function parseRoute(pathname: string): Route {
  if (pathname === '/' || pathname === '') return { name: 'home' };
  const match = /^\/links\/([A-Za-z0-9_-]{3,32})\/?$/.exec(pathname);
  if (match) return { name: 'analytics', code: match[1] };
  return { name: 'not-found' };
}

export function analyticsPath(code: string): string {
  return `/links/${encodeURIComponent(code)}`;
}

export function navigate(path: string) {
  window.history.pushState(null, '', path);
  window.dispatchEvent(new PopStateEvent('popstate'));
  window.scrollTo({ top: 0 });
}

/** Click handler for in-app links: keeps cmd/ctrl-click opening a new tab. */
export function onLinkClick(path: string) {
  return (e: MouseEvent) => {
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
    e.preventDefault();
    navigate(path);
  };
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.pathname));
  useEffect(() => {
    const update = () => setRoute(parseRoute(window.location.pathname));
    window.addEventListener('popstate', update);
    return () => window.removeEventListener('popstate', update);
  }, []);
  return route;
}
