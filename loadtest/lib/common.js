// Shared helpers for the k6 scenarios. k6 runs JavaScript, but it is not Node.js:
// each virtual user (VU) is an isolated JS runtime that loops over the default function.
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@ledger.local';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'admin-password-local';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

// ---- custom metrics (they appear in the end-of-test summary) ------------------------------
// A "logical transfer" is one business operation, however many HTTP attempts it took.
export const logicalTransfersOk = new Counter('logical_transfers_ok');
export const logicalTransfersFailed = new Counter('logical_transfers_failed');
// 201 with Idempotent-Replayed: false = the server moved money for this request.
export const transfersCreated = new Counter('transfers_created');
// 201 with Idempotent-Replayed: true = answered from the idempotency store, no money moved.
export const transfersReplayed = new Counter('transfers_replayed');
export const status409 = new Counter('status_409');
export const status422 = new Counter('status_422');
export const status5xx = new Counter('status_5xx');
export const statusOther = new Counter('status_other');
// Fraction of transfer requests that did not get a 201.
export const transferErrors = new Rate('transfer_errors');

/** Options shared by every scenario: p99 in the summary, generous setup time. */
export function baseOptions(scenario) {
  return {
    setupTimeout: '300s',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
    scenarios: { main: scenario },
    // Thresholds this loose never fail; they exist so k6 reports these tagged sub-metrics.
    thresholds: {
      'http_reqs{name:seed}': ['count>=0'],
      'http_reqs{name:login}': ['count>=0'],
      'http_req_duration{name:transfer}': ['p(99)<600000'],
      'http_req_duration{name:get_account}': ['p(99)<600000'],
      'http_req_duration{name:list_transfers}': ['p(99)<600000'],
    },
  };
}

// Module state is per VU (each VU has its own JS runtime), so this counter is per VU too.
let keyCounter = 0;

/** A unique, header-safe Idempotency-Key (works in setup() too, where __VU/__ITER are not set). */
export function newKey() {
  keyCounter += 1;
  return `k-${Date.now()}-${keyCounter}-${Math.random().toString(36).slice(2, 12)}`;
}

export function auth(token) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

export function login(email, password) {
  const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email, password }),
    { headers: JSON_HEADERS, tags: { name: 'login' } });
  if (res.status !== 200) {
    fail(`login failed for ${email}: ${res.status} ${res.body}`);
  }
  return res.json('accessToken');
}

export function adminLogin() {
  return login(ADMIN_EMAIL, ADMIN_PASSWORD);
}

/**
 * Creates `count` users, each with one wallet funded with `balancePaise`, in parallel batches.
 * Returns [{ accountId, token }]. Runs in setup(), before the measured part of the test.
 */
export function seedAccounts(count, balancePaise) {
  const adminToken = adminLogin();
  const runId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const seeded = [];
  const batchSize = 20;
  for (let start = 0; start < count; start += batchSize) {
    const indexes = [];
    for (let i = start; i < Math.min(count, start + batchSize); i++) {
      indexes.push(i);
    }
    const creds = indexes.map((i) => ({ email: `lt-${runId}-${i}@example.com`, password: `password-${i}-lt` }));

    const registered = http.batch(creds.map((c) => ['POST', `${BASE_URL}/api/auth/register`,
      JSON.stringify({ fullName: 'Load Test', email: c.email, password: c.password }),
      { headers: JSON_HEADERS, tags: { name: 'seed' } }]));
    registered.forEach((r) => { if (r.status !== 201) fail(`register: ${r.status} ${r.body}`); });

    const logins = http.batch(creds.map((c) => ['POST', `${BASE_URL}/api/auth/login`,
      JSON.stringify({ email: c.email, password: c.password }), { headers: JSON_HEADERS, tags: { name: 'seed' } }]));
    const tokens = logins.map((r) => { if (r.status !== 200) fail(`login: ${r.status}`); return r.json('accessToken'); });

    const opened = http.batch(tokens.map((t) => ['POST', `${BASE_URL}/api/accounts`, null,
      { headers: auth(t), tags: { name: 'seed' } }]));
    const accountIds = opened.map((r) => { if (r.status !== 201) fail(`open account: ${r.status}`); return r.json('id'); });

    const deposits = http.batch(accountIds.map((id) => ['POST', `${BASE_URL}/api/deposits`,
      JSON.stringify({ accountId: id, amountPaise: balancePaise }),
      { headers: Object.assign(auth(adminToken), { 'Idempotency-Key': newKey() }), tags: { name: 'seed' } }]));
    deposits.forEach((r) => { if (r.status !== 201) fail(`deposit: ${r.status} ${r.body}`); });

    accountIds.forEach((id, k) => seeded.push({ accountId: id, token: tokens[k] }));
  }
  return seeded;
}

/** Builds the request tuple for http.batch / http.request. */
export function transferRequest(token, key, fromAccountId, toAccountId, amountPaise) {
  return ['POST', `${BASE_URL}/api/transfers`,
    JSON.stringify({ fromAccountId, toAccountId, amountPaise }),
    { headers: Object.assign(auth(token), { 'Idempotency-Key': key }), tags: { name: 'transfer' } }];
}

/** Records the outcome metrics for one transfer HTTP response. Returns true on 201. */
export function recordTransferResponse(res) {
  const ok = res.status === 201;
  transferErrors.add(!ok);
  if (ok) {
    if (res.headers['Idempotent-Replayed'] === 'true') {
      transfersReplayed.add(1);
    } else {
      transfersCreated.add(1);
    }
  } else if (res.status === 409) {
    status409.add(1);
  } else if (res.status === 422) {
    status422.add(1);
  } else if (res.status >= 500) {
    status5xx.add(1);
  } else {
    statusOther.add(1);
  }
  check(res, { 'transfer 201': (r) => r.status === 201 });
  return ok;
}

/** Two different random indexes in [0, n). */
export function randomPair(n) {
  const from = Math.floor(Math.random() * n);
  let to = Math.floor(Math.random() * (n - 1));
  if (to >= from) {
    to += 1;
  }
  return [from, to];
}

export function randomInt(min, max) {
  return min + Math.floor(Math.random() * (max - min + 1));
}
