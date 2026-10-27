"""Run explicit P2 instrumentation APKs on one authorized device and retain bounded evidence."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

from gates import artifact_hashes, source_hash, write

PROFILES = ["plan", "codegen", "recycler", "text", "layouts", "prewarm"]
CASES = (
    ("plan", "samples/plan-consumer/build/outputs/apk/debug/plan-consumer-debug.apk",
     "samples/plan-consumer/build/outputs/apk/androidTest/debug/plan-consumer-debug-androidTest.apk",
     "io.github.jichuo1.meguviz.samples.plan.test/io.github.jichuo1.meguviz.samples.plan.PlanInstrumentation", 2),
    ("codegen", "samples/generated-consumer/build/outputs/apk/debug/generated-consumer-debug.apk",
     "samples/generated-consumer/build/outputs/apk/androidTest/debug/generated-consumer-debug-androidTest.apk",
     "io.github.jichuo1.meguviz.samples.generated.test/io.github.jichuo1.meguviz.samples.generated.GeneratedInstrumentation", 1),
    ("recycler", None, "ui-recycler/build/outputs/apk/androidTest/debug/ui-recycler-debug-androidTest.apk",
     "io.github.jichuo1.meguviz.ui.recycler.test/io.github.jichuo1.meguviz.ui.recycler.RecyclerInstrumentation", 1),
    ("text", None, "ui-text/build/outputs/apk/androidTest/debug/ui-text-debug-androidTest.apk",
     "io.github.jichuo1.meguviz.ui.text.test/io.github.jichuo1.meguviz.ui.text.TextInstrumentation", 3),
    ("layouts", None, "ui-layouts/build/outputs/apk/androidTest/debug/ui-layouts-debug-androidTest.apk",
     "io.github.jichuo1.meguviz.ui.layouts.test/io.github.jichuo1.meguviz.ui.layouts.RowInstrumentation", 4),
    ("prewarm", None, "ui-prewarm/build/outputs/apk/androidTest/debug/ui-prewarm-debug-androidTest.apk",
     "io.github.jichuo1.meguviz.ui.prewarm.test/io.github.jichuo1.meguviz.ui.prewarm.PrewarmInstrumentation", 2),
)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_instrumentation(output: str, runner: str, expected: int) -> None:
    events = []
    fields = {}
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if separator:
                fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            events.append((int(line.partition(":")[2].strip()), fields))
            fields = {}
    final = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", output, re.M)
    if len(events) != expected * 2 or final != ["-1"]:
        raise RuntimeError("Missing or extra instrumentation events, or unsuccessful final result")
    seen = set()
    expected_class = runner.partition("/")[2]
    for index in range(expected):
        start_code, start = events[index * 2]
        end_code, end = events[index * 2 + 1]
        keys = ("class", "test", "current", "numtests")
        if (start_code != 1 or end_code != 0 or any(start.get(key) != end.get(key) for key in keys)
                or start.get("class") != expected_class or not start.get("test")
                or start.get("current") != str(index + 1) or start.get("numtests") != str(expected)
                or start["test"] in seen):
            raise RuntimeError(f"Invalid or duplicate instrumentation event pair {index + 1}")
        seen.add(start["test"])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--allow-install", action="store_true", required=True)
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    adb = args.adb.resolve(strict=True)
    if not re.fullmatch(r"[A-Za-z0-9._:-]+", args.serial):
        parser.error("Invalid device serial")
    started = time.time()
    run = root / "build/reports/device/p2" / str(time.time_ns())
    run.mkdir(parents=True)
    fingerprint = source_hash(root)
    artifacts = artifact_hashes(root)
    outputs = {}
    reports = []
    errors = []
    checked = []

    def call(*command: str, timeout: int = 180) -> subprocess.CompletedProcess[str]:
        return subprocess.run([str(adb), "-s", args.serial, *command], capture_output=True,
                              text=True, encoding="utf-8", errors="replace", timeout=timeout)

    try:
        device = call("get-state", timeout=20)
        if device.returncode or device.stdout.strip() != "device":
            raise RuntimeError("Selected device is not online and authorized")
        api_result = call("shell", "getprop", "ro.build.version.sdk", timeout=20)
        if api_result.returncode or not api_result.stdout.strip().isdigit():
            raise RuntimeError("Unable to identify device API")
        api = int(api_result.stdout.strip())
        build_result = call("shell", "getprop", "ro.build.fingerprint", timeout=20)
        release_result = call("shell", "getprop", "ro.build.version.release", timeout=20)
        if build_result.returncode or not build_result.stdout.strip() or release_result.returncode:
            raise RuntimeError("Unable to identify device build")
        device_identity = {"serial_sha256": hashlib.sha256(args.serial.encode()).hexdigest(),
                           "build_fingerprint_sha256": hashlib.sha256(build_result.stdout.strip().encode()).hexdigest(),
                           "android_release": release_result.stdout.strip()}
        for name, application, instrumentation, runner, expected in CASES:
            paths = [root / item for item in (application, instrumentation) if item]
            test_package = runner.partition("/")[0]
            packages = ([test_package.removesuffix(".test")] if application else []) + [test_package]
            try:
                for apk, package in zip(paths, packages, strict=True):
                    if not apk.is_file():
                        raise RuntimeError(f"Missing APK: {apk.relative_to(root)}")
                    relative = apk.relative_to(root).as_posix()
                    outputs[relative] = digest(apk)
                    installed_path = call("shell", "pm", "path", package, timeout=20)
                    installed_files = [line.removeprefix("package:").strip() for line in installed_path.stdout.splitlines()
                                       if line.startswith("package:")]
                    if installed_files:
                        if len(installed_files) != 1:
                            raise RuntimeError(f"Existing split package requires separate review: {package}")
                        existing = subprocess.run([str(adb), "-s", args.serial, "exec-out", "cat", installed_files[0]],
                                                  capture_output=True, timeout=60)
                        if existing.returncode or hashlib.sha256(existing.stdout).hexdigest() != outputs[relative]:
                            raise RuntimeError(f"Existing package differs; it was not replaced: {package}")
                        continue
                    if installed_path.returncode not in (0, 1) or installed_path.stdout.strip():
                        raise RuntimeError(f"Could not establish whether package exists: {package}")
                    installed = call("install", "-r", "-t", str(apk), timeout=180)
                    if installed.returncode or "Success" not in installed.stdout:
                        raise RuntimeError(f"Install failed: {relative}: {installed.stdout} {installed.stderr}")
                result = call("shell", "am", "instrument", "-w", "-r", runner, timeout=180)
                log = run / f"{name}.log"
                log.write_text(result.stdout + "\n" + result.stderr, encoding="utf-8")
                reports.append(log)
                if result.returncode:
                    raise RuntimeError(f"ADB instrumentation returned {result.returncode}: {name}")
                check_instrumentation(result.stdout, runner, expected)
                if any(digest(apk) != outputs[apk.relative_to(root).as_posix()] for apk in paths):
                    raise RuntimeError(f"APK changed during test: {name}")
                checked.append({"profile": name, "tests": expected, "api": api})
            except subprocess.TimeoutExpired:
                errors.append(f"Device command timed out for {name}; instrumentation may still be running")
                break
            except (OSError, RuntimeError, ValueError) as failure:
                errors.append(str(failure))
        if source_hash(root) != fingerprint or artifact_hashes(root) != artifacts:
            errors.append("Source or artifacts changed during device testing")
        missing_apis = sorted({27, 29, 37} - {api})
        remaining = [f"Single-device run; other required APIs: {missing_apis}",
                     "OEM, accessibility and the full P2 behavior matrix require separate evidence"]
        receipt = {"schema_version": 1, "gate": "p2-device", "status": "blocked", "ready": False,
                   "source_sha256": fingerprint, "profiles": PROFILES, "device_api": api,
                   "device_identity": device_identity,
                   "test_count": sum(item["tests"] for item in checked), "checks": checked,
                   "artifacts": artifacts, "outputs": outputs, "errors": errors, "remaining": remaining,
                   "started_epoch": started, "finished_epoch": time.time(),
                   "reports": [p.relative_to(root).as_posix() for p in reports],
                   "report_sha256": {p.relative_to(root).as_posix(): digest(p) for p in reports}}
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as failure:
        receipt = {"schema_version": 1, "gate": "p2-device", "status": "blocked", "ready": False,
                   "source_sha256": fingerprint, "profiles": PROFILES, "test_count": 0,
                   "artifacts": artifacts, "outputs": outputs, "errors": [str(failure)],
                   "started_epoch": started, "finished_epoch": time.time(), "reports": [], "report_sha256": {}}
    destination = root / "build/reports/gates/p2-device.json"
    write(destination, receipt)
    print(json.dumps({"status": receipt["status"], "test_count": receipt["test_count"],
                      "checks": receipt.get("checks", []), "errors": receipt["errors"],
                      "remaining": receipt.get("remaining", []), "receipt": str(destination)}, ensure_ascii=False))
    return 1 if receipt["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
