// Scenario (b): retry storm.
// Every transfer is sent TWICE AT THE SAME TIME with the same Idempotency-Key (http.batch runs
// both requests in parallel), like a client that times out and retries immediately, or a proxy
// that duplicates requests. Exactly one transfer must exist per key afterwards.
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  baseOptions, seedAccounts, newKey, transferRequest, recordTransferResponse,
  logicalTransfersOk, logicalTransfersFailed, randomPair, randomInt,
} from './lib/common.js';

const ACCOUNTS = 20;
const OPENING_BALANCE = 1_000_000_000;

// Both attempts got 201 but with DIFFERENT transfer ids: money moved twice for one key.
const clientDetectedDuplicates = new Counter('client_detected_duplicates');

export const options = baseOptions({
  executor: 'constant-vus',
  vus: Number(__ENV.VUS || 32),
  duration: __ENV.DURATION || '60s',
});

export function setup() {
  return { accounts: seedAccounts(ACCOUNTS, OPENING_BALANCE) };
}

export default function (data) {
  const [from, to] = randomPair(data.accounts.length);
  const source = data.accounts[from];
  const request = transferRequest(source.token, newKey(), source.accountId,
    data.accounts[to].accountId, randomInt(1, 1000));

  const [first, second] = http.batch([request, request]);
  const firstOk = recordTransferResponse(first);
  const secondOk = recordTransferResponse(second);

  if (firstOk && secondOk && first.json('id') !== second.json('id')) {
    clientDetectedDuplicates.add(1);
  }
  if (firstOk || secondOk) {
    logicalTransfersOk.add(1);
  } else {
    logicalTransfersFailed.add(1);
  }
}
