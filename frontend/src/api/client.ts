export interface ShortUrl {
  shortCode: string;
  shortUrl: string;
  originalUrl: string;
  customAlias: boolean;
  clickCount: number;
  createdAt: string;
  expiresAt: string | null;
}

export interface CreateShortUrlRequest {
  url: string;
  customAlias?: string;
  expiresAt?: string;
}

/** Mirrors the backend's RFC 7807 ProblemDetail body. */
interface ProblemDetail {
  title?: string;
  detail?: string;
  status?: number;
  errors?: Record<string, string>;
}

export class ApiError extends Error {
  readonly status: number;
  readonly fieldErrors: Record<string, string>;

  constructor(status: number, message: string, fieldErrors: Record<string, string> = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '');

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      ...init,
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...init?.headers },
    });
  } catch {
    throw new ApiError(0, "Can't reach the LinkLens API. Is the backend running?");
  }

  if (!response.ok) {
    const problem: ProblemDetail = await response.json().catch(() => ({}));
    throw new ApiError(
      response.status,
      problem.detail ?? problem.title ?? `Request failed (${response.status})`,
      problem.errors,
    );
  }
  return response.json() as Promise<T>;
}

export function createShortUrl(body: CreateShortUrlRequest): Promise<ShortUrl> {
  return request<ShortUrl>('/api/v1/urls', { method: 'POST', body: JSON.stringify(body) });
}

export function getShortUrl(shortCode: string): Promise<ShortUrl> {
  return request<ShortUrl>(`/api/v1/urls/${encodeURIComponent(shortCode)}`);
}

export type InsightSeverity = 'POSITIVE' | 'NEUTRAL' | 'WARNING';

export interface Analytics {
  shortCode: string;
  shortUrl: string;
  originalUrl: string;
  createdAt: string;
  expiresAt: string | null;
  totalClicks: number;
  periodDays: number;
  periodClicks: number;
  /** One entry per UTC day, oldest first, zero-filled. `date` is yyyy-MM-dd. */
  daily: { date: string; clicks: number }[];
  /** "Direct" = no usable referrer; "Other" = everything outside the top sources. */
  referrers: { source: string; clicks: number; share: number }[];
  insights: { type: string; severity: InsightSeverity; title: string; message: string }[];
  generatedAt: string;
}

export function getAnalytics(shortCode: string, days: number): Promise<Analytics> {
  return request<Analytics>(`/api/v1/urls/${encodeURIComponent(shortCode)}/analytics?days=${days}`);
}
