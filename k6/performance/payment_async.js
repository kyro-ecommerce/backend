import crypto from 'k6/crypto';
import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import {
  BASE_URL, PAYMENT_ORDER_ID, acceptedRequests, auth, body, buildScenario, observationSuccess,
  observedUpdates, propagationLatency, required, technicalSuccess, thresholds, userId,
} from './common.js';

export const options = {
  scenarios: buildScenario(),
  thresholds: {
    ...thresholds(),
    rabbitmq_observation_success_rate: ['rate>=0.99'],
    rabbitmq_propagation_latency: [`p(95)<=${Number(__ENV.RABBIT_P95_MS || 2000)}`],
  },
};

export default function () {
  const offset = exec.scenario.iterationInTest;
  const orderId = PAYMENT_ORDER_ID + offset;
  const responseCode = __ENV.RESPONSE_CODE || '00';
  const expectedStatus = __ENV.EXPECTED_PAYMENT_STATUS || (responseCode === '00' ? 'COMPLETED' : 'FAILED');
  const headers = auth(userId(offset), offset);
  const params = {
    vnp_Amount: '10000',
    vnp_ResponseCode: responseCode,
    vnp_TmnCode: required('VNPAY_TMN_CODE'),
    vnp_TransactionNo: `k6-${orderId}`,
    vnp_TransactionStatus: responseCode,
    vnp_TxnRef: `perf-${orderId}`,
  };
  const hashData = queryString(params);
  params.vnp_SecureHash = crypto.hmac('sha512', required('VNPAY_HASH_SECRET'), hashData, 'hex');
  const started = Date.now();
  const response = http.get(`${BASE_URL}/payment-providers/vnpay/callback?${queryString(params)}`, {
    tags: { path: 'payment_producer' },
  });
  const accepted = check(response, { 'callback accepted': (r) => r.status === 200 && body(r).success === (responseCode === '00') });
  technicalSuccess.add(accepted);
  if (!accepted) return;
  acceptedRequests.add(1);

  if (offset % 10 !== 0) return;
  while (Date.now() - started < 10000) {
    const order = body(http.get(`${BASE_URL}/orders/${orderId}`, { headers, tags: { path: 'observer' } }));
    if (order.paymentStatus === expectedStatus) {
      propagationLatency.add(Date.now() - started);
      observedUpdates.add(1);
      observationSuccess.add(true);
      return;
    }
    sleep(0.05);
  }
  observationSuccess.add(false);
}

function queryString(params) {
  return Object.keys(params).sort().map((key) => `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`).join('&');
}
