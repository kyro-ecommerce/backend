import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { Trend, Rate, Counter } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
export const BASE_USER_ID = Number(__ENV.BASE_USER_ID || 100000);
export const PAYMENT_ORDER_ID = 9000000;

export const technicalSuccess = new Rate('technical_success_rate');
export const observationSuccess = new Rate('rabbitmq_observation_success_rate');
export const syncPathLatency = new Trend('synchronous_path_latency', true);
export const propagationLatency = new Trend('rabbitmq_propagation_latency', true);
export const e2eSagaLatency = new Trend('e2e_saga_latency', true);
export const e2eSagaSuccess = new Rate('e2e_saga_success_rate');
export const idempotencySuccess = new Rate('idempotency_success_rate');
export const acceptedRequests = new Counter('accepted_requests');
export const observedUpdates = new Counter('observed_updates');
export const idempotencyChecks = new Counter('idempotency_checks');

export function auth(userId, iteration = 0) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64({ alg: 'HS256', typ: 'JWT' });
  const payload = b64({
    sub: `perf-${userId}@kyro.test`,
    id: userId,
    roles: ['CUSTOMER'],
    jti: `perf-${userId}-${now}-${iteration}`,
    iat: now,
    exp: now + 3600,
  });
  const key = encoding.b64decode(required('JWT_SECRET'), 'std');
  const signature = encoding.b64encode(crypto.hmac('sha256', key, `${header}.${payload}`, 'binary'), 'rawurl');
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${header}.${payload}.${signature}` };
}

export function buildScenario() {
  if (__ENV.STAGES) {
    return {
      load: {
        executor: 'ramping-arrival-rate',
        startRate: Number(__ENV.START_RATE || 25),
        timeUnit: '1s',
        stages: JSON.parse(__ENV.STAGES),
        preAllocatedVUs: Number(__ENV.PRE_VUS || 200),
        maxVUs: Number(__ENV.MAX_VUS || 1200),
      },
    };
  }
  return {
    load: {
      executor: __ENV.EXECUTOR || 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 10),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: Number(__ENV.PRE_VUS || 200),
      maxVUs: Number(__ENV.MAX_VUS || 1200),
    },
  };
}

export function body(response) {
  try { return JSON.parse(response.body) || {}; } catch (_) { return {}; }
}

export function userId(iteration) {
  return BASE_USER_ID + iteration;
}

export function thresholds() {
  return {
    technical_success_rate: ['rate>=0.99'],
    dropped_iterations: ['count==0'],
  };
}

export function priceForUser(id) {
  return [32890600, 20001300, 18051400, 18911400, 60191400, 30020900, 3495080, 1295190, 496290, 7461700][(id - BASE_USER_ID) % 10];
}

function b64(value) {
  return encoding.b64encode(JSON.stringify(value), 'rawurl');
}

export function required(name) {
  if (!__ENV[name]) throw new Error(`Set ${name}`);
  return __ENV[name];
}
