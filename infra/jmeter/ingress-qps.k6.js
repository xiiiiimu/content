// Open-model load for the purchase-entry POST (one k6 run = one level). No per-request output; aggregates only.
// Env: API, CAMPAIGNS (comma list; or CAMPAIGN), BUYERS_FILE (CSV userId,token[,..]; private, target/ only), RATE,
//      DURATION, VUS, RUN_TAG, TIMEOUT (default 5s), SUMMARY (output JSON path),
//      UNIQUE_PAIRS=1 (accept path: never reuse a (buyer, campaign) pair; overflow is counted, not sent),
//      RECORD=1 (accept path: one console line per request for per-request reconciliation; use --console-output).
// Each request uses a new clientKey: <RUN_TAG>-<iterationInTest>. Iteration i -> campaign i % n, buyer floor(i / n).
// Accepted iterations are recorded with their reservationId only; tokens are never printed.
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';
import { classify } from './ingress-qps-classify.js';

const API = __ENV.API || 'http://127.0.0.1:28080';
const CAMPAIGNS = (__ENV.CAMPAIGNS || __ENV.CAMPAIGN || '').split(',').filter((x) => x);
const UNIQUE = __ENV.UNIQUE_PAIRS === '1';
const RECORD = __ENV.RECORD === '1';
const RATE = Number(__ENV.RATE);
const VUS = Number(__ENV.VUS);
const buyers = new SharedArray('buyers', () => open(__ENV.BUYERS_FILE).split(/\r?\n/).slice(1)
  .filter((l) => l.trim()).map((l) => { const c = l.trim().split(','); return { userId: c[0], token: c[1] }; }));

export const options = {
  scenarios: {
    level: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: __ENV.DURATION || '30s',
             preAllocatedVUs: VUS, maxVUs: VUS, gracefulStop: '10s' },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: false,
};

const accepted = new Counter('biz_accepted');
const soldOut = new Counter('biz_sold_out');
const rejectByStatus = { 401: new Counter('rej_401'), 403: new Counter('rej_403'), 404: new Counter('rej_404'), 409: new Counter('rej_409_other'), 503: new Counter('err_503') };
const rejectOther4xx = new Counter('rej_4xx_other');
const http5xx = new Counter('err_5xx');
const timeouts = new Counter('err_timeout');
const connFail = new Counter('err_conn');
const unexpected2xx = new Counter('unexpected_2xx');
const latSoldOut = new Trend('lat_sold_out', true);
const latAccepted = new Trend('lat_accepted', true);
const pairOverflow = new Counter('pair_overflow');

export function setup() {
  if (CAMPAIGNS.length === 0 || !RATE || !VUS || buyers.length === 0) throw new Error('CAMPAIGN, RATE, VUS and BUYERS_FILE are required');
  return { setupEndMs: Date.now(), buyers: buyers.length, campaigns: CAMPAIGNS };
}

export default function () {
  const i = exec.scenario.iterationInTest;
  const campaign = CAMPAIGNS[i % CAMPAIGNS.length];
  const slot = Math.floor(i / CAMPAIGNS.length);
  if (UNIQUE && slot >= buyers.length) { pairOverflow.add(1); return; }
  const b = buyers[slot % buyers.length];
  const res = http.post(`${API}/api/sale-campaigns/${campaign}/purchase`, JSON.stringify({ clientKey: `${__ENV.RUN_TAG}-${i}` }),
    { headers: { Authorization: 'Bearer ' + b.token, 'Content-Type': 'application/json' }, timeout: __ENV.TIMEOUT || '5s', tags: { name: 'purchase' } });
  let code = null; let reservation = '';
  if (res.status >= 400 && res.status < 500) { try { code = res.json('code'); } catch (e) { code = null; } }
  if (RECORD && res.status === 200) { try { reservation = res.json('reservationId') || ''; } catch (e) { reservation = ''; } }
  if (RECORD) console.log(`R,${i},${campaign},${b.userId},${res.status},${res.error_code || 0},${code || ''},${reservation},${res.timings.duration.toFixed(2)}`);
  const k = classify(res.status, res.error_code, code, [401, 403, 404, 409]);
  if (k === 'accepted') { accepted.add(1); latAccepted.add(res.timings.duration); }
  else if (k === 'soldOut') { soldOut.add(1); latSoldOut.add(res.timings.duration); }
  else if (k === 'timeout') timeouts.add(1);
  else if (k === 'conn') connFail.add(1);
  else if (k === '503') rejectByStatus[503].add(1);
  else if (k === '5xx') http5xx.add(1);
  else if (k === 'unexpected2xx') unexpected2xx.add(1);
  else if (k === '4xxOther') rejectOther4xx.add(1);
  else rejectByStatus[Number(k)].add(1);
}

export function handleSummary(data) {
  const out = {};
  out[__ENV.SUMMARY] = JSON.stringify({ setup: data.setup_data, metrics: data.metrics, state: data.state,
    options: { rate: RATE, vus: VUS, duration: __ENV.DURATION || '30s', runTag: __ENV.RUN_TAG } });
  out.stdout = `level rate=${RATE} vus=${VUS} reqs=${data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0}\n`;
  return out;
}
