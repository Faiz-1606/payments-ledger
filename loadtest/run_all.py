#!/usr/bin/env python3
"""
Runs every k6 scenario against every locking mode and records the results.

For each (mode, scenario):
  1. start a FRESH stack (app + PostgreSQL) with LEDGER_LOCKING=<mode>   (docker compose)
  2. count transfer rows in the database
  3. run the k6 scenario in a container on the compose network           (grafana/k6)
  4. count transfer rows again, read the optimistic-retry metric
  5. run reconciliation through the admin API and record any ledger drift
  6. compare "transfer rows created" with "transfers k6 saw succeed" -> duplicates / lost

Outputs:
  loadtest/results/raw/<mode>-<scenario>.json   k6 end-of-test summary (raw numbers)
  loadtest/results/summary.json                 derived numbers for every run + machine specs
  loadtest/RESULTS.md                           the table between the RESULTS markers is rewritten

Usage (needs Docker and Python 3.9+; no other dependencies):
  python loadtest/run_all.py                         # everything, 60s per scenario
  python loadtest/run_all.py --duration 30s --modes pessimistic --scenarios hot-accounts
"""
import argparse
import datetime
import json
import os
import platform
import re
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOADTEST = ROOT / "loadtest"
RESULTS = LOADTEST / "results"
COMPOSE_FILES = [ROOT / "docker-compose.core.yml", LOADTEST / "docker-compose.loadtest.yml"]
COMPOSE_PROJECT = "payments-ledger-core"
NETWORK = COMPOSE_PROJECT + "_default"
K6_IMAGE = "grafana/k6:2.4.0"
HTTP_PORT = os.environ.get("LEDGER_HTTP_PORT", "18080")
DB_PORT = os.environ.get("LEDGER_DB_PORT", "15432")
ADMIN_EMAIL = "admin@ledger.local"          # must match docker-compose.core.yml
ADMIN_PASSWORD = "admin-password-local"

MODES = ["naive", "pessimistic", "optimistic"]
SCENARIOS = {"hot-accounts": 32, "retry-storm": 32, "mixed": 64}  # scenario -> virtual users


def run(cmd, env=None, capture=False, check=True):
    printable = " ".join(str(c) for c in cmd)
    print(f"  $ {printable[:160]}", flush=True)
    result = subprocess.run([str(c) for c in cmd], env=env, cwd=ROOT, text=True,
                            capture_output=capture, encoding="utf-8")
    if check and result.returncode != 0:
        if capture:
            print(result.stdout, result.stderr)
        raise SystemExit(f"command failed ({result.returncode}): {printable}")
    return result.stdout.strip() if capture else result.returncode


def compose_cmd(*args):
    cmd = ["docker", "compose", "-p", COMPOSE_PROJECT]
    for f in COMPOSE_FILES:
        cmd += ["-f", f]
    return cmd + list(args)


def stack_env(mode):
    env = dict(os.environ)
    env.update({"LEDGER_LOCKING": mode, "LEDGER_HTTP_PORT": HTTP_PORT, "LEDGER_DB_PORT": DB_PORT})
    return env


def psql(env, sql):
    out = run(compose_cmd("exec", "-T", "postgres", "psql", "-U", "ledger", "-d", "ledger", "-tAc", sql),
              env=env, capture=True)
    return out.strip()


def http(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(f"http://localhost:{HTTP_PORT}{path}", data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.status, json.loads(response.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, None


def admin_token():
    status, body = http("POST", "/api/auth/login", {"email": ADMIN_EMAIL, "password": ADMIN_PASSWORD})
    if status != 200:
        raise SystemExit(f"admin login failed: {status}")
    return body["accessToken"]


def lock_retries(token):
    status, body = http("GET", "/actuator/metrics/ledger.lock.retries", token=token)
    if status != 200:
        return 0  # metric not registered: no retry ever happened
    return int(sum(m["value"] for m in body["measurements"] if m["statistic"] == "COUNT"))


def metric(metrics, name, field, default=0):
    value = metrics.get(name, {})
    value = value.get("values", value)  # tolerate both summary layouts
    return value.get(field, default)


class CpuSampler:
    """Samples `docker stats` for the app and postgres containers while k6 runs."""

    def __init__(self):
        self.samples = {"app": [], "postgres": []}
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self._loop, daemon=True)

    def _loop(self):
        while not self.stop.is_set():
            out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}}|{{.CPUPerc}}"],
                                 capture_output=True, text=True).stdout
            for line in out.splitlines():
                name, _, cpu = line.partition("|")
                for service in self.samples:
                    if name.startswith(f"{COMPOSE_PROJECT}-{service}-"):
                        try:
                            self.samples[service].append(float(cpu.strip().rstrip("%")))
                        except ValueError:
                            pass
            self.stop.wait(3)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.stop.set()
        self.thread.join(timeout=30)

    def mean(self, service):
        # Drop the first sample (seeding) when there are enough; % is of ONE core (400% = 4 cores).
        values = self.samples[service][1:] or self.samples[service]
        return round(statistics.mean(values)) if values else None


def duration_seconds(text):
    match = re.fullmatch(r"(\d+)(s|m)", text)
    if not match:
        raise SystemExit("--duration must look like 60s or 2m")
    return int(match.group(1)) * (60 if match.group(2) == "m" else 1)


def run_one(mode, scenario, vus, duration):
    env = stack_env(mode)
    print(f"\n=== {mode} / {scenario} ({vus} VUs, {duration}) ===", flush=True)
    run(compose_cmd("down", "-v", "--remove-orphans"), env=env)
    run(compose_cmd("up", "-d", "--wait"), env=env)

    before = int(psql(env, "select count(*) from transfers where type = 'TRANSFER'"))
    raw_file = RESULTS / "raw" / f"{mode}-{scenario}.json"
    raw_file.parent.mkdir(parents=True, exist_ok=True)
    if raw_file.exists():
        raw_file.unlink()
    started = time.time()
    with CpuSampler() as cpu:
        exit_code = run(["docker", "run", "--rm", "--network", NETWORK,
                         "-v", f"{LOADTEST}:/scripts",
                         "-e", "BASE_URL=http://app:8080", "-e", f"ADMIN_EMAIL={ADMIN_EMAIL}",
                         "-e", f"ADMIN_PASSWORD={ADMIN_PASSWORD}", "-e", f"VUS={vus}", "-e", f"DURATION={duration}",
                         K6_IMAGE, "run", "--quiet", "--summary-export", f"/scripts/results/raw/{raw_file.name}",
                         f"/scripts/{scenario}.js"], env=env, check=False)
    wall = time.time() - started
    after = int(psql(env, "select count(*) from transfers where type = 'TRANSFER'"))

    token = admin_token()
    retries = lock_retries(token)
    status, report = http("POST", "/api/admin/reconciliation/runs", token=token)
    if status != 200:
        raise SystemExit(f"reconciliation failed: {status}")

    m = json.loads(raw_file.read_text(encoding="utf-8"))["metrics"]
    seconds = duration_seconds(duration)
    if exit_code != 0 or metric(m, "http_req_duration{name:transfer}", "count") == 0:
        raise SystemExit(f"k6 run for {mode}/{scenario} failed (exit {exit_code}); not recording empty numbers")
    transfer_requests = metric(m, "http_req_duration{name:transfer}", "count")
    logical_ok = metric(m, "logical_transfers_ok", "count")
    db_rows = after - before
    result = {
        "mode": mode,
        "scenario": scenario,
        "vus": vus,
        "duration_s": seconds,
        "wall_clock_s": round(wall, 1),
        "requests_measured": int(metric(m, "http_reqs", "count")
                                 - metric(m, "http_reqs{name:seed}", "count")
                                 - metric(m, "http_reqs{name:login}", "count")),
        "total_requests_per_s": round((metric(m, "http_reqs", "count")
                                       - metric(m, "http_reqs{name:seed}", "count")
                                       - metric(m, "http_reqs{name:login}", "count")) / seconds, 1),
        "transfer_requests": int(transfer_requests),
        "transfer_requests_per_s": round(transfer_requests / seconds, 1),
        "ok_transfers_per_s": round(logical_ok / seconds, 1),
        "transfer_p50_ms": round(metric(m, "http_req_duration{name:transfer}", "med"), 2),
        "transfer_p95_ms": round(metric(m, "http_req_duration{name:transfer}", "p(95)"), 2),
        "transfer_p99_ms": round(metric(m, "http_req_duration{name:transfer}", "p(99)"), 2),
        "transfer_max_ms": round(metric(m, "http_req_duration{name:transfer}", "max"), 2),
        "read_p95_ms": round(metric(m, "http_req_duration{name:get_account}", "p(95)"), 2),
        "read_p99_ms": round(metric(m, "http_req_duration{name:get_account}", "p(99)"), 2),
        "app_cpu_avg_pct": cpu.mean("app"),
        "postgres_cpu_avg_pct": cpu.mean("postgres"),
        "transfer_error_rate": round(metric(m, "transfer_errors", "value"), 4),
        "status_409": int(metric(m, "status_409", "count")),
        "status_422": int(metric(m, "status_422", "count")),
        "status_5xx": int(metric(m, "status_5xx", "count")),
        "transfers_created": int(metric(m, "transfers_created", "count")),
        "transfers_replayed": int(metric(m, "transfers_replayed", "count")),
        "logical_transfers_ok": int(logical_ok),
        "logical_transfers_failed": int(metric(m, "logical_transfers_failed", "count")),
        "db_transfer_rows_created": db_rows,
        # > 0: more transfers in the DB than clients saw succeed (duplicates).
        # < 0: clients saw success that the DB does not have (lost).  Expected: 0.
        "db_rows_minus_ok_transfers": db_rows - int(logical_ok),
        "client_detected_duplicates": int(metric(m, "client_detected_duplicates", "count")),
        "optimistic_lock_retries": retries,
        "reconciliation_healthy": report["healthy"],
        "drifted_accounts": len(report["driftedAccounts"]),
        "total_abs_drift_paise": report["totalAbsoluteDriftPaise"],
        "ledger_total_paise": report["ledgerTotalPaise"],
        "unbalanced_transfers": len(report["unbalancedTransferIds"]),
        "negative_accounts": len(report["negativeAccountIds"]),
    }
    print(json.dumps(result, indent=2), flush=True)
    return result


def powershell(command):
    return subprocess.run(["powershell", "-NoProfile", "-Command", command], capture_output=True,
                          text=True).stdout.strip()


def machine_specs():
    specs = {"os": platform.platform(), "logical_cpus": os.cpu_count()}
    system = platform.system()
    try:
        if system == "Windows":
            specs["cpu"] = powershell("(Get-CimInstance Win32_Processor | Select-Object -First 1).Name")
            ram = powershell("(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory")
            specs["ram_gb"] = round(int(ram) / 1024 ** 3, 1)
        elif system == "Linux":
            cpuinfo = Path("/proc/cpuinfo").read_text()
            specs["cpu"] = re.search(r"model name\s*:\s*(.*)", cpuinfo).group(1)
            mem_kb = int(re.search(r"MemTotal:\s*(\d+)", Path("/proc/meminfo").read_text()).group(1))
            specs["ram_gb"] = round(mem_kb / 1024 ** 2, 1)
        elif system == "Darwin":
            specs["cpu"] = subprocess.run(["sysctl", "-n", "machdep.cpu.brand_string"], capture_output=True,
                                          text=True).stdout.strip()
            mem = subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True, text=True).stdout
            specs["ram_gb"] = round(int(mem) / 1024 ** 3, 1)
    except Exception as e:  # specs are best-effort; never fail the run for them
        specs["spec_error"] = str(e)
    docker = run(["docker", "info", "--format", "{{.ServerVersion}}|{{.NCPU}}|{{.MemTotal}}|{{.OperatingSystem}}"],
                 capture=True).split("|")
    specs["docker"] = {"version": docker[0], "cpus": int(docker[1]),
                       "memory_gb": round(int(docker[2]) / 1024 ** 3, 1), "os": docker[3]}
    specs["limits"] = "app: 4 CPUs / 2 GB, postgres: 4 CPUs / 2 GB, k6: unlimited (same machine)"
    specs["k6"] = run(["docker", "run", "--rm", K6_IMAGE, "version"], capture=True).splitlines()[0]
    specs["git_commit"] = run(["git", "rev-parse", "--short", "HEAD"], capture=True)
    return specs


def table(results):
    lines = [
        "| Mode | Scenario | VUs | Transfer req/s | OK transfers/s | p50 ms | p95 ms | p99 ms "
        "| Transfer error rate | 409 | 5xx | Optimistic retries | Drifted accounts | Abs. drift (₹) "
        "| Ledger sum | DB rows − OK transfers | Duplicate ids seen |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in results:
        lines.append(
            f"| {r['mode']} | {r['scenario']} | {r['vus']} | {r['transfer_requests_per_s']} "
            f"| {r['ok_transfers_per_s']} | {r['transfer_p50_ms']} | {r['transfer_p95_ms']} "
            f"| {r['transfer_p99_ms']} | {r['transfer_error_rate'] * 100:.2f}% | {r['status_409']} "
            f"| {r['status_5xx']} | {r['optimistic_lock_retries']} | {r['drifted_accounts']} "
            f"| {r['total_abs_drift_paise'] / 100:,.2f} | {r['ledger_total_paise']} "
            f"| {r['db_rows_minus_ok_transfers']} | {r['client_detected_duplicates']} |")
    lines += [
        "",
        "| Mode | Scenario | All req/s | Balance-read p95 ms | Balance-read p99 ms | App CPU avg | PostgreSQL CPU avg |",
        "|---|---|---|---|---|---|---|",
    ]
    for r in results:
        reads = r["scenario"] == "mixed"
        lines.append(
            f"| {r['mode']} | {r['scenario']} | {r['total_requests_per_s']} "
            f"| {r['read_p95_ms'] if reads else '-'} | {r['read_p99_ms'] if reads else '-'} "
            f"| {r['app_cpu_avg_pct']}% | {r['postgres_cpu_avg_pct']}% |")
    lines.append("")
    lines.append("CPU % is of one core, sampled every ~3 s with `docker stats`; each container is capped at 400%.")
    return "\n".join(lines)


def write_results_md(results, specs, started_at):
    path = LOADTEST / "RESULTS.md"
    text = path.read_text(encoding="utf-8")
    d = specs["docker"]
    block = "\n".join([
        "<!-- RESULTS:START (generated by loadtest/run_all.py, do not edit by hand) -->",
        f"Run started {started_at} UTC, git commit `{specs['git_commit']}`, {results[0]['duration_s']}s per scenario.",
        "",
        f"- **Host:** {specs.get('cpu', '?')}, {specs.get('logical_cpus')} logical CPUs, "
        f"{specs.get('ram_gb', '?')} GB RAM, {specs['os']}",
        f"- **Docker:** {d['version']} ({d['os']}), {d['cpus']} CPUs / {d['memory_gb']} GB available to containers",
        f"- **Limits:** {specs['limits']}",
        f"- **Load generator:** {specs['k6']}",
        "",
        table(results),
        "",
        "Raw k6 summaries: `loadtest/results/raw/`. All derived numbers: `loadtest/results/summary.json`.",
        "<!-- RESULTS:END -->",
    ])
    text = re.sub(r"<!-- RESULTS:START.*?<!-- RESULTS:END -->", lambda _: block, text, flags=re.S)
    path.write_text(text, encoding="utf-8", newline="\n")


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")  # Windows consoles default to cp1252 (no rupee sign)
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--modes", nargs="+", default=MODES, choices=MODES)
    parser.add_argument("--scenarios", nargs="+", default=list(SCENARIOS), choices=list(SCENARIOS))
    parser.add_argument("--duration", default="60s")
    parser.add_argument("--skip-build", action="store_true", help="reuse the existing payments-ledger:local image")
    args = parser.parse_args()

    started_at = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M")
    specs = machine_specs()
    print(json.dumps(specs, indent=2))
    if not args.skip_build:
        run(compose_cmd("build", "app"), env=stack_env("pessimistic"))

    results = []
    try:
        for mode in args.modes:
            for scenario in args.scenarios:
                results.append(run_one(mode, scenario, SCENARIOS[scenario], args.duration))
    finally:
        run(compose_cmd("down", "-v", "--remove-orphans"), env=stack_env("pessimistic"), check=False)

    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "summary.json").write_text(
        json.dumps({"started_at": started_at, "machine": specs, "runs": results}, indent=2),
        encoding="utf-8", newline="\n")
    write_results_md(results, specs, started_at)
    print("\n" + table(results))
    print("\nWrote loadtest/results/summary.json and updated loadtest/RESULTS.md")


if __name__ == "__main__":
    sys.exit(main())
