#!/usr/bin/env bash
# Run the read scenario at each VU level and collect k6 summary + DB/app evidence.
# Usage: ./run.sh <label> [duration] [vus...]   e.g. ./run.sh baseline 60s 1 5 10 25 50
set -euo pipefail
cd "$(dirname "$0")"
LABEL=$1; DUR=${2:-60s}; shift 2 || true; VUS_LIST=${*:-1 5 10 25 50}
OUT=../docs/measurements/loadtest-raw/$LABEL; mkdir -p "$OUT"
PROM=http://localhost:9090/api/v1/query

{ echo "date: $(date -u +%FT%TZ)"; echo "git: $(git rev-parse --short HEAD) $(git diff --quiet || echo '(dirty)')";
  echo "hikari_max: ${HIKARI_MAX:-10}"; docker compose ps --format '{{.Name}} {{.Image}}';
  docker inspect -f '{{.Name}} cpus={{.HostConfig.NanoCpus}} mem={{.HostConfig.Memory}}' scom-loadtest-app-1 scom-loadtest-db-1;
  ./psql.sh -Atc "select version()"; ./psql.sh -Atc "select indexname||': '||indexdef from pg_indexes where tablename='products'"; } > "$OUT/env.txt"

# warm-up (discarded)
k6 run -q -e VUS=5 -e DURATION=15s read.js > /dev/null

for V in $VUS_LIST; do
  ./psql.sh -qAtc "select pg_stat_statements_reset()" >/dev/null
  (while true; do docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' scom-loadtest-app-1 scom-loadtest-db-1 | sed "s/^/$(date +%T),/"; sleep 4; done) > "$OUT/stats_vus$V.csv" 2>/dev/null &
  SP=$!
  START=$(date +%s)
  k6 run -q -e VUS=$V -e DURATION=$DUR -e OUT="$OUT/k6_vus$V.json" read.js | tee "$OUT/k6_vus$V.txt"
  SECS=$(( $(date +%s) - START ))
  kill $SP; wait $SP 2>/dev/null || true
  ./psql.sh -c "select calls, round(total_exec_time::numeric) total_ms, round(mean_exec_time::numeric,2) mean_ms, rows, left(regexp_replace(query,'\s+',' ','g'),200) query from pg_stat_statements where query not like '%pg_stat_statements%' order by total_exec_time desc limit 15" > "$OUT/pgss_vus$V.txt"
  for q in "max_over_time(hikaricp_connections_pending[${SECS}s])" "max_over_time(hikaricp_connections_active[${SECS}s])" \
           "avg_over_time(process_cpu_usage[${SECS}s])" "increase(jvm_gc_pause_seconds_sum[${SECS}s])" \
           "max_over_time(tomcat_threads_busy_threads[${SECS}s])"; do
    echo "$q = $(curl -s --data-urlencode "query=$q" $PROM | python3 -c 'import json,sys; r=json.load(sys.stdin)["data"]["result"]; print([x["value"][1] for x in r])')"
  done > "$OUT/prom_vus$V.txt"
  cat "$OUT/prom_vus$V.txt"
done
