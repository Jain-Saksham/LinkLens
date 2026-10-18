# Interview Notes

Short answers to "why did you do it this way?"

## Short-code generation
- **Choice:** 7 random Base62 characters from `SecureRandom`. Check whether the code exists, retry up to 5 times on collision, and let the DB unique constraint act as the final guard.
- **Why:** 62⁷ ≈ 3.5 trillion codes, so collisions are rare until the table holds billions of rows. Random codes can't be enumerated.
- **Alternatives:**
  - *Base62 of the DB ID:* no collisions, but guessable (`/abc`, `/abd`, ...) and it leaks how many links exist.
  - *Hash of the URL, truncated:* deterministic, but truncation still collides, and the same URL always gets the same code (so links can't have their own expiry).
  - *Pre-generated key pool / Snowflake IDs:* worth it at very large scale, unnecessary here.
- **Follow-up:** "What about the check-then-insert race?" Two requests could pick the same code between the `exists` check and the `INSERT`. The unique constraint rejects the second one. `saveAndFlush` raises that violation inside the service, which turns it into a 409 rather than a 500 at commit.

## Redirect: 302, not 301
A 301 is cached permanently by browsers, so repeat visits never reach the server. That would make click counts wrong and expiry impossible to enforce. The trade-off is a little more server load. `Cache-Control: no-store` makes the intent explicit.

## Expired links return 410 Gone
404 means "never existed", 410 means "existed, now intentionally gone". Clients and crawlers treat them differently. Expiry is checked when the link is read, and nothing is deleted. A scheduled cleanup job (plus a partial index on `expires_at`) can be added if the table grows.

## Click counting
`UPDATE ... SET click_count = click_count + 1` runs atomically in the database. A load-modify-save in JPA would lose updates under concurrency (verified: 100 parallel redirects → +100). At high traffic, the next steps would be batching counts in memory or Redis, or writing click events to a queue. Both are deliberately out of scope.

## Schema & indexes
- The `UNIQUE (short_code)` constraint already creates the B-tree index that serves the hot path (redirect lookup), so adding a second index would be redundant.
- `GENERATED ALWAYS AS IDENTITY` is the SQL-standard replacement for `SERIAL`.
- `TIMESTAMPTZ` stores instants unambiguously. Java uses `Instant` end to end.
- `CHECK (click_count >= 0)` keeps an invariant at the DB level that application code can't bypass.
- Postgres compares `varchar` case-sensitively by default, which Base62 needs (`aB` ≠ `ab`).

## Flyway + `ddl-auto=validate`
Versioned SQL migrations give a reviewable, repeatable schema history. Hibernate only *validates* that the entity matches, so the app refuses to start if code and schema drift apart. `ddl-auto=update` is unsafe in production: it never drops or renames anything and can't migrate data.

## Validation layers
1. **Bean Validation on the DTO:** shape rules (`@NotBlank`, `@Size`, `@Pattern`, `@Future`, custom `@HttpUrl`). Invalid requests are rejected before reaching the service.
2. **Service:** rules that need context: reserved aliases, alias availability, and URLs pointing back at LinkLens (prevents redirect loops).
- `@HttpUrl` only allows `http`/`https` with a host. That blocks `javascript:`, `file:` and `ftp:` targets, which would be dangerous in a redirect. Hibernate's `@URL` accepts any scheme.

## Error handling
One `@RestControllerAdvice` maps domain exceptions to RFC 7807 `ProblemDetail`. Extending `ResponseEntityExceptionHandler` keeps Spring's own errors (bad JSON, 405, ...) in the same format. Domain exceptions are unchecked and HTTP-agnostic, so the service doesn't know about status codes.

## Layering & simplicity
- Controllers deal only with HTTP. The service owns transactions and rules. Repositories deal only with persistence.
- DTOs (records) stop the JPA entity from leaking into the API, so the schema and the API contract can change independently.
- **No `ShortUrlService` interface:** with only one implementation it would be pure ceremony. Mockito mocks concrete classes fine.
- **No Lombok:** records cover the DTOs, and the one entity has explicit getters. That's one less annotation processor to break on JDK upgrades.
- `open-in-view=false` keeps DB access inside service transactions, avoiding hidden lazy-loading queries during JSON rendering.

## Configuration
`@ConfigurationProperties` record + `@Validated`: typed, documented and fail-fast at startup. Values come from env vars ([12-factor](https://12factor.net/config)). The optional `.env` import is a local convenience, and real env vars override it.

## Testability
- An injected `Clock` makes expiry tests deterministic, with no sleeps.
- **Test pyramid:**
  - Pure unit tests for the generator and validator.
  - Mockito tests for service rules.
  - `@WebMvcTest` for HTTP status, header and JSON mapping.
  - One Testcontainers test for what mocks can't prove: the Flyway migration, JPA mapping, unique constraint and the atomic update against real Postgres. H2 was avoided because its SQL dialect differs.

## Frontend
- **Dev proxy instead of CORS:** Vite forwards `/api` to the backend, so the browser sees one origin, there are no preflight requests, and no backend URL is baked into the bundle. CORS is still configured on the backend for deployments where the UI and API live on different origins.
- **Validation on both sides:** the client mirrors the server's rules (http/https only, alias pattern, expiry in the future) for instant feedback. The server stays the source of truth. Server-only failures (409 alias taken, URL pointing at LinkLens) are mapped onto the field they concern, using the ProblemDetail `status` and `errors` map.
- **Forgiving input:** a bare `example.com` becomes `https://example.com`. The API stays strict and the UI is lenient.
- **History in `localStorage`, not on the server:** with no accounts, a "list all links" endpoint would expose everyone's links. Counts are refreshed with the existing per-code endpoint, and codes that now return 404 (e.g. after a DB reset) are dropped.
- **No UI or state libraries:** `useState` plus one custom hook cover the whole app, and the icons are inline SVG. Redux, React Query or a component kit would add more concepts than they remove at this size.
- **Logic out of components:** validation, expiry maths and formatting are pure functions in `lib/` with Vitest tests. Components are mostly layout.
- **Design tokens:** colors are CSS variables mapped into Tailwind with `@theme inline`, so dark mode is a single override block rather than `dark:` variants scattered through the markup.
- **Expiry is sent as an ISO instant:** the user picks a local time, and the browser converts it with the user's own timezone before sending. The server never has to guess the timezone.
