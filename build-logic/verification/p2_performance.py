"""Measure the six P2 profiles on one authorized device and write a fail-closed admission receipt.

Each profile is admitted only by its pre-declared criterion in p2_performance_criteria.json,
computed from raw same-input A/B samples. Missing, partial or throttled runs never pass.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import random
import re
import statistics
import subprocess
import sys
import time
import tomllib

from gates import artifact_hashes, source_hash, write

PROFILES = ("plan", "codegen", "recycler", "text", "layouts", "prewarm")
APP_PACKAGE = "io.github.jichuo1.meguviz.samples.p2benchmark"
TEST_PACKAGE = APP_PACKAGE + ".test"
RUNNER = TEST_PACKAGE + "/" + APP_PACKAGE + ".P2PerformanceInstrumentation"
APP_APK = "samples/p2-benchmark/build/outputs/apk/benchmark/p2-benchmark-benchmark.apk"
TEST_APK = "samples/p2-benchmark/build/outputs/apk/androidTest/benchmark/p2-benchmark-benchmark-androidTest.apk"
CRITERIA = Path(__file__).with_name("p2_performance_criteria.toml")
ADMITTED_MODULES = ("ui-core", "ui-widgets", "ui-plan", "ui-recycler", "ui-text", "ui-layouts", "ui-prewarm", "ui-codegen")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_statuses(output: str) -> tuple[list[tuple[int, dict[str, str]]], list[str]]:
    """Returns status events in order and every INSTRUMENTATION_CODE value."""
    events, finals, fields, last = [], [], {}, None
    for line in output.replace("\r\n", "\n").split("\n"):
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if not separator:
                raise ValueError("Malformed instrumentation status line")
            fields[key] = value
            last = key
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            events.append((int(line.partition(":")[2].strip()), fields))
            fields, last = {}, None
        elif line.startswith("INSTRUMENTATION_CODE:"):
            finals.append(line.partition(":")[2].strip())
        elif line.startswith("INSTRUMENTATION_"):
            last = None
        elif last is not None:
            fields[last] += "\n" + line  # Multi-line values such as stack traces.
    return events, finals


def parse_run(output: str, criteria: dict, samples: int, warmup: int) -> dict[str, dict]:
    events, finals = parse_statuses(output)
    if finals != ["-1"]:
        raise ValueError("Instrumentation did not finish with a single successful result")
    if len(events) != len(PROFILES) * 3:
        raise ValueError("Missing, failed or extra experiment events")
    runner_class = RUNNER.partition("/")[2]
    records: dict[str, dict] = {}
    for index in range(len(PROFILES)):
        (start_code, start), (data_code, data), (end_code, end) = events[index * 3:index * 3 + 3]
        keys = ("class", "test", "current", "numtests")
        if (start_code, data_code, end_code) != (1, 2, 0) or any(start.get(k) != end.get(k) for k in keys):
            raise ValueError(f"Invalid event sequence for experiment {index + 1}")
        if (start.get("class") != runner_class or start.get("current") != str(index + 1)
                or start.get("numtests") != str(len(PROFILES)) or "stack" in end):
            raise ValueError(f"Invalid experiment identity {index + 1}")
        try:
            record = json.loads(data["p2perf"])
        except (KeyError, json.JSONDecodeError) as failure:
            raise ValueError(f"Missing or malformed measurement {index + 1}") from failure
        profile = record.get("profile")
        if profile not in PROFILES or profile in records:
            raise ValueError(f"Unknown or duplicate profile: {profile}")
        rule = criteria["profiles"][profile]
        if (record.get("schema_version") != 1 or record.get("scenario") != rule["scenario"]
                or start.get("test") != f"{profile}_{rule['scenario']}" or record.get("unit") != "ns"
                or record.get("warmup") != warmup or record.get("order") != "ABBA-interleaved"):
            raise ValueError(f"Measurement identity mismatch: {profile}")
        for group in ("A", "B"):
            values = record.get(group, {}).get("samples")
            if (not isinstance(values, list) or len(values) != samples
                    or any(isinstance(v, bool) or not isinstance(v, int) or v <= 0 for v in values)):
                raise ValueError(f"Invalid samples: {profile} {group}")
        records[profile] = record
    if set(records) != set(PROFILES):
        raise ValueError("Not every P2 profile was measured")
    return records


def quantile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(q * len(ordered)) - 1)]


def evaluate(profile: str, record: dict, criteria: dict) -> dict:
    rule = criteria["profiles"][profile]
    a, b = record["A"]["samples"], record["B"]["samples"]
    if min(len(a), len(b)) < criteria["minimum_samples"]:
        raise ValueError(f"Too few samples for {profile}")
    rng = random.Random(f"{criteria['bootstrap_seed']}:{profile}")
    statistic = rule["statistic"]
    if statistic == "paired-ratio":
        # A[i] and B[i] come from the same ABBA iteration; parse_run requires equal lengths and that order.
        if len(a) != len(b):
            raise ValueError(f"Unpaired samples for {profile}")
        pairs = [y / x for x, y in zip(a, b)]
        ratio = statistics.median(pairs)
        ratios = sorted(statistics.median(rng.choices(pairs, k=len(pairs))) for _ in range(criteria["bootstrap_resamples"]))
    elif statistic == "median-ratio":
        ratio = statistics.median(b) / statistics.median(a)
        ratios = sorted(statistics.median(rng.choices(b, k=len(b))) / statistics.median(rng.choices(a, k=len(a)))
                        for _ in range(criteria["bootstrap_resamples"]))
    else:
        raise ValueError(f"Unknown statistic for {profile}: {statistic}")
    low, high = quantile(ratios, 0.025), quantile(ratios, 0.975)
    limit = rule["max_ratio"]
    admitted = high <= limit if rule["kind"] == "bounded-overhead" else high < limit
    summary = lambda v: {"count": len(v), "median_ns": statistics.median(v), "mean_ns": statistics.mean(v),
                         "p95_ns": quantile(v, 0.95) if len(v) >= 100 else None, "min_ns": min(v), "max_ns": max(v)}
    aux = {group: {name: statistics.median(values) for name, values in record[group].get("aux", {}).items() if values}
           for group in ("A", "B")}
    return {"profile": profile, "scenario": rule["scenario"], "metric": record.get("metric"), "kind": rule["kind"],
            "statistic": statistic, "max_ratio": limit, "median_ratio": ratio,
            "unpaired_median_ratio": statistics.median(b) / statistics.median(a), "ratio_ci95": [low, high], "admitted": admitted,
            "A": summary(a) | {"description": record["A"].get("description")},
            "B": summary(b) | {"description": record["B"].get("description")}, "aux_median": aux}


def thermal_status(text: str) -> int:
    values = re.findall(r"^\s*Thermal Status:\s*(\d+)\s*$", text, re.M)
    if not values:
        raise ValueError("Device does not report a thermal status")
    return int(values[0])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--allow-install", action="store_true", required=True)
    parser.add_argument("--samples", type=int, default=300)
    parser.add_argument("--warmup", type=int, default=20)
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    adb = args.adb.resolve(strict=True)
    if not re.fullmatch(r"[A-Za-z0-9._:-]+", args.serial):
        parser.error("Invalid device serial")
    if not 1 <= args.samples <= 10000 or not 0 <= args.warmup <= 1000:
        parser.error("Sample bounds exceeded")
    criteria = tomllib.loads(CRITERIA.read_text("utf-8"))
    started = time.time()
    run = root / "build/reports/device/p2-performance" / str(time.time_ns())
    run.mkdir(parents=True)
    fingerprint = source_hash(root)
    artifacts = artifact_hashes(root)
    admitted_artifacts = {name: sha for name, sha in artifacts.items()
                          if any(f"/{m}" in "/" + name or f"meguviz-{m}" in name for m in ADMITTED_MODULES)}
    errors: list[str] = []
    results: list[dict] = []
    outputs: dict[str, str] = {}
    environment: dict = {}
    reports: list[Path] = []
    commands: list[dict] = []

    def call(*command: str, timeout: int = 120) -> str:
        result = subprocess.run([str(adb), "-s", args.serial, *command], capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=timeout)
        commands.append({"command": list(command), "exit_code": result.returncode})
        if result.returncode:
            raise RuntimeError(f"adb {command[0]} exited {result.returncode}: {result.stderr.strip()[:300]}")
        return result.stdout

    try:
        if criteria.get("schema_version") != 2 or set(criteria.get("profiles", {})) != set(PROFILES):
            raise ValueError("Criteria file does not declare exactly the six P2 profiles")
        if args.samples < criteria["minimum_samples"]:
            raise ValueError("Requested samples are below the declared minimum")
        if not admitted_artifacts:
            raise RuntimeError("No current P2 release artifacts; run verifyP2 first")
        for relative in (APP_APK, TEST_APK):
            path = root / relative
            if not path.is_file():
                raise RuntimeError(f"Missing APK: {relative}")
            outputs[relative] = digest(path)
        if call("get-state", timeout=20).strip() != "device":
            raise RuntimeError("Selected device is not online and authorized")
        api = int(call("shell", "getprop", "ro.build.version.sdk", timeout=20).strip())
        environment = {
            "serial_sha256": hashlib.sha256(args.serial.encode()).hexdigest(),
            "build_fingerprint_sha256": hashlib.sha256(call("shell", "getprop", "ro.build.fingerprint").strip().encode()).hexdigest(),
            "model": call("shell", "getprop", "ro.product.model").strip(),
            "android_release": call("shell", "getprop", "ro.build.version.release").strip(),
            "api": api, "abi": call("shell", "getprop", "ro.product.cpu.abi").strip(),
            "emulator": call("shell", "getprop", "ro.kernel.qemu").strip() == "1",
        }
        if environment["emulator"]:
            raise RuntimeError("Performance admission requires a physical device")
        # Only the harness's own two packages are installed or replaced.
        for relative in (APP_APK, TEST_APK):
            if "Success" not in call("install", "-r", "-t", str(root / relative), timeout=300):
                raise RuntimeError(f"Install failed: {relative}")
        for package in (APP_PACKAGE, TEST_PACKAGE):
            if "Success" not in call("shell", "cmd", "package", "compile", "-m", "speed", "-f", package, timeout=300):
                raise RuntimeError(f"AOT compilation failed: {package}")
        before = call("shell", "dumpsys", "thermalservice")
        (run / "thermal-before.txt").write_text(before, "utf-8")
        battery = call("shell", "dumpsys", "battery")
        (run / "battery-before.txt").write_text(battery, "utf-8")
        reports += [run / "thermal-before.txt", run / "battery-before.txt"]
        environment["thermal_before"] = thermal_status(before)
        output = call("shell", "am", "instrument", "-w", "-r", "-e", "warmup", str(args.warmup),
                      "-e", "samples", str(args.samples), RUNNER, timeout=1800)
        log = run / "instrumentation.log"
        log.write_text(output, "utf-8")
        reports.append(log)
        after = call("shell", "dumpsys", "thermalservice")
        (run / "thermal-after.txt").write_text(after, "utf-8")
        reports.append(run / "thermal-after.txt")
        environment["thermal_after"] = thermal_status(after)
        limit = criteria["max_thermal_status"]
        if max(environment["thermal_before"], environment["thermal_after"]) > limit:
            errors.append(f"Device thermal status exceeded {limit}; samples are not admissible")
        records = parse_run(output, criteria, args.samples, args.warmup)
        results = [evaluate(profile, records[profile], criteria) for profile in PROFILES]
        if any(digest(root / name) != sha for name, sha in outputs.items()):
            errors.append("APK changed during measurement")
        if source_hash(root) != fingerprint or artifact_hashes(root) != artifacts:
            errors.append("Source or artifacts changed during measurement")
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.TimeoutExpired) as failure:
        errors.append(str(failure))
    rejected = [item["profile"] for item in results if not item["admitted"]]
    summary = run / "summary.json"
    summary.write_text(json.dumps({"results": results, "commands": commands}, ensure_ascii=False, indent=2), "utf-8")
    reports.append(summary)
    passed = not errors and len(results) == len(PROFILES) and not rejected
    receipt = {"schema_version": 1, "gate": "p2-performance", "status": "passed" if passed else "blocked",
               "ready": passed, "source_sha256": fingerprint, "profiles": list(PROFILES),
               "test_count": len(results), "samples_per_group": args.samples, "warmup_per_group": args.warmup,
               "criteria_sha256": digest(CRITERIA), "device": environment, "results": results,
               "rejected_profiles": rejected, "artifacts": admitted_artifacts, "outputs": outputs, "errors": errors,
               "remaining": ["Single physical device and API; other devices, OEMs and APIs are unmeasured",
                             "Benchmark build is non-debuggable and AOT speed-compiled but not R8-minified",
                             "Detached hierarchies; draw, frame overrun and heap are not measured",
                             "Each application must still admit a module against its own workload"],
               "started_epoch": started, "finished_epoch": time.time(),
               "reports": [p.relative_to(root).as_posix() for p in reports],
               "report_sha256": {p.relative_to(root).as_posix(): digest(p) for p in reports}}
    destination = root / "build/reports/gates/p2-performance.json"
    write(destination, receipt)
    print(json.dumps({"status": receipt["status"], "errors": errors, "rejected": rejected,
                      "results": [{k: item[k] for k in ("profile", "kind", "statistic", "median_ratio", "ratio_ci95", "max_ratio", "admitted")}
                                  for item in results], "receipt": str(destination)}, ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
