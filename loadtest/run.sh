#!/usr/bin/env bash
# LinkLens load test (ApacheBench). Reproducible: same scenarios, request counts and concurrency
# on every run; results are appended to results/<label>-<timestamp>.md.
#
#   ./run.sh [label] [base-url]          e.g. ./run.sh cache-on http://localhost:8080
#
# Start the backend with RATE_LIMIT_ENABLED=false (creation is otherwise capped at 20/min per IP).
# For a cache-off comparison, restart it with CACHE_ENABLED=false and run again with another label.
#
# Each scenario runs twice:
#   c=1  : one request at a time, i.e. latency without queueing (the "how fast is one request" number)
#   c=32 : 32 concurrent keep-alive connections, i.e. throughput under load
# A discarded warmup precedes every measurement (JIT, connection pools, caches).
set -euo pipefail

LABEL="${1:-run}"
BASE="${2:-http://127.0.0.1:8080}"
N="${N:-20000}"            # requests per measured c=32 run
N_SERIAL="${N_SERIAL:-3000}"  # requests per measured c=1 run
WARMUP="${WARMUP:-3000}"

command -v ab >/dev/null || { echo "ApacheBench (ab) is required: macOS has it; Linux: apt install apache2-utils"; exit 1; }
curl -sf "$BASE/actuator/health" >/dev/null || { echo "Backend not reachable at $BASE"; exit 1; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
echo '{"url":"https://example.com/loadtest/article?utm_source=bench"}' > "$TMP/create.json"

if curl -s -D - -o /dev/null -H 'Content-Type: application/json' -d @"$TMP/create.json" "$BASE/api/v1/urls" \
    | grep -qi '^x-ratelimit-limit'; then
  echo "Rate limiting is enabled; restart the backend with RATE_LIMIT_ENABLED=false"; exit 1
fi

new_link() {
  curl -s -H 'Content-Type: application/json' -d @"$TMP/create.json" "$BASE/api/v1/urls" \
    | sed -E 's/.*"shortCode":"([^"]+)".*/\1/'
}
HOT="$(new_link)"
STATS="$(new_link)"
# Give the analytics link some history so its queries do real work.
ab -q -k -c 8 -n 2000 "$BASE/$STATS" >/dev/null

# Prints one markdown table row from ab's output.
measure() {
  local name="$1" conc="$2" n="$3"; shift 3
  ab -q -k -c "$conc" -n "$WARMUP" "$@" >/dev/null 2>&1
  local out; out="$(ab -k -c "$conc" -n "$n" "$@" 2>&1)"
  local rps mean p50 p90 p99 max failed
  rps=$(awk '/Requests per second/ {printf "%d", $4}' <<<"$out")
  mean=$(awk '/Time per request/ && /\(mean\)$/ {print $4; exit}' <<<"$out")
  p50=$(awk '$1=="50%" {print $2}' <<<"$out"); p90=$(awk '$1=="90%" {print $2}' <<<"$out")
  p99=$(awk '$1=="99%" {print $2}' <<<"$out"); max=$(awk '$1=="100%" {print $2}' <<<"$out")
  # ab's "Failed requests" also counts responses whose body length differs from the first one
  # (timestamps and codes vary), so only connect/receive/exception failures are real errors.
  failed=$(awk -F'[:,)]' '/\(Connect:/ {print $2+$4+$8}' <<<"$out"); failed=${failed:-0}
  printf '| %-28s | %3s | %7s | %8s | %4s | %4s | %4s | %5s | %6s |\n' "$name" "$conc" "$rps" "$mean" "$p50" "$p90" "$p99" "$max" "$failed"
}

dropped_count() {
  curl -s "$BASE/actuator/metrics/linklens.clicks.dropped" | sed -nE 's/.*"value":([0-9.E]+).*/\1/p' | awk '{printf "%d", $1}'
}
DROPPED_BEFORE="$(dropped_count)"

mkdir -p "$(dirname "$0")/results"
OUT="$(dirname "$0")/results/${LABEL}-$(date -u +%Y%m%dT%H%M%SZ).md"
{
  echo "# LinkLens load test: $LABEL"
  echo
  echo "- Date (UTC): $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "- Machine: $(sysctl -n machdep.cpu.brand_string 2>/dev/null || grep -m1 'model name' /proc/cpuinfo | cut -d: -f2), $(getconf _NPROCESSORS_ONLN) cores"
  echo "- Target: $BASE (load generator on the same machine)"
  echo "- Requests: $N per c=32 run, $N_SERIAL per c=1 run, $WARMUP warmup each"
  echo
  echo "Latencies in ms (ab reports percentiles in whole ms; mean is per request at that concurrency)."
  echo
  echo "| Scenario                     |   c |   req/s | mean ms  |  p50 |  p90 |  p99 |   max | errors |"
  echo "|------------------------------|-----|---------|----------|------|------|------|-------|--------|"
  for c in 1 32; do
    n=$([ "$c" = 1 ] && echo "$N_SERIAL" || echo "$N")
    measure "redirect (302)" "$c" "$n" "$BASE/$HOT"
    measure "unknown code (404)" "$c" "$n" "$BASE/does-not-exist-123"
    measure "create link (POST, 201)" "$c" "$n" -p "$TMP/create.json" -T application/json "$BASE/api/v1/urls"
    measure "analytics 30d (200)" "$c" "$n" "$BASE/api/v1/urls/$STATS/analytics?days=30"
  done
} | tee "$OUT"

sleep 2 # let the click flusher drain
DROPPED_AFTER="$(dropped_count)"
echo
if [ -n "$DROPPED_BEFORE" ] && [ -n "$DROPPED_AFTER" ]; then
  echo "Click events dropped during this run (buffer full): $((DROPPED_AFTER - DROPPED_BEFORE))" | tee -a "$OUT"
else
  echo "Click events dropped: n/a (start the backend with MANAGEMENT_ENDPOINTS=health,metrics)" | tee -a "$OUT"
fi
echo "Saved $OUT"
