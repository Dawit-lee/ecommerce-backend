// Read-heavy product browsing mix (closed model, no think time -> measures max throughput).
// Usage: k6 run -e VUS=50 -e DURATION=60s -e OUT=results/x.json read.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:18080';
const KEYWORDS = ['keyboard', 'mouse', 'monitor', 'laptop', 'camera', 'watch', 'tent', 'mug', 'lamp', 'chair'];

export const options = {
  scenarios: {
    read: { executor: 'constant-vus', vus: Number(__ENV.VUS || 10), duration: __ENV.DURATION || '60s' },
  },
  // dummy thresholds only to make k6 report per-endpoint sub-metrics in the summary
  thresholds: Object.fromEntries(['list', 'category', 'keyword', 'price_sort']
    .map((n) => [`http_req_duration{name:${n}}`, ['max>=0']])),
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const rnd = (n) => Math.floor(Math.random() * n);
const cat = () => 'Category-' + String(1 + rnd(50)).padStart(3, '0');

export default function () {
  const r = Math.random();
  let url, name;
  if (r < 0.4) { name = 'list';     url = `/api/public/products?pageNumber=${rnd(100)}`; }
  else if (r < 0.6) { name = 'category'; url = `/api/public/products?category=${cat()}&pageNumber=${rnd(10)}`; }
  else if (r < 0.8) { name = 'keyword';  url = `/api/public/products?keyword=${KEYWORDS[rnd(KEYWORDS.length)]}&pageNumber=${rnd(10)}`; }
  else { name = 'price_sort'; url = `/api/public/products?sortBy=price&sortOrder=desc&pageNumber=${rnd(100)}`; }
  const res = http.get(BASE + url, { tags: { name } });
  check(res, { 'status 200': (x) => x.status === 200 });
}

export function handleSummary(data) {
  const out = {};
  if (__ENV.OUT) out[__ENV.OUT] = JSON.stringify(data, null, 1);
  const m = data.metrics;
  const line = `VUS=${__ENV.VUS} reqs=${m.http_reqs.values.count} rps=${m.http_reqs.values.rate.toFixed(1)} ` +
    `p95=${m.http_req_duration.values['p(95)'].toFixed(1)}ms avg=${m.http_req_duration.values.avg.toFixed(1)}ms ` +
    `fail=${(m.http_req_failed.values.rate * 100).toFixed(2)}%\n`;
  const per = ['list', 'category', 'keyword', 'price_sort'].map((n) => {
    const v = m[`http_req_duration{name:${n}}`].values;
    return `  ${n}: p95=${v['p(95)'].toFixed(1)}ms avg=${v.avg.toFixed(1)}ms`;
  }).join('\n');
  out.stdout = line + per + '\n';
  return out;
}
