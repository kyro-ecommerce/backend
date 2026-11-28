import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import {
  BASE_URL, acceptedRequests, auth, body, buildScenario, e2eSagaLatency, e2eSagaSuccess,
  idempotencyChecks, idempotencySuccess, observedUpdates, priceForUser, syncPathLatency,
  technicalSuccess, thresholds, userId,
} from './common.js';

export const options = {
  scenarios: buildScenario(),
  thresholds: {
    ...thresholds(),
    idempotency_success_rate: ['rate>=0.99'],
    e2e_saga_success_rate: ['rate>=0.99'],
    e2e_saga_latency: [`p(95)<=${Number(__ENV.SAGA_P95_MS || 2000)}`],
  },
};

export default function () {
  const iter = exec.scenario.iterationInTest;
  const id = userId(iter);
  const headers = auth(id, iter);
  headers['Idempotency-Key'] = `perf-e2e-${id}`;
  const payload = JSON.stringify({
    addressId: id,
    paymentMethod: 'COD',
    cartItemIds: [id],
    cartVersion: 0,
    expectedTotalDiscountedPrice: priceForUser(id),
  });

  const started = Date.now();
  const response = http.post(`${BASE_URL}/orders`, payload, { headers, tags: { path: 'e2e_order_producer' } });
  syncPathLatency.add(Date.now() - started);
  const orderId = body(response).orders?.[0]?.id;
  const accepted = check(response, { 'cod order accepted': (r) => r.status === 201 && !!orderId });
  technicalSuccess.add(accepted);
  if (!accepted) return;
  acceptedRequests.add(1);

  if (iter % 10 !== 0) return;

  const replayResponse = http.post(`${BASE_URL}/orders`, payload, { headers, tags: { path: 'e2e_idempotency_replay' } });
  const replayId = body(replayResponse).orders?.[0]?.id;
  const idempotent = check(replayResponse, {
    'idempotent replay matches order': (r) => r.status === 201 && replayId === orderId,
  });
  idempotencySuccess.add(idempotent);
  idempotencyChecks.add(1);

  while (Date.now() - started < 10000) {
    const order = body(http.get(`${BASE_URL}/orders/${orderId}`, { headers, tags: { path: 'e2e_observer' } }));
    if (order.orderStatus === 'CONFIRMED') {
      e2eSagaLatency.add(Date.now() - started);
      observedUpdates.add(1);
      e2eSagaSuccess.add(true);
      return;
    }
    sleep(0.05);
  }
  e2eSagaSuccess.add(false);
}
