"""Print markdown tables from ../docs/measurements/loadtest-raw/<label>/k6_vus*.json (python3 table.py label...)."""
import json, glob, re, sys

def rows(label):
    for f in sorted(glob.glob(f'../docs/measurements/loadtest-raw/{label}/k6_vus*.json'), key=lambda f: int(re.search(r'vus(\d+)', f)[1])):
        m = json.load(open(f))['metrics']
        d = m['http_req_duration']['values']
        yield (int(re.search(r'vus(\d+)', f)[1]), m['http_reqs']['values']['count'], m['http_reqs']['values']['rate'],
               d['med'], d['p(95)'], d['p(99)'], m['http_req_failed']['values']['rate'] * 100)

for label in sys.argv[1:]:
    print(f'\n**{label}**\n\n| VU | 요청 수 | TPS | p50 (ms) | p95 (ms) | p99 (ms) | 에러율 |\n|---:|---:|---:|---:|---:|---:|---:|')
    for v, n, rps, p50, p95, p99, err in rows(label):
        print(f'| {v} | {n:,} | {rps:,.1f} | {p50:,.1f} | {p95:,.1f} | {p99:,.1f} | {err:.2f}% |')
