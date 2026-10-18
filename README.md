# LinkLens

An interview-focused URL shortener: short links, custom aliases, expiration, URL validation and click counts.

| Part | Stack | Status |
|---|---|---|
| `backend/` | Java 21, Spring Boot 3.5, Maven, PostgreSQL, Flyway | ✅ Phase 1 |
| `frontend/` | React 19, TypeScript, Vite 7, Tailwind 4 | ✅ Phase 2 |

## Prerequisites

- JDK 21+ (the build targets Java 21 bytecode via `--release 21`)
- PostgreSQL 14+
- Node.js 20.19+ (for the frontend)
- Docker (optional; only needed for the Testcontainers integration test, which is skipped without it)

Maven doesn't need to be installed: use the bundled `./mvnw`.

## Run the backend

```bash
createdb linklens                      # or: CREATE DATABASE linklens;
cd backend
cp -n .env.example .env                # first time only (-n never overwrites); then edit DB_USERNAME / DB_PASSWORD
./mvnw spring-boot:run
```

> **Homebrew PostgreSQL?** It has no `postgres` role (you'll see `FATAL: role "postgres" does not exist`). Set `DB_USERNAME` to your macOS username (`whoami`) and leave `DB_PASSWORD` empty.

Flyway creates the schema on startup. Check `http://localhost:8080/actuator/health` → `{"status":"UP"}`.

### Configuration

All settings come from environment variables. `backend/.env` is loaded if present, and real env vars override it.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/linklens` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / `postgres` | DB credentials |
| `SERVER_PORT` | `8080` | HTTP port |
| `APP_BASE_URL` | `http://localhost:8080` | Origin used to build returned short links |
| `SHORT_CODE_LENGTH` | `7` | Generated code length (5–16) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Comma-separated frontend origins |

## Run the frontend

With the backend running, in a second terminal:

```bash
cd frontend
npm install
npm run dev                            # http://localhost:5173
```

The Vite dev server proxies `/api` to `http://localhost:8080`, so no extra config is needed locally. Optional settings are in [frontend/.env.example](frontend/.env.example) (copy to `.env.local`).

The UI lets you:
- Shorten a link, optionally with a custom alias and an expiry (presets or a custom date).
- Copy or open the result.
- See "Your links" with live click counts and expiry status.

The history is kept in the browser's `localStorage` because there are no accounts. The UI follows your system's light/dark setting and works down to phone width.

## Test

```bash
cd backend && ./mvnw test              # JUnit/Mockito, @WebMvcTest slices, Testcontainers integration test
cd frontend && npm test                # Vitest: URL/alias validation, expiry, formatting
cd frontend && npm run build           # type-check + production build
```

The backend integration test runs against a real PostgreSQL via Testcontainers and is skipped when Docker isn't running.

## API

| Method | Path | Success | Errors |
|---|---|---|---|
| `POST` | `/api/v1/urls` | `201` + link details | `400` invalid input, `409` alias taken/reserved |
| `GET` | `/api/v1/urls/{code}` | `200` link details incl. `clickCount` | `404` |
| `GET` | `/{code}` | `302` to original URL, counts a click | `404` unknown, `410` expired |

```bash
curl -X POST localhost:8080/api/v1/urls -H 'Content-Type: application/json' \
  -d '{"url":"https://spring.io","customAlias":"spring","expiresAt":"2030-01-01T00:00:00Z"}'
```

```json
{"shortCode":"spring","shortUrl":"http://localhost:8080/spring","originalUrl":"https://spring.io",
 "customAlias":true,"clickCount":0,"createdAt":"2026-10-18T08:36:41.99Z","expiresAt":"2030-01-01T00:00:00Z"}
```

`customAlias` and `expiresAt` (ISO-8601 instant, must be in the future) are optional. Errors use RFC 7807 `application/problem+json`. Validation errors add an `errors` map from field to message.

## Project layout

```
backend/src/main/java/com/linklens/
  controller/   HTTP only: ShortUrlController (API), RedirectController (/{code})
  service/      Business rules: ShortUrlService, ShortCodeGenerator
  repository/   Spring Data JPA
  domain/       JPA entity
  dto/          Request/response records
  validation/   @HttpUrl Bean Validation constraint
  exception/    Domain exceptions + GlobalExceptionHandler
  config/       AppProperties (typed config), CORS, Clock
backend/src/main/resources/db/migration/   Flyway SQL migrations

frontend/src/
  api/client.ts         Typed fetch wrapper; maps ProblemDetail → ApiError (status + field errors)
  lib/                  Pure, unit-tested logic: validation, expiry presets, formatting
  hooks/useLinkHistory  localStorage history + click-count refresh
  components/           ShortenForm, ResultCard, RecentLinks, CopyButton, icons
  index.css             Design tokens (light/dark) exposed to Tailwind via @theme
```

See [docs/interview-notes.md](docs/interview-notes.md) for the reasoning behind each design choice.
