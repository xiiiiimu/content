// Response classification for ingress-qps.k6.js (imported by it and by test-ingress-qps-classify.k6.js).
// k6 also sets error_code for HTTP 4xx/5xx (14xx/15xx), so only status 0 (no HTTP response) is a network failure:
// error_code 1050 = request timeout, anything else = connection failure.
export function classify(status, errorCode, code, perStatus) {
  if (status === 0) return errorCode === 1050 ? 'timeout' : 'conn';
  if (status >= 500) return status === 503 ? '503' : '5xx';
  if (status === 200) return 'accepted';
  if (status >= 200 && status < 300) return 'unexpected2xx';
  if (status === 409 && code === 'SOLD_OUT') return 'soldOut';
  if (perStatus.indexOf(status) >= 0) return String(status);
  return '4xxOther';
}
