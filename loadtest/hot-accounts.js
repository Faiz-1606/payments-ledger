// Scenario (a): hot-account contention.
// Many virtual users hammer transfers among only 5 accounts, so almost every transfer competes
// for the same few rows. This is where the locking strategies differ most.
import http from 'k6/http';
import {
  baseOptions, seedAccounts, newKey, transferRequest, recordTransferResponse,
  logicalTransfersOk, logicalTransfersFailed, randomPair, randomInt,
} from './lib/common.js';

const HOT_ACCOUNTS = 5;
const OPENING_BALANCE = 1_000_000_000; // ₹1 crore each: nobody ever runs out of money

export const options = baseOptions({
  executor: 'constant-vus',
  vus: Number(__ENV.VUS || 32),
  duration: __ENV.DURATION || '60s',
});

export function setup() {
  return { accounts: seedAccounts(HOT_ACCOUNTS, OPENING_BALANCE) };
}

export default function (data) {
  const [from, to] = randomPair(data.accounts.length);
  const source = data.accounts[from];
  const res = http.request(...transferRequest(source.token, newKey(), source.accountId,
    data.accounts[to].accountId, randomInt(1, 1000)));
  if (recordTransferResponse(res)) {
    logicalTransfersOk.add(1);
  } else {
    logicalTransfersFailed.add(1);
  }
}
