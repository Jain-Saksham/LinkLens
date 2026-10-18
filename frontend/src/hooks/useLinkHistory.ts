import { useCallback, useEffect, useState } from 'react';
import { ApiError, getShortUrl, type ShortUrl } from '../api/client';

const STORAGE_KEY = 'linklens:history';
const MAX_ITEMS = 25;

function load(): ShortUrl[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as ShortUrl[]) : [];
  } catch {
    return [];
  }
}

function save(items: ShortUrl[]) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
  } catch {
    // Storage full or blocked (private mode): history just won't persist.
  }
}

/**
 * "Your links" lives in the browser. There are no accounts, so a server-side list would expose
 * everyone's links. Click counts are refreshed from the API.
 */
export function useLinkHistory() {
  const [items, setItems] = useState<ShortUrl[]>(load);
  const [refreshing, setRefreshing] = useState(false);

  useEffect(() => save(items), [items]);

  const add = useCallback((link: ShortUrl) => {
    setItems((prev) => [link, ...prev.filter((l) => l.shortCode !== link.shortCode)].slice(0, MAX_ITEMS));
  }, []);

  const remove = useCallback((shortCode: string) => {
    setItems((prev) => prev.filter((l) => l.shortCode !== shortCode));
  }, []);

  const clear = useCallback(() => setItems([]), []);

  const refresh = useCallback(async () => {
    setRefreshing(true);
    const current = load();
    const results = await Promise.allSettled(current.map((l) => getShortUrl(l.shortCode)));
    const fresh = new Map<string, ShortUrl>();
    const gone = new Set<string>();
    results.forEach((result, i) => {
      if (result.status === 'fulfilled') fresh.set(result.value.shortCode, result.value);
      else if (result.reason instanceof ApiError && result.reason.status === 404) gone.add(current[i].shortCode);
    });
    // Links deleted server-side (e.g. a wiped dev database) drop out; network failures keep stale data.
    setItems((prev) => prev.filter((l) => !gone.has(l.shortCode)).map((l) => fresh.get(l.shortCode) ?? l));
    setRefreshing(false);
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return { items, add, remove, clear, refresh, refreshing };
}
