#!/usr/bin/env bash
# End-to-end smoke test against a running LinkLens (local or deployed).
#
#   scripts/smoke-test.sh <api-base-url> [frontend-url]
#   e.g. scripts/smoke-test.sh https://linklens.onrender.com https://linklens-app.onrender.com
#
# Creates real links (prefixed "smoke-"), so run it against environments where that's fine.
# Exits non-zero on the first failed check.
set -uo pipefail

API="${1:?usage: smoke-test.sh <api-base-url> [frontend-url]}"; API="${API%/}"
APP="${2:-}"; APP="${APP%/}"
RUN="smoke-$(date +%s)"
PASS=0
H='Content-Type: application/json'

ok()   { PASS=$((PASS + 1)); printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗ %s\033[0m\n' "$1"; [ -n "${2:-}" ] && printf '    %s\n' "$2"; exit 1; }
status() { curl -s -o /dev/null -w '%{http_code}' --max-time 90 "$@"; }
expect() { # name, expected, actual
  [ "$2" = "$3" ] && ok "$1" || fail "$1 (expected $2, got $3)"
}

echo "LinkLens smoke test → $API"

# Free hosts sleep: the first request may take ~1 minute while the service wakes.
start=$(date +%s)
code=$(status "$API/actuator/health/liveness")
expect "liveness probe answers (woke in $(( $(date +%s) - start ))s)" 200 "$code"
expect "readiness (database reachable)" 200 "$(status "$API/actuator/health/readiness")"

# Create
body=$(curl -s --max-time 30 -H "$H" -d "{\"url\":\"https://example.com/?smoke=$RUN\",\"customAlias\":\"$RUN\"}" "$API/api/v1/urls")
echo "$body" | grep -q "\"shortCode\":\"$RUN\"" && ok "create link with custom alias" || fail "create link" "$body"
echo "$body" | grep -q "\"shortUrl\":\"https://" && ok "short URL is HTTPS" || echo "    (note: short URL is not https: $body)"

# Redirect
loc=$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' --max-time 30 "$API/$RUN")
expect "redirect 302 to the destination" "302 https://example.com/?smoke=$RUN" "$loc"

# Validation and errors.
# JSON bodies live in variables: written inline inside $( ), bash would brace-expand {"a":1,"b":2}
# at the comma and send only half of it.
dup_body="{\"url\":\"https://example.com\",\"customAlias\":\"$RUN\"}"
expect "duplicate alias → 409" 409 "$(status -H "$H" -d "$dup_body" "$API/api/v1/urls")"
expect "invalid URL → 400" 400 "$(status -H "$H" -d '{"url":"javascript:alert(1)"}' "$API/api/v1/urls")"
expect "unknown code → 404" 404 "$(status "$API/$RUN-missing")"
expect "bad analytics period → 400" 400 "$(status "$API/api/v1/urls/$RUN/analytics?days=0")"

# Expiry: a link that expires in 5 seconds stops redirecting.
exp_at=$(python3 -c 'import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(seconds=5)).strftime("%Y-%m-%dT%H:%M:%SZ"))')
exp_body="{\"url\":\"https://example.com/exp\",\"customAlias\":\"$RUN-exp\",\"expiresAt\":\"$exp_at\"}"
expect "create expiring link" 201 "$(status -H "$H" -d "$exp_body" "$API/api/v1/urls")"
expect "expiring link redirects before expiry" 302 "$(status "$API/$RUN-exp")"
sleep 7
expect "expired link → 410" 410 "$(status "$API/$RUN-exp")"

# Analytics (clicks are written in batches every ~200 ms)
sleep 2
analytics=$(curl -s --max-time 30 "$API/api/v1/urls/$RUN/analytics?days=7")
echo "$analytics" | grep -q '"totalClicks":1' && ok "analytics counted the click" || fail "analytics" "$analytics"
echo "$analytics" | grep -q '"insights":\[{' && ok "analytics returned insights" || fail "insights" "$analytics"

# Health + preview
expect "health endpoint" 200 "$(status "$API/api/v1/urls/$RUN/health")"
preview=$(curl -s --max-time 30 -D - "$API/$RUN+")
echo "$preview" | grep -qi "content-security-policy: default-src 'none'" && ok "preview page served with CSP" || fail "preview CSP"
echo "$preview" | grep -q "example.com" && ok "preview shows the destination" || fail "preview content"

# Live stream sends the current count immediately
first=$(curl -s -N --max-time 5 "$API/api/v1/urls/$RUN/clicks/stream" | grep -m1 '^data:' || true)
echo "$first" | grep -q '"clickCount"' && ok "live click stream (SSE)" || fail "SSE stream" "$first"

# Persistence: read the link back through the details endpoint (database, not cache).
expect "link persisted" 200 "$(status "$API/api/v1/urls/$RUN")"

# CORS + frontend
if [ -n "$APP" ]; then
  cors=$(curl -s -o /dev/null -D - -X OPTIONS -H "Origin: $APP" -H 'Access-Control-Request-Method: POST' "$API/api/v1/urls" | tr -d '\r' | grep -i '^access-control-allow-origin:' || true)
  echo "$cors" | grep -qi "$APP" && ok "CORS allows the frontend origin" || fail "CORS" "$cors"
  expect "frontend serves the app shell" 200 "$(status "$APP/")"
  expect "frontend SPA route (/links/$RUN) falls back to the app" 200 "$(status "$APP/links/$RUN")"
fi

echo "All $PASS checks passed. Test links created: $RUN, $RUN-exp"
