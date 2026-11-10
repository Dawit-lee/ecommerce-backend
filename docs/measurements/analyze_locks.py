"""Compare no lock / @Version + 3 attempts / pessimistic lock, all measured in one session (warmup excluded).

Usage: python3 -I docs/measurements/analyze_locks.py
Reads raw/lock_comparison/distributed_<scenario>_<config>_{runs,products,requests}.csv and writes
raw/lock_comparison/summary.csv. Oversell / lost-update figures use the same formulas as analyze_nolock.py.
"failed_with_stock_left" counts every non-success order (any reason) whose cart products all still had
stock >= 1 at the end of the run (a lower bound, as in the distributed analysis).
"""
import csv
import math
import os
import statistics as st
from collections import defaultdict

RAW = os.path.join(os.path.dirname(os.path.abspath(__file__)), "raw", "lock_comparison")
LOCKS = ["nolock", "a3", "pessimistic"]
POOLS = [10, 20]
SCENARIOS = ["A", "B", "C", "D", "E", "F"]
KEYS = ["success", "out_of_stock", "retry_exhausted", "deadlock", "other", "failed_with_stock_left",
        "optimistic_failures", "sold", "lost", "oversold_units", "inconsistent_products", "wall_ms"]


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
        for pool in POOLS:
            for lock in LOCKS:
                base = os.path.join(RAW, f"distributed_{scenario}_{lock}_pool{pool}")
                if not os.path.exists(base + "_runs.csv"):
                    continue
                runs, prods, reqs = read(base + "_runs.csv"), read(base + "_products.csv"), read(base + "_requests.csv")
                for name in dict.fromkeys(r["scenario"] for r in runs):  # F has F_2, F_5, ...
                    per_run = defaultdict(lambda: defaultdict(float))
                    for p in (p for p in prods if p["scenario"] == name):
                        r = per_run[p["run"]]
                        initial, final, sold = int(p["initial_stock"]), int(p["final_stock"]), int(p["ordered_qty_db"])
                        r["sold"] += sold
                        r["lost"] += sold - (initial - final)
                        r["oversold_units"] += max(0, sold - initial)
                        r["inconsistent_products"] += final < 0 or initial - final != sold
                    for q in (q for q in reqs if q["scenario"] == name):
                        per_run[q["run"]]["failed_with_stock_left"] += (
                            q["result"] != "SUCCESS" and q["stock_left_for_all_items"] == "true")
                    checks = True
                    for run in (r for r in runs if r["scenario"] == name):
                        r = per_run[run["run"]]
                        r["success"] = int(run["success"])
                        r["out_of_stock"] = int(run["out_of_stock"])
                        r["retry_exhausted"] = int(run["retry_exhausted"])
                        r["deadlock"] = int(run["other_deadlock"])
                        r["other"] = int(run["other"])
                        r["optimistic_failures"] = int(run["optimistic_failures"])
                        r["wall_ms"] = float(run["wall_ms"])
                        checks &= run["orders_in_db_eq_success"] == "true" and run["ordered_qty_eq_success_items"] == "true"
                        checks &= int(run["refund_calls"]) == int(run["orders"]) - int(run["success"])
                    row = {"scenario": name, "lock": lock, "pool": pool, "runs": len(per_run),
                           "orders": next(r["orders"] for r in runs if r["scenario"] == name)}
                    for key in KEYS:
                        for k, v in agg([per_run[r][key] for r in per_run]).items():
                            row[f"{key}_{k}"] = v
                    lat = sorted(float(q["latency_ms"]) for q in reqs if q["scenario"] == name)
                    row["latency_avg_ms"] = round(st.mean(lat), 2)
                    row["latency_p50_ms"] = pct(lat, 50)
                    row["latency_p95_ms"] = pct(lat, 95)
                    row["latency_max_ms"] = lat[-1]
                    row["consistency_checks_pass"] = checks
                    out.append(row)

    with open(os.path.join(RAW, "summary.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0].keys()))
        w.writeheader()
        w.writerows(out)

    m = lambda r, k: f"{r[k + '_mean']:g}[{r[k + '_min']:g}-{r[k + '_max']:g}]"
    for r in out:
        print(r["scenario"], r["lock"], r["pool"], "succ", m(r, "success"), "oos", m(r, "out_of_stock"),
              "exh", m(r, "retry_exhausted"), "dl", m(r, "deadlock"), "oth", m(r, "other"),
              "failStock", m(r, "failed_with_stock_left"), "opt", m(r, "optimistic_failures"),
              "lost", m(r, "lost"), "overU", m(r, "oversold_units"), "incons", m(r, "inconsistent_products"),
              "wall", r["wall_ms_mean"], "lat", r["latency_avg_ms"], r["latency_p50_ms"], r["latency_p95_ms"],
              "chk", r["consistency_checks_pass"])


if __name__ == "__main__":
    main()
