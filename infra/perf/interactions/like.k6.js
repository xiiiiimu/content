// Open-model like load: PUT /api/posts/{id}/likes. Each iteration uses a unique (user, post) pair,
// so every request is a real false->true flip (no idempotent no-op likes).
// Env: BASE_URL RATE DURATION POST_BASE POST_COUNT USER_BASE TOPIC(optional) IDENTITY_KEY MAX_VUS
import http from 'k6/http';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL;
const RATE = parseInt(__ENV.RATE);
const POST_BASE = parseInt(__ENV.POST_BASE);
const POST_COUNT = parseInt(__ENV.POST_COUNT);
const USER_BASE = parseInt(__ENV.USER_BASE);
const TOPIC = __ENV.TOPIC || '';
const KEY = __ENV.IDENTITY_KEY;

const ok = new Counter('like_ok');
const e4 = new Counter('like_4xx');
const e5 = new Counter('like_5xx');
const e0 = new Counter('like_transport_err');

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    likes: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: __ENV.DURATION || '60s',
      preAllocatedVUs: Math.min(RATE * 2, parseInt(__ENV.MAX_VUS || '400')),
      maxVUs: parseInt(__ENV.MAX_VUS || '400'),
      gracefulStop: '30s',
    },
  },
};

function token(user) {
  const now = Date.now();
  const body = JSON.stringify({ version: 1, reader: user, issuedAt: now, expiresAt: now + 600000, role: 'READER' });
  const b = encoding.b64encode(body, 'rawurl');
  const sig = crypto.hmac('sha256', KEY, 'IDENTITY\n' + b, 'base64rawurl');
  return b + '.' + sig;
}

export default function () {
  const i = exec.scenario.iterationInTest;
  const post = POST_BASE + (i % POST_COUNT);
  const user = USER_BASE + Math.floor(i / POST_COUNT);
  const q = TOPIC ? `?topic=${encodeURIComponent(TOPIC)}` : '';
  const res = http.put(`${BASE}/api/posts/${post}/likes${q}`, null, {
    headers: { Authorization: `Bearer ${token(user)}` }, timeout: '60s',
  });
  if (res.status >= 200 && res.status < 300) ok.add(1);
  else if (res.status >= 500) e5.add(1);
  else if (res.status >= 400) e4.add(1);
  else e0.add(1);
}
