# API contract

Base URL: `APP_BASE_URL` (default `http://localhost:8080`). JSON in and out. Timestamps are ISO-8601 instants in UTC.

## Errors

Every error is RFC 7807 `application/problem+json`:

```json
{ "type": "about:blank", "title": "Alias unavailable", "status": 409,
  "detail": "Alias 'docs' is not available", "instance": "/api/v1/urls" }
```

Validation errors add `errors`, a map from field to message:

```json
{ "title": "Validation failed", "status": 400, "detail": "One or more fields are invalid",
  "errors": { "url": "must be a valid http or https URL", "customAlias": "must be 3-32 characters: letters, digits, '-' or '_'" } }
```

| Status | When |
|---|---|
| 400 | Invalid body/params, malformed JSON, URL pointing at LinkLens itself, `days` out of range |
| 404 | Unknown short code |
| 409 | Custom alias taken or reserved (`api`, `actuator`, `health`, `admin`, `static`, `assets`) |
| 410 | Link has expired |
| 429 | Creation rate limit exceeded (with `Retry-After`) |
| 500 | Unexpected error (generic message; details only in server logs) |
| 503 | Database unavailable (with `Retry-After: 5`) |

## `POST /api/v1/urls`: create a short link

Rate limited per client IP: 20 burst, refilled at 20/min (configurable).

Request:

| Field | Type | Rules |
|---|---|---|
| `url` | string, required | absolute `http`/`https` URL with a host, ≤ 2048 chars, not a LinkLens URL |
| `customAlias` | string, optional | `^[A-Za-z0-9_-]{3,32}$`, not reserved, case-sensitive |
| `expiresAt` | instant, optional | must be in the future |

Response `201 Created`, `Location: /api/v1/urls/{code}`:

```json
{ "shortCode": "docs", "shortUrl": "http://localhost:8080/docs", "originalUrl": "https://spring.io",
  "customAlias": true, "clickCount": 0, "createdAt": "2026-10-18T10:00:00Z", "expiresAt": null }
```

Headers on every creation attempt: `X-RateLimit-Limit`, `X-RateLimit-Remaining`. On 429: `Retry-After: <seconds>`.

## `GET /{code}`: redirect

| Result | Response |
|---|---|
| Active link | `302 Found`, `Location: <originalUrl>`, `Cache-Control: no-store` |
| Unknown | `404` ProblemDetail |
| Expired | `410` ProblemDetail |

`code` must match `[A-Za-z0-9_-]{3,32}`; other paths are never treated as codes. A non-bot click is recorded with its timestamp and referring **host** only. Counts appear within ~200 ms.

## `GET /api/v1/urls/{code}`: link details

`200` with the same shape as the create response (`clickCount` is all-time), or 404.

## `GET /api/v1/urls/{code}/analytics?days=30`: analytics

`days`: integer 1–90, default 30. Days are **UTC** calendar days. The period includes today, which is still in progress.

```json
{
  "shortCode": "launch-post", "shortUrl": "http://localhost:8080/launch-post",
  "originalUrl": "https://blog.example.com/…", "createdAt": "…", "expiresAt": null,
  "totalClicks": 202, "periodDays": 30, "periodClicks": 202,
  "daily": [ { "date": "2026-09-19", "clicks": 5 }, … ],
  "referrers": [ { "source": "news.ycombinator.com", "clicks": 52, "share": 0.257 },
                 { "source": "Direct", "clicks": 49, "share": 0.243 }, … ],
  "insights": [ { "type": "PEAK_DAY", "severity": "NEUTRAL",
                  "title": "Busiest day: Fri, Oct 9", "message": "33 clicks (UTC)." } ],
  "generatedAt": "2026-10-18T10:00:00Z"
}
```

- `daily` has exactly `periodDays` entries, oldest first, with zero-click days included.
- `referrers` lists the top 8 sources. `Direct` means no usable referrer. `Other` is the remainder, if any. `share` is relative to `periodClicks`.
- `insights` holds 1–4 items. `severity` is `POSITIVE`, `NEUTRAL` or `WARNING`. `type` values: `NO_CLICKS`, `COLLECTING`, `TRAFFIC_UP`, `TRAFFIC_DOWN`, `NEW_TRAFFIC`, `INACTIVE`, `EXPIRING_WHILE_ACTIVE`, `DOMINANT_SOURCE`, `MOSTLY_DIRECT`, `PEAK_DAY`.

## `GET /actuator/health`

`{"status":"UP"}`. It reflects PostgreSQL only: Redis is optional and excluded on purpose. Metrics (`/actuator/metrics`) are exposed only when `MANAGEMENT_ENDPOINTS` includes `metrics`.
