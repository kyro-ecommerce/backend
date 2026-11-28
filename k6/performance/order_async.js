import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import {
  BASE_URL, acceptedRequests, auth, body, buildScenario, priceForUser,
  syncPathLatency, technicalSuccess, thresholds, userId,
} from './common.js';

export const options = {
  scenarios: buildScenario(),
  thresholds: {
    ...thresholds(),
    synchronous_path_latency: [`p(95)<=${Number(__ENV.SYNC_P95_MS || 500)}`],
  },
};

export default function () {
  const iter = exec.scenario.iterationInTest;
  const id = userId(iter);
  const headers = auth(id, iter);
  if (__ENV.USE_IDEMPOTENCY === 'true') {
    headers['Idempotency-Key'] = `perf-order-${id}`;
  }
  const started = Date.now();
  const response = http.post(`${BASE_URL}/orders`, JSON.stringify({
    addressId: id,
    paymentMethod: 'VNPAY',
    cartItemIds: [id],
    cartVersion: 0,
    expectedTotalDiscountedPrice: priceForUser(id),
  }), { headers, tags: { path: 'order_producer' } });
  syncPathLatency.add(Date.now() - started);
  const orderId = body(response).orders?.[0]?.id;
  const accepted = check(response, { 'order accepted': (r) => r.status === 201 && !!orderId });
  technicalSuccess.add(accepted);
  if (!accepted) return;
  acceptedRequests.add(1);
}
