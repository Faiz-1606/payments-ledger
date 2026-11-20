// Scenario (c): mixed, realistic traffic.
// 200 users, each acting on their own wallet. Most requests are reads; transfers go to random
// other users (low contention); a few clients retry a transfer with the same key.
//   55% GET own account (balance + recent entries)
//   15% GET own transfer history
//   25% transfer to a random user
//    5% transfer, then retry it with the same Idempotency-Key
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import {
  baseOptions, seedAccounts, newKey, transferRequest, recordTransferResponse, auth, BASE_URL,
  logicalTransfersOk, logicalTransfersFailed, randomInt,
} from './lib/common.js';

const ACCOUNTS = 200;
const OPENING_BALANCE = 10_000_000; // ₹1 lakh each; transfers are at most ₹50

export const options = baseOptions({
  executor: 'constant-vus',
  vus: Number(__ENV.VUS || 64),
  duration: __ENV.DURATION || '60s',
});

export function setup() {
  return { accounts: seedAccounts(ACCOUNTS, OPENING_BALANCE) };
}

export default function (data) {
  const me = data.accounts[(exec.vu.idInTest - 1) % data.accounts.length];
  const dice = Math.random();

  if (dice < 0.55) {
    const res = http.get(`${BASE_URL}/api/accounts/${me.accountId}`,
      { headers: auth(me.token), tags: { name: 'get_account' } });
    check(res, { 'get account 200': (r) => r.status === 200 });
  } else if (dice < 0.70) {
    const res = http.get(`${BASE_URL}/api/transfers?size=20`,
      { headers: auth(me.token), tags: { name: 'list_transfers' } });
    check(res, { 'list transfers 200': (r) => r.status === 200 });
  } else {
    let other = data.accounts[randomInt(0, data.accounts.length - 1)];
    while (other.accountId === me.accountId) {
      other = data.accounts[randomInt(0, data.accounts.length - 1)];
    }
    const request = transferRequest(me.token, newKey(), me.accountId, other.accountId, randomInt(100, 5000));
    let ok = recordTransferResponse(http.request(...request));
    if (dice >= 0.95) {
      // The client did not trust the first answer and retries with the same key.
      ok = recordTransferResponse(http.request(...request)) || ok;
    }
    if (ok) {
      logicalTransfersOk.add(1);
    } else {
      logicalTransfersFailed.add(1);
    }
  }
}
