"""Compare the no-lock baseline with the existing @Version results (measured runs only, warmup excluded).

Usage: python3 -I docs/measurements/analyze_nolock.py
Reads raw/distributed_<scenario>_<config>_{runs,products,requests}.csv for
  nolock_pool10 (this baseline), a1_pool10 and a3_pool10 (reused from commit 7f615ce)
and writes raw/nolock_comparison.csv. Oversell/lost-update figures are recomputed from the
products CSV for every config so all three use the same formulas.
"""
import csv
import math
import os
import statistics as st
from collections import defaultdict

RAW = os.path.join(os.path.dirname(os.path.abspath(__file__)), "raw")
CONFIGS = [("nolock_pool10", "락 없음"), ("a1_pool10", "@Version 시도 1회"), ("a3_pool10", "@Version 최대 3회")]
SCENARIOS = ["C", "B", "E", "A", "D"]


def read(path):
    with open(path, newline="") as f:
        return [r for r in csv.DictReader(f) if r["warmup"] == "false"]


def pct(vals, p):
    idx = math.ceil(p / 100 * len(vals)) - 1
    return vals[max(0, min(idx, len(vals) - 1))]


def agg(values):
    return {"mean": round(st.mean(values), 2), "sd": round(st.stdev(values), 2) if len(values) > 1 else 0,
            "min": min(values), "max": max(values)}


def main():
    out = []
    for scenario in SCENARIOS:
        for config, label in CONFIGS:
            base = os.path.join(RAW, f"distributed_{scenario}_{config}")
            if not os.path.exists(base + "_runs.csv"):
                continue
            runs = read(base + "_runs.csv")
            prods = read(base + "_products.csv")
            reqs = read(base + "_requests.csv")

            per_run = defaultdict(lambda: defaultdict(int))
            for p in prods:
                r = per_run[p["run"]]
                initial, final, sold = int(p["initial_stock"]), int(p["final_stock"]), int(p["ordered_qty_db"])
                decremented = initial - final
                r["sold"] += sold
                r["decremented"] += decremented
                r["lost"] += sold - decremented
                r["oversold_units"] += max(0, sold - initial)
                r["negative_stock_products"] += final < 0
                r["mismatch_products"] += decremented != sold
                r["inconsistent_products"] += (final < 0) or (decremented != sold)
            for run in runs:
                r = per_run[run["run"]]
                r["success"] = int(run["success"])
                r["out_of_stock"] = int(run["out_of_stock"])
                r["retry_exhausted"] = int(run["retry_exhausted"])
                r["other"] = int(run["other_deadlock"]) + int(run["other"])
                r["exhausted_with_stock_left"] = int(run["exhausted_with_stock_left"])
                r["optimistic_failures"] = int(run["optimistic_failures"])
                r["orders_in_db_eq_success"] = run["orders_in_db_eq_success"] == "true"
                r["sold_eq_success_items"] = run["ordered_qty_eq_success_items"] == "true"

            row = {"scenario": scenario, "config": config, "label": label, "runs": len(runs),
                   "orders": runs[0]["orders"], "order_items": runs[0]["order_items"],
                   "source": "this baseline" if config.startswith("nolock") else "reused from 7f615ce"}
            for key in ["success", "out_of_stock", "retry_exhausted", "other", "exhausted_with_stock_left",
                        "optimistic_failures", "sold", "decremented", "lost", "oversold_units",
                        "negative_stock_products", "mismatch_products", "inconsistent_products"]:
                a = agg([per_run[r][key] for r in per_run])
                for k, v in a.items():
                    row[f"{key}_{k}"] = v
            row["runs_with_inconsistency"] = sum(1 for r in per_run.values() if r["inconsistent_products"] > 0)
            row["all_orders_in_db_eq_success"] = all(r["orders_in_db_eq_success"] for r in per_run.values())
            row["all_sold_eq_success_items"] = all(r["sold_eq_success_items"] for r in per_run.values())
            lat = sorted(float(r["latency_ms"]) for r in reqs)
            row["latency_avg_ms"] = round(st.mean(lat), 2)
            row["latency_p50_ms"] = pct(lat, 50)
            row["latency_p95_ms"] = pct(lat, 95)
            out.append(row)

    with open(os.path.join(RAW, "nolock_comparison.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0].keys()))
        w.writeheader()
        w.writerows(out)

    for r in out:
        f = lambda k: f"{r[k + '_mean']}±{r[k + '_sd']}[{r[k + '_min']}-{r[k + '_max']}]"
        print(r["scenario"], r["config"], "succ", f("success"), "oos", f("out_of_stock"), "exh", f("retry_exhausted"),
              "other", f("other"), "exhS", f("exhausted_with_stock_left"), "opt", f("optimistic_failures"),
              "sold", f("sold"), "decr", f("decremented"), "lost", f("lost"), "overU", f("oversold_units"),
              "neg", f("negative_stock_products"), "mism", f("mismatch_products"), "incons", f("inconsistent_products"),
              "runsIncons", r["runs_with_inconsistency"], "chk", r["all_orders_in_db_eq_success"], r["all_sold_eq_success_items"],
              "lat", r["latency_avg_ms"], r["latency_p50_ms"], r["latency_p95_ms"])


if __name__ == "__main__":
    main()
