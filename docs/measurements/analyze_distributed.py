"""Aggregate raw distributed-scenario CSVs (measured runs only, warmup excluded).

Usage: python3 -I docs/measurements/analyze_distributed.py
Writes raw/distributed_summary.csv and raw/distributed_boundary.csv next to the raw files.
"""
import csv
import glob
import math
import os
import statistics as st
from collections import defaultdict

RAW = os.path.join(os.path.dirname(os.path.abspath(__file__)), "raw")
CONFIGS = ["a3_pool10", "a1_pool10", "a3_pool20", "a1_pool20"]
SCENARIOS = ["A", "B", "C", "D", "E", "F"]
METRICS = ["success", "out_of_stock", "retry_exhausted", "other_deadlock", "other", "exhausted_with_stock_left",
           "oversold_products", "optimistic_failures", "success_attempt1", "success_attempt2", "success_attempt3",
           "total_attempts", "wall_ms", "avg_ms", "p50_ms", "p95_ms", "queue_avg_ms", "queue_p95_ms",
           "hikari_max_active", "hikari_max_pending", "hikari_avg_pending"]
BINS = [(1, 1), (2, 2), (3, 4), (5, 8), (9, 16), (17, 32), (33, 64), (65, 128), (129, 10**9)]


def read(path):
    with open(path, newline="") as f:
        return [r for r in csv.DictReader(f) if r["warmup"] == "false"]


def pct(sorted_vals, p):
    idx = math.ceil(p / 100 * len(sorted_vals)) - 1
    return sorted_vals[max(0, min(idx, len(sorted_vals) - 1))]


def main():
    summary = []
    boundary = []
    for config in CONFIGS:
        for scenario in SCENARIOS:
            runs_path = os.path.join(RAW, f"distributed_{scenario}_{config}_runs.csv")
            if not os.path.exists(runs_path):
                continue
            runs = read(runs_path)
            reqs = read(os.path.join(RAW, f"distributed_{scenario}_{config}_requests.csv"))
            prods = read(os.path.join(RAW, f"distributed_{scenario}_{config}_products.csv"))

            by_name = defaultdict(list)
            for r in runs:
                by_name[r["scenario"]].append(r)
            reqs_by_name = defaultdict(list)
            for r in reqs:
                reqs_by_name[r["scenario"]].append(r)

            for name, rows in by_name.items():
                row = {"config": config, "scenario": name, "runs": len(rows), "orders": rows[0]["orders"],
                       "order_items": rows[0]["order_items"], "threads": rows[0]["threads"],
                       "pool_size": rows[0]["pool_size"], "max_attempts": rows[0]["max_attempts"]}
                for m in METRICS:
                    vals = [float(r[m]) for r in rows]
                    row[m + "_mean"] = round(st.mean(vals), 2)
                    row[m + "_sd"] = round(st.stdev(vals), 2) if len(vals) > 1 else 0
                    row[m + "_min"] = min(vals)
                    row[m + "_max"] = max(vals)
                failures = [int(r["out_of_stock"]) + int(r["retry_exhausted"]) + int(r["other_deadlock"]) + int(r["other"])
                            for r in rows]
                row["check_orders_in_db_eq_success"] = all(r["orders_in_db_eq_success"] == "true" for r in rows)
                row["check_ordered_qty_eq_success_items"] = all(r["ordered_qty_eq_success_items"] == "true" for r in rows)
                row["check_refunds_eq_failures"] = all(int(r["refund_calls"]) == f for r, f in zip(rows, failures))
                lat = sorted(float(r["latency_ms"]) for r in reqs_by_name[name])
                row["pooled_requests"] = len(lat)
                row["pooled_avg_ms"] = round(st.mean(lat), 2)
                row["pooled_p50_ms"] = pct(lat, 50)
                row["pooled_p95_ms"] = pct(lat, 95)
                summary.append(row)

            # Boundary: per product, how many orders hit it vs how many were exhausted while stock remained.
            if scenario in ("B", "C", "D", "A"):
                agg = defaultdict(lambda: [0, 0, 0, 0, 0])  # products, orders, exhausted_with_stock, exhausted, success
                for p in prods:
                    n = int(p["assigned_qty"])
                    if n == 0:
                        continue
                    b = next(b for b in BINS if b[0] <= n <= b[1])
                    a = agg[b]
                    a[0] += 1
                    a[1] += n
                    a[2] += int(p["orders_exhausted_with_stock_left"])
                    a[3] += int(p["orders_exhausted"])
                    a[4] += int(p["orders_success"])
                for b in BINS:
                    if b in agg:
                        a = agg[b]
                        boundary.append({"config": config, "scenario": scenario,
                                         "orders_per_product": f"{b[0]}-{b[1]}" if b[0] != b[1] else str(b[0]),
                                         "product_runs": a[0], "orders": a[1], "success": a[4], "exhausted": a[3],
                                         "exhausted_with_stock_left": a[2],
                                         "exhausted_with_stock_left_rate": round(a[2] / a[1], 4)})

    for name, rows in (("distributed_summary.csv", summary), ("distributed_boundary.csv", boundary)):
        with open(os.path.join(RAW, name), "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)

    cols = ["success", "out_of_stock", "retry_exhausted", "exhausted_with_stock_left", "other_deadlock", "other",
            "oversold_products", "optimistic_failures", "success_attempt1", "success_attempt2", "success_attempt3",
            "avg_ms", "p50_ms", "p95_ms", "queue_avg_ms", "hikari_max_pending"]
    for r in summary:
        parts = [f"{c}={r[c + '_mean']}±{r[c + '_sd']}[{r[c + '_min']:g}..{r[c + '_max']:g}]" for c in cols]
        print(r["config"], r["scenario"], f"n={r['orders']}/{r['order_items']}", *parts,
              f"pooled_avg={r['pooled_avg_ms']} p50={r['pooled_p50_ms']} p95={r['pooled_p95_ms']}",
              f"checks={r['check_orders_in_db_eq_success']},{r['check_ordered_qty_eq_success_items']},{r['check_refunds_eq_failures']}")
    print()
    for b in boundary:
        print(b)


if __name__ == "__main__":
    main()
