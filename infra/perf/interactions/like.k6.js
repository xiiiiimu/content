// Open-model like load: PUT /api/posts/{id}/likes. Each iteration uses a unique (user, post) pair,
// so every request is a real false->true flip (no idempotent no-op likes).
// Env: BASE_URL RATE DURATION POST_BASE POST_COUNT USER_BASE USER_COUNT TOPIC(optional) IDENTITY_KEY MAX_VUS
//
// User mapping (hardening A2, per-user limit 10/s burst 20): users are taken round-robin, user = i % USER_COUNT, so
// each user sends RATE / USER_COUNT likes per second (200 rps / 4000 users = 0.05/s). The post is
// (i + floor(i / USER_COUNT)) % POST_COUNT: consecutive requests still walk the posts in order exactly like the round-1
// mapping (post = i % POST_COUNT), and because (USER_COUNT + 1) % POST_COUNT == 1 for the shipped settings a user's k-th
// visit lands on post (u + k) % POST_COUNT, unique for k < POST_COUNT. Round 1 used user = floor(i / POST_COUNT), which put
// up to 100 likes from one user into half a second.
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
const USER_COUNT = parseInt(__ENV.USER_COUNT || '4000');
const MAX_ITER = RATE * parseInt((__ENV.DURATION || '60s').replace('s', ''));
if ((USER_COUNT + 1) % POST_COUNT !== 1 % POST_COUNT) throw new Error('USER_COUNT+1 must be 1 mod POST_COUNT for unique pairs');
if (Math.ceil(MAX_ITER / USER_COUNT) > POST_COUNT) throw new Error('not enough posts for unique (user, post) pairs');
if (RATE / USER_COUNT >= 10) throw new Error('per-user rate must stay below the 10/s interact limit');
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
  const user = USER_BASE + (i % USER_COUNT);
  const post = POST_BASE + ((i + Math.floor(i / USER_COUNT)) % POST_COUNT);
  const q = TOPIC ? `?topic=${encodeURIComponent(TOPIC)}` : '';
  const res = http.put(`${BASE}/api/posts/${post}/likes${q}`, null, {
    headers: { Authorization: `Bearer ${token(user)}` }, timeout: '60s',
  });
  if (res.status >= 200 && res.status < 300) ok.add(1);
  else if (res.status >= 500) e5.add(1);
  else if (res.status >= 400) e4.add(1);
  else e0.add(1);
}
