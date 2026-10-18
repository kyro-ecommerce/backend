#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RESULTS_BASE="$ROOT/k6/results"

env_value() { sed -n "s/^$1=//p" "$ROOT/.env" | tail -1; }
export JWT_SECRET="${JWT_SECRET:-$(env_value JWT_SECRET)}"
export VNPAY_TMN_CODE="${VNPAY_TMN_CODE:-$(env_value VNPAY_TMN_CODE)}"
export VNPAY_HASH_SECRET="${VNPAY_HASH_SECRET:-$(env_value VNPAY_HASH_SECRET)}"

sql() { docker exec -i kyro-postgres psql -U postgres -d postgres < "$1"; }
redis_cleanup() {
  docker exec kyro-redis redis-cli --raw EVAL \
    "local c='0' repeat local r=redis.call('SCAN',c,'MATCH','cart:*','COUNT',1000); c=r[1]; for _,k in ipairs(r[2]) do local n=tonumber(string.sub(k,6)); if n and n>=100000 and n<=139999 then redis.call('DEL',k) end end until c=='0' return 1" 0 >/dev/null
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

run_k6() {
  local script=$1 label=$2 rate=$3 duration=$4
  local rabbit="$RESULTS/$label-rabbit.csv"
  monitor "$rabbit" & local monitor_pid=$!
  set +e
  RATE="$rate" DURATION="$duration" k6 run --quiet --log-output "file=$RESULTS/$label-k6.log" \
    --summary-export "$RESULTS/$label.json" "$ROOT/k6/performance/$script.js"
  local code=$?
  set -e
  kill "$monitor_pid" 2>/dev/null || true
  wait "$monitor_pid" 2>/dev/null || true
  local peak at_stop end drain=0
  peak=$(awk -F, 'NR>1 && $2 ~ /^(order-payment-status-queue|catalog-order-created-queue|cart-stock-reserved-queue|order-stock-result-queue)$/ {v[$1]+=$3+$4} END {for(t in v)if(v[t]>m)m=v[t]; print m+0}' "$rabbit")
  at_stop=$(queue_depth)
  end=$at_stop
  while [[ "$end" -gt 0 && "$drain" -lt 30 ]]; do sleep 1; drain=$((drain + 1)); end=$(queue_depth); done
  if [[ "$end" -gt 0 ]]; then
    echo "Queues did not drain within 30 seconds" >&2
    code=1
  fi
  echo "$label,$rate,$code,$peak,$at_stop,$end,$drain" | tee -a "$RESULTS/runs.csv"
  return "$code"
}

start_results() {
  RESULTS="$RESULTS_BASE/$1"
  mkdir -p "$RESULTS"
  echo 'run,rate,exit,observed_peak_backlog,backlog_at_producer_stop,backlog_after_drain,drain_seconds' > "$RESULTS/runs.csv"
}

queue_depth() {
  docker exec kyro-rabbitmq rabbitmqctl -q list_queues name messages_ready messages_unacknowledged --formatter csv 2>/dev/null \
    | awk -F, 'NR>1 {gsub(/"/,""); if($1 ~ /^(order-payment-status-queue|catalog-order-created-queue|cart-stock-reserved-queue|order-stock-result-queue)$/) n+=$2+$3} END {print n+0}'
}

verify() {
  local label=$1
  {
    echo "orders_by_status"
    docker exec kyro-postgres psql -U postgres -d kyro_order -Atc \
      "SELECT CASE WHEN id>=9000000 THEN 'payment_fixture' ELSE 'generated' END||','||order_status||'/'||payment_status||'/'||CASE WHEN stock_reserved THEN 'reserved' ELSE 'not_reserved' END||','||count(*) FROM orders WHERE user_id BETWEEN 100000 AND 139999 GROUP BY CASE WHEN id>=9000000 THEN 'payment_fixture' ELSE 'generated' END,order_status,payment_status,stock_reserved ORDER BY 1"
    echo "payment_events"
    docker exec kyro-postgres psql -U postgres -d kyro_payment -Atc \
      "SELECT CASE WHEN order_id>=9000000 THEN 'payment_fixture' ELSE 'generated' END||','||payment_status||','||count(*) FROM payment_details WHERE order_id>=999999 GROUP BY CASE WHEN order_id>=9000000 THEN 'payment_fixture' ELSE 'generated' END,payment_status ORDER BY 1"
    echo "stock"
    docker exec kyro-postgres psql -U postgres -d kyro_catalog -Atc \
      "SELECT product_id||'/'||variant_name||','||stock FROM product_variant WHERE id IN (1,4,6,8,10,12,14,16,18,20) ORDER BY id"
  } > "$RESULTS/$label-verify.csv"
}

benchmark() {
  local script=$1 label=$2 runs=${PERF_RUNS:-1}
  prepare
  run_k6 "$script" "$label-warmup" 10 10s || true
  for ((run = 1; run <= runs; run++)); do
    local code=0
    prepare
    run_k6 "$script" "$label-run-$run" "${PERF_RATE:-25}" "${PERF_DURATION:-30s}" || code=$?
    verify "$label-run-$run"
    ((code == 0)) || return "$code"
  done
}

case "${1:-}" in
  prepare) prepare ;;
  reset) reset ;;
  feign)
    start_results feign
    benchmark order_async feign
    ;;
  rabbitmq)
    start_results rabbitmq
    benchmark payment_async rabbitmq
    ;;
  payment-failure)
    start_results payment-failure
    prepare
    RESPONSE_CODE=24 EXPECTED_PAYMENT_STATUS=FAILED run_k6 payment_async payment-failure "${PERF_RATE:-25}" "${PERF_DURATION:-30s}"
    verify payment-failure
    ;;
  *) echo "Usage: $0 feign|rabbitmq|payment-failure|prepare|reset" >&2; exit 2 ;;
esac
