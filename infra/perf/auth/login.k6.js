// Open-model login load: POST /api/auth/login, one distinct seeded user per iteration (lt_<i>), right password.
// Env: BASE_URL RATE DURATION USER_COUNT MAX_VUS PASSWORD
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const RATE = parseInt(__ENV.RATE);
const USER_COUNT = parseInt(__ENV.USER_COUNT);
const ok = new Counter('login_200');
const limited = new Counter('login_429');
const e4 = new Counter('login_4xx_other');
const e5 = new Counter('login_5xx');
const e0 = new Counter('login_transport_err');

if (RATE * parseInt((__ENV.DURATION || '30s').replace('s', '')) > USER_COUNT) throw new Error('not enough seeded users');

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    login: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: __ENV.DURATION || '30s',
      preAllocatedVUs: Math.min(RATE * 2, parseInt(__ENV.MAX_VUS || '400')),
      maxVUs: parseInt(__ENV.MAX_VUS || '400'),
      gracefulStop: '30s',
    },
  },
};

export default function () {
  const i = exec.scenario.iterationInTest;
  const res = http.post(`${__ENV.BASE_URL}/api/auth/login`,
    JSON.stringify({ username: `lt_${i}`, password: __ENV.PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '60s' });
  if (res.status === 200) ok.add(1);
  else if (res.status === 429) limited.add(1);
  else if (res.status >= 500) e5.add(1);
  else if (res.status >= 400) e4.add(1);
  else e0.add(1);
}
