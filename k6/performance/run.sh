#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RESULTS_BASE="$ROOT/k6/results"

env_value() { sed -n "s/^$1=//p" "$ROOT/.env" | tail -1; }
export JWT_SECRET="${JWT_SECRET:-$(env_value JWT_SECRET)}"
export VNPAY_TMN_CODE="${VNPAY_TMN_CODE:-$(env_value VNPAY_TMN_CODE)}"
export VNPAY_HASH_SECRET="${VNPAY_HASH_SECRET:-$(env_value VNPAY_HASH_SECRET)}"

QUEUES_REGEX='^(order-payment-status-queue|catalog-order-created-queue|cart-order-confirmed-queue|order-stock-result-queue|payment-order-cancelled-queue|catalog-stock-release-queue|catalog-order-delivered-queue|notification-order-confirmed-queue)$'

sql() { docker exec -i kyro-postgres psql -U postgres -d postgres < "$1"; }
redis_cleanup() {
  docker exec kyro-redis redis-cli --raw EVAL \
    "local c='0' repeat local r=redis.call('SCAN',c,'MATCH','cart:*','COUNT',1000); c=r[1]; for _,k in ipairs(r[2]) do local n=tonumber(string.sub(k,6)); if n and n>=100000 and n<=149999 then redis.call('DEL',k) end end until c=='0' return 1" 0 >/dev/null
}
prepare() { sql "$ROOT/k6/performance/fixture.sql"; redis_cleanup; }
reset() { sql "$ROOT/k6/performance/cleanup.sql"; redis_cleanup; }

monitor() {
  local file=$1
  echo 'epoch,queue,ready,unacked' > "$file"
  while true; do
    docker exec kyro-rabbitmq rabbitmqctl -q list_queues name messages_ready messages_unacknowledged --formatter csv 2>/dev/null \
      | awk -v now="$(date +%s)" -F, 'NR>1 {gsub(/"/,""); print now "," $1 "," $2 "," $3}' >> "$file" || true
    sleep 1
  done
}

queue_depth() {
  docker exec kyro-rabbitmq rabbitmqctl -q list_queues name messages_ready messages_unacknowledged --formatter csv 2>/dev/null \
    | awk -v re="$QUEUES_REGEX" -F, 'NR>1 {gsub(/"/,""); if($1 ~ re) n+=$2+$3} END {print n+0}'
}

dlq_depth() {
  docker exec kyro-rabbitmq rabbitmqctl -q list_queues name messages_ready messages_unacknowledged --formatter csv 2>/dev/null \
    | awk -F, 'NR>1 {gsub(/"/,""); if($1 ~ /\.dlq$/) n+=$2+$3} END {print n+0}'
}

outbox_count() {
  local status=$1 total=0 db c
  for db in kyro_order kyro_catalog kyro_payment; do
    c=$(docker exec kyro-postgres psql -U postgres -d "$db" -Atc "SELECT count(*) FROM outbox_events WHERE status='$status'" 2>/dev/null || echo 0)
    total=$((total + c))
  done
  echo "$total"
}

run_k6() {
  local script=$1 label=$2 rate=$3 duration=$4
  local rabbit="$RESULTS/$label-rabbit.csv"
  local extra_args=()
  if [[ -n "${K6_OUT_CSV:-}" ]]; then
    extra_args+=(--out "csv=$K6_OUT_CSV")
  fi
  monitor "$rabbit" & local monitor_pid=$!
  set +e
  RATE="$rate" DURATION="$duration" k6 run --quiet --log-output "file=$RESULTS/$label-k6.log" \
    --summary-export "$RESULTS/$label.json" "${extra_args[@]}" "$ROOT/k6/performance/$script.js"
  local code=$?
  set -e
  kill "$monitor_pid" 2>/dev/null || true
  wait "$monitor_pid" 2>/dev/null || true

  local peak at_stop end outbox_stop outbox_end outbox_fail dlq_end drain=0 max_drain="${PERF_DRAIN_TIMEOUT:-30}"
  peak=$(awk -v re="$QUEUES_REGEX" -F, 'NR>1 && $2 ~ re {v[$1]+=$3+$4} END {for(t in v)if(v[t]>m)m=v[t]; print m+0}' "$rabbit")
  at_stop=$(queue_depth)
  outbox_stop=$(outbox_count PENDING)
  end=$at_stop
  outbox_end=$outbox_stop
  while [[ ("$end" -gt 0 || "$outbox_end" -gt 0) && "$drain" -lt "$max_drain" ]]; do
    sleep 1
    drain=$((drain + 1))
    end=$(queue_depth)
    outbox_end=$(outbox_count PENDING)
  done
  outbox_fail=$(outbox_count FAILED)
  dlq_end=$(dlq_depth)
  if [[ "$end" -gt 0 || "$outbox_end" -gt 0 ]]; then
    echo "Queues or outbox_events did not drain within ${max_drain}s (queue=$end, outbox=$outbox_end)" >&2
    code=1
  fi
  if [[ "$outbox_fail" -gt 0 || "$dlq_end" -gt 0 ]]; then
    echo "Dead-letter or failed outbox detected (outbox_failed=$outbox_fail, dlq=$dlq_end)" >&2
    code=1
  fi
  echo "$label,$rate,$code,$peak,$at_stop,$end,$outbox_stop,$outbox_end,$outbox_fail,$dlq_end,$drain" | tee -a "$RESULTS/runs.csv"
  return "$code"
}

start_results() {
  RESULTS="$RESULTS_BASE/$1"
  mkdir -p "$RESULTS"
  echo 'run,rate,exit,observed_peak_backlog,backlog_at_producer_stop,backlog_after_drain,outbox_at_stop,outbox_after_drain,outbox_failed,dlq_after_drain,drain_seconds' > "$RESULTS/runs.csv"
}

verify() {
  local label=$1
  {
    echo "orders_by_status"
    docker exec kyro-postgres psql -U postgres -d kyro_order -Atc \
      "SELECT CASE WHEN id>=9000000 THEN 'payment_fixture' ELSE 'generated' END||','||order_status||'/'||payment_status||'/'||CASE WHEN stock_reserved THEN 'reserved' ELSE 'not_reserved' END||','||count(*) FROM orders WHERE user_id BETWEEN 100000 AND 149999 GROUP BY CASE WHEN id>=9000000 THEN 'payment_fixture' ELSE 'generated' END,order_status,payment_status,stock_reserved ORDER BY 1"
    echo "payment_events"
    docker exec kyro-postgres psql -U postgres -d kyro_payment -Atc \
      "SELECT CASE WHEN order_id>=9000000 THEN 'payment_fixture' ELSE 'generated' END||','||payment_status||','||count(*) FROM payments WHERE order_id>=999999 GROUP BY CASE WHEN order_id>=9000000 THEN 'payment_fixture' ELSE 'generated' END,payment_status ORDER BY 1"
    echo "cart_cleanup"
    docker exec kyro-postgres psql -U postgres -d kyro_cart -Atc \
      "SELECT 'processed_cart_events,'||count(*) FROM processed_cart_events WHERE order_id>=999999"
    docker exec kyro-postgres psql -U postgres -d kyro_cart -Atc \
      "SELECT 'remaining_fixture_cart_items,'||count(*) FROM cart_items WHERE id BETWEEN 100000 AND 149999"
    echo "outbox_status"
    for db in kyro_order kyro_catalog kyro_payment; do
      docker exec kyro-postgres psql -U postgres -d "$db" -Atc \
        "SELECT '$db,'||status||','||count(*) FROM outbox_events GROUP BY status ORDER BY status"
    done
    echo "dlq_status"
    docker exec kyro-rabbitmq rabbitmqctl -q list_queues name messages_ready messages_unacknowledged --formatter csv 2>/dev/null \
      | awk -F, 'NR>1 {gsub(/"/,""); if($1 ~ /\.dlq$/) print $1 "," ($2+$3)}'
    echo "stock"
    docker exec kyro-postgres psql -U postgres -d kyro_catalog -Atc \
      "SELECT product_id||'/'||variant_name||','||stock FROM product_variant WHERE id IN (1,4,6,8,10,12,14,16,18,20) ORDER BY id"
  } > "$RESULTS/$label-verify.csv"
}

benchmark() {
  local script=$1 label=$2 runs=${PERF_RUNS:-3}
  prepare
  run_k6 "$script" "$label-warmup" "${PERF_WARMUP_RATE:-25}" "${PERF_WARMUP_DURATION:-20s}" || true
  for ((run = 1; run <= runs; run++)); do
    local code=0
    prepare
    run_k6 "$script" "$label-run-$run" "${PERF_RATE:-25}" "${PERF_DURATION:-60s}" || code=$?
    verify "$label-run-$run"
    ((code == 0)) || return "$code"
  done
}

ramp() {
  local script=$1 label=$2 default_stages=$3
  local samples="$RESULTS/$label-ramp-samples.csv"
  prepare
  STAGES="" START_RATE="" run_k6 "$script" "$label-warmup" "${PERF_WARMUP_RATE:-25}" "${PERF_WARMUP_DURATION:-20s}" || true
  prepare
  K6_OUT_CSV="$samples" STAGES="${STAGES:-$default_stages}" SYNC_P95_MS="${SYNC_P95_MS:-999999}" RABBIT_P95_MS="${RABBIT_P95_MS:-999999}" \
    run_k6 "$script" "$label-ramp" "ramp" "ramp" || true
  verify "$label-ramp"
  if [[ -f "$samples" ]]; then
    python3 - "$samples" "$RESULTS/$label-ramp-stages.csv" <<'PY'
import csv, sys, math
samples_path, out_path = sys.argv[1], sys.argv[2]
buckets = {}
t0 = None
with open(samples_path) as f:
    reader = csv.DictReader(f)
    for row in reader:
        m = row['metric_name']
        if m not in ('synchronous_path_latency', 'rabbitmq_propagation_latency', 'technical_success_rate', 'dropped_iterations'):
            continue
        ts = int(row['timestamp'])
        if t0 is None:
            t0 = ts
        idx = (ts - t0) // 30
        b = buckets.setdefault(idx, {'lat': [], 'ok': 0, 'fail': 0, 'drop': 0})
        v = float(row['metric_value'])
        if m in ('synchronous_path_latency', 'rabbitmq_propagation_latency'):
            b['lat'].append(v)
        elif m == 'technical_success_rate':
            if v >= 1.0: b['ok'] += 1
            else: b['fail'] += 1
        elif m == 'dropped_iterations':
            b['drop'] += int(v)
with open(out_path, 'w', newline='') as f:
    w = csv.writer(f)
    w.writerow(['window_30s_idx', 'count', 'approx_rps', 'p50_ms', 'p95_ms', 'max_ms', 'success_pct', 'dropped'])
    for idx in sorted(buckets):
        b = buckets[idx]
        lats = sorted(b['lat'])
        n = len(lats)
        total = b['ok'] + b['fail']
        p50 = lats[int(0.50 * (n - 1))] if n else 0
        p95 = lats[int(0.95 * (n - 1))] if n else 0
        mx = lats[-1] if n else 0
        succ = (100.0 * b['ok'] / total) if total else 100.0
        w.writerow([idx, total, round(total / 30.0, 2), round(p50, 2), round(p95, 2), round(mx, 2), round(succ, 2), b['drop']])
PY
    rm -f "$samples"
  fi
}

case "${1:-}" in
  prepare) prepare ;;
  reset) reset ;;
  feign)
    start_results "${PERF_DIR:-feign}"
    benchmark order_async feign
    ;;
  feign-ramp)
    start_results "${PERF_DIR:-feign-ramp}"
    START_RATE="${START_RATE:-50}" ramp order_async feign '[{"target":50,"duration":"30s"},{"target":100,"duration":"1s"},{"target":100,"duration":"29s"},{"target":150,"duration":"1s"},{"target":150,"duration":"29s"},{"target":200,"duration":"1s"},{"target":200,"duration":"29s"},{"target":250,"duration":"1s"},{"target":250,"duration":"29s"},{"target":300,"duration":"1s"},{"target":300,"duration":"29s"},{"target":350,"duration":"1s"},{"target":350,"duration":"29s"}]'
    ;;
  rabbitmq)
    start_results "${PERF_DIR:-rabbitmq}"
    benchmark payment_async rabbitmq
    ;;
  rabbitmq-ramp)
    start_results "${PERF_DIR:-rabbitmq-ramp}"
    START_RATE="${START_RATE:-25}" ramp payment_async rabbitmq '[{"target":25,"duration":"30s"},{"target":50,"duration":"1s"},{"target":50,"duration":"29s"},{"target":100,"duration":"1s"},{"target":100,"duration":"29s"},{"target":150,"duration":"1s"},{"target":150,"duration":"29s"},{"target":200,"duration":"1s"},{"target":200,"duration":"29s"}]'
    ;;
  payment-failure)
    start_results "${PERF_DIR:-payment-failure}"
    prepare
    RESPONSE_CODE=24 EXPECTED_PAYMENT_STATUS=FAILED run_k6 payment_async payment-failure "${PERF_RATE:-10}" "${PERF_DURATION:-10s}"
    verify payment-failure
    ;;
  e2e-saga)
    start_results "${PERF_DIR:-e2e-saga}"
    benchmark e2e_saga e2e-saga
    ;;
  *) echo "Usage: $0 feign|feign-ramp|rabbitmq|rabbitmq-ramp|payment-failure|e2e-saga|prepare|reset" >&2; exit 2 ;;
esac
