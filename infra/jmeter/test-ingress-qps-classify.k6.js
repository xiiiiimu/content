// Unit test for ingress-qps-classify.js: k6 run --quiet --no-usage-report infra/jmeter/test-ingress-qps-classify.k6.js
// (no network; exits non-zero when a check fails).
import { check } from 'k6';
import { classify } from './ingress-qps-classify.js';
export const options = { iterations: 1, vus: 1, thresholds: { checks: ['rate==1'] } };
const S = [401, 403, 404, 409];
export default function () {
  const cases = [
    [[409, 1409, 'SOLD_OUT'], 'soldOut'], [[409, 1409, 'ALREADY_OWNED'], '409'], [[200, 0, null], 'accepted'],
    [[201, 0, null], 'unexpected2xx'], [[401, 1401, 'UNAUTHENTICATED'], '401'], [[429, 1429, null], '4xxOther'],
    [[503, 1503, 'SALE_CACHE_UNAVAILABLE'], '503'], [[500, 1500, null], '5xx'], [[0, 1050, null], 'timeout'],
    [[0, 1212, null], 'conn'], [[0, 1000, null], 'conn'],
  ];
  for (const [args, want] of cases) check(want, { [`${args.join('/')} -> ${want}`]: () => classify(args[0], args[1], args[2], S) === want });
}
