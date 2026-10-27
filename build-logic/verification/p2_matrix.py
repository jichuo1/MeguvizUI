"""Run the P2 behavior matrix on one authorized device and write a fail-closed per-device receipt.

Covers the instrumentation matrix (restoration, accessibility tree, keyboard, font scale, RTL and
module cases), a process-death restore driven through adb, and the R8-minified independent consumer.
System settings are never changed. Real TalkBack listening stays a manual item and is never inferred.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from gates import artifact_hashes, source_hash, write
from p2_performance import parse_statuses

APP_PACKAGE = "io.github.jichuo1.meguviz.samples.p2matrix"
TEST_PACKAGE = APP_PACKAGE + ".test"
RUNNER = TEST_PACKAGE + "/" + APP_PACKAGE + ".P2MatrixInstrumentation"
APP_APK = "samples/p2-matrix/build/outputs/apk/debug/p2-matrix-debug.apk"
TEST_APK = "samples/p2-matrix/build/outputs/apk/androidTest/debug/p2-matrix-debug-androidTest.apk"
CONSUMER_APK = "samples/p2-consumer/app/build/outputs/apk/release/app-release-unsigned.apk"
CONSUMER_PACKAGE = "io.github.jichuo1.meguviz.samples.p2consumer"
CONSUMER_TEXTS = ("Generated label from published artifacts", "Plan label", "Published RecyclerView adapter")
CASES = (
    "rotation_restores_model_scroll_focus_ids", "activity_recreate_restores_model_scroll_focus_ids",
    "accessibility_tree_labels_targets_heading_order", "keyboard_tab_and_dpad_traversal",
    "font_scale_2_activity_no_clipping_targets", "rtl_activity_mirrors_rows_and_list",
    "recycler_attached_animator_burst_while_scrolling", "recycler_dropped_payload_full_bind",
    "recycler_focus_kept_after_diff", "text_precomputed_equals_plain_layout_matrix",
    "layouts_font_scale_rtl_geometry_targets_focus_baseline", "plan_rtl_font_scale_geometry_and_accessibility_order",
    "prewarm_low_memory_trim_releases_staged_page", "prewarm_rotation_resource_change_releases_staged_page",
    "prewarm_home_detach_and_unfinished_page_fallback",
)
MANUAL = ("TalkBack spoken output, earcons and gestures on every P2 surface (needs a human listener with TalkBack enabled)",
          "Switch Access / external keyboard on a physical keyboard, beyond injected key events")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_matrix(output: str) -> dict[str, dict]:
    """Returns per-case status, failure stack and evidence; structure errors raise ValueError."""
    events, finals = parse_statuses(output)
    if len(finals) != 1:
        raise ValueError("Instrumentation did not report exactly one final result")
    runner_class = RUNNER.partition("/")[2]
    results: dict[str, dict] = {}
    index, current = 0, None
    for code, fields in events:
        if code == 1:
            if current is not None or fields.get("class") != runner_class:
                raise ValueError("Unexpected case start")
            index += 1
            name = fields.get("test")
            if name not in CASES or name in results or fields.get("current") != str(index) or fields.get("numtests") != str(len(CASES)):
                raise ValueError(f"Unknown, duplicate or misnumbered case: {name}")
            current = {"name": name, "evidence": None}
        elif code == 2:
            if current is None or "matrix" not in fields:
                raise ValueError("Evidence outside a case")
            value = json.loads(fields["matrix"])
            if value.get("case") != current["name"] or current["evidence"] is not None:
                raise ValueError("Evidence for a different case or repeated evidence")
            current["evidence"] = value.get("evidence")
        elif code in (0, -2):
            if current is None or fields.get("test") != current["name"]:
                raise ValueError("Case end without matching start")
            passed = code == 0 and current["evidence"] is not None and "stack" not in fields
            results[current["name"]] = {"status": "passed" if passed else "failed", "evidence": current["evidence"],
                                        "failure": None if passed else (fields.get("stack") or "no evidence recorded")[:4000]}
            current = None
        else:
            raise ValueError(f"Unexpected status code {code}")
    if current is not None or set(results) != set(CASES):
        raise ValueError("Not every matrix case ran to completion")
    if finals != ["-1"] and all(item["status"] == "passed" for item in results.values()):
        raise ValueError("Unsuccessful final result with all cases passing")
    return results


def find_nodes(xml_text: str, **attributes: str) -> list[dict]:
    root = ET.fromstring(xml_text)
    return [node.attrib for node in root.iter("node") if all(node.attrib.get(k) == v for k, v in attributes.items())]


def center(bounds: str) -> tuple[int, int]:
    left, top, right, bottom = map(int, re.findall(r"\d+", bounds))
    return (left + right) // 2, (top + bottom) // 2


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--allow-install", action="store_true", required=True)
    parser.add_argument("--destination", type=Path, help="Receipt path; defaults to build/reports/device/p2-matrix/api<N>.json")
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    adb = args.adb.resolve(strict=True)
    if not re.fullmatch(r"[A-Za-z0-9._:-]+", args.serial):
        parser.error("Invalid device serial")
    started = time.time()
    run = root / "build/reports/device/p2-matrix" / str(time.time_ns())
    run.mkdir(parents=True)
    fingerprint = source_hash(root)
    artifacts = artifact_hashes(root)
    checks: list[dict] = []
    errors: list[str] = []
    outputs: dict[str, str] = {}
    reports: list[Path] = []
    device: dict = {}

    def call(*command: str, timeout: int = 120, check: bool = True) -> str:
        result = subprocess.run([str(adb), "-s", args.serial, *command], capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(f"adb {' '.join(command[:3])} exited {result.returncode}: {result.stderr.strip()[:300]}")
        return result.stdout

    def save(name: str, text: str) -> Path:
        path = run / name
        path.write_text(text, "utf-8")
        reports.append(path)
        return path

    def screenshot(name: str) -> None:
        data = subprocess.run([str(adb), "-s", args.serial, "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
        if data.startswith(b"\x89PNG"):
            path = run / name
            path.write_bytes(data)
            reports.append(path)

    def dump(name: str) -> str:
        call("shell", "uiautomator", "dump", "/data/local/tmp/p2-matrix.xml", timeout=60)
        text = call("exec-out", "cat", "/data/local/tmp/p2-matrix.xml", timeout=30)
        save(name, text)
        return text

    def state_lines() -> list[dict]:
        lines = call("logcat", "-d", "-s", "P2Matrix:I", "-v", "raw", timeout=30).splitlines()
        values = []
        for line in lines:
            if line.startswith("{"):
                try:
                    values.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
        return values

    def wait_state(what: str, condition, timeout: float = 15) -> dict:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            matching = [value for value in state_lines() if condition(value)]
            if matching:
                return matching[-1]
            time.sleep(0.5)
        raise RuntimeError(f"Timed out waiting for app state: {what}")

    try:
        if call("get-state", timeout=20).strip() != "device":
            raise RuntimeError("Selected device is not online and authorized")
        api = int(call("shell", "getprop", "ro.build.version.sdk").strip())
        device = {"serial_sha256": hashlib.sha256(args.serial.encode()).hexdigest(), "api": api,
                  "model": call("shell", "getprop", "ro.product.model").strip(),
                  "manufacturer": call("shell", "getprop", "ro.product.manufacturer").strip(),
                  "android_release": call("shell", "getprop", "ro.build.version.release").strip(),
                  "build_fingerprint_sha256": hashlib.sha256(call("shell", "getprop", "ro.build.fingerprint").strip().encode()).hexdigest(),
                  "emulator": call("shell", "getprop", "ro.kernel.qemu").strip() == "1"}
        for relative in (APP_APK, TEST_APK):
            path = root / relative
            if not path.is_file():
                raise RuntimeError(f"Missing APK: {relative}")
            outputs[relative] = digest(path)
            if "Success" not in call("install", "-r", "-t", str(path), timeout=300):
                raise RuntimeError(f"Install failed: {relative}")

        # 1. Instrumentation matrix.
        # Stream to disk so a hung case is still identifiable from the partial log after a timeout.
        log = run / "instrumentation.log"
        reports.append(log)
        with log.open("w", encoding="utf-8") as stream:
            process = subprocess.Popen([str(adb), "-s", args.serial, "shell", "am", "instrument", "-w", "-r", RUNNER],
                                       stdout=stream, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
            try:
                process.wait(timeout=1500)
            except subprocess.TimeoutExpired:
                process.kill()
                call("shell", "am", "force-stop", APP_PACKAGE, check=False)
                partial = log.read_text("utf-8")
                cases = re.findall(r"^INSTRUMENTATION_STATUS: test=(.*)$", partial, re.M)
                raise RuntimeError(f"Instrumentation timed out; last case started: {cases[-1] if cases else 'none'}")
        output = log.read_text("utf-8")
        for name, result in parse_matrix(output).items():
            checks.append({"id": name, "kind": "instrumentation", **result})

        # 2. Process death and restore through the real task stack.
        component = f"{APP_PACKAGE}/.MatrixActivity"
        try:
            call("shell", "am", "force-stop", APP_PACKAGE)
            call("logcat", "-c")
            call("shell", "am", "start", "-W", "-n", component, timeout=60)
            wait_state("initial list", lambda v: v.get("items") == 100 and not v.get("restored"))
            screen = dump("process-initial.xml")
            target = find_nodes(screen, **{"content-desc": "Toggle row 3"})
            lists = find_nodes(screen, **{"class": "androidx.recyclerview.widget.RecyclerView"})
            if not target or not lists:
                raise RuntimeError("Toggle row 3 or the list is not on screen")
            x, y = center(target[0]["bounds"])
            call("shell", "input", "tap", str(x), str(y))
            wait_state("toggle", lambda v: v.get("toggled") == [3])
            left, top, right, bottom = map(int, re.findall(r"\d+", lists[0]["bounds"]))
            # Stay clear of the screen edges: an upward swipe from the bottom edge is the system home gesture.
            span = bottom - top
            for _ in range(3):
                call("shell", "input", "swipe", str((left + right) // 2), str(top + span * 3 // 4), str((left + right) // 2), str(top + span // 4), "300")
                time.sleep(0.8)
            before = wait_state("scrolled", lambda v: v.get("toggled") == [3] and v.get("firstVisible", 0) >= 5)
            time.sleep(1)
            before = state_lines()[-1]
            screenshot("process-before.png")
            call("shell", "input", "keyevent", "KEYCODE_HOME")
            time.sleep(2)
            # am kill only removes processes the system already treats as killable background; some OEMs
            # keep a just-backgrounded app above that level. Fall back to SIGKILL from the app's own uid
            # (what a low-memory kill delivers) and record which method was needed.
            kill_method = None
            for _ in range(10):
                call("shell", "am", "kill", APP_PACKAGE)
                time.sleep(1)
                if not call("shell", "pidof", APP_PACKAGE, check=False).strip():
                    kill_method = "am kill"
                    break
            if kill_method is None:
                pid = call("shell", "pidof", APP_PACKAGE, check=False).strip()
                call("shell", "run-as", APP_PACKAGE, "kill", "-9", pid, check=False)
                time.sleep(1)
                if call("shell", "pidof", APP_PACKAGE, check=False).strip():
                    raise RuntimeError(f"Process survived am kill and SIGKILL: {pid}")
                kill_method = "run-as kill -9 (am kill did not apply within 10 s)"
            call("logcat", "-c")
            call("shell", "am", "start", "-W", "-n", component, timeout=60)
            after = wait_state("restored", lambda v: v.get("restored") and v.get("items") == 100 and v.get("firstVisible", -1) >= 0)
            time.sleep(1)
            after = [v for v in state_lines() if v.get("restored")][-1]
            screenshot("process-after.png")
            dump("process-after.xml")
            problems = []
            if after["pid"] == before["pid"]:
                problems.append("process was not replaced")
            if after["toggled"] != before["toggled"]:
                problems.append(f"toggles {before['toggled']} -> {after['toggled']}")
            if abs(after["firstVisible"] - before["firstVisible"]) > 1:
                problems.append(f"scroll {before['firstVisible']} -> {after['firstVisible']}")
            checks.append({"id": "process_death_restores_model_and_scroll", "kind": "adb",
                           "status": "failed" if problems else "passed",
                           "evidence": {"before": before, "after": after, "kill_method": kill_method},
                           "failure": "; ".join(problems) or None})
        except (RuntimeError, ValueError, KeyError, subprocess.TimeoutExpired) as failure:
            checks.append({"id": "process_death_restores_model_and_scroll", "kind": "adb", "status": "failed",
                           "evidence": None, "failure": str(failure)})
        finally:
            call("shell", "am", "force-stop", APP_PACKAGE, check=False)

        # 3. R8-minified independent consumer, signed locally with the debug key.
        try:
            unsigned = root / CONSUMER_APK
            if not unsigned.is_file():
                raise RuntimeError("Missing minified consumer APK; run samples/p2-consumer/verify_consumer.py")
            signed = run / "p2-consumer-release-debugsigned.apk"
            sdks = [adb.parents[1]]
            local = root / "local.properties"
            if local.is_file():
                sdks += [Path(line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "/"))
                         for line in local.read_text("utf-8").splitlines() if line.startswith("sdk.dir=")]
            signers = sorted(jar for sdk in sdks for jar in (sdk / "build-tools").glob("*/lib/apksigner.jar"))
            if not signers:
                raise RuntimeError("No apksigner found in the device SDK or local.properties sdk.dir")
            signer = signers[-1]
            keystore = Path.home() / ".android/debug.keystore"
            subprocess.run(["java", "-jar", str(signer), "sign", "--ks", str(keystore), "--ks-pass", "pass:android",
                            "--key-pass", "pass:android", "--ks-key-alias", "androiddebugkey", "--out", str(signed), str(unsigned)],
                           check=True, capture_output=True, timeout=120)
            outputs[CONSUMER_APK] = digest(unsigned)
            installed = call("install", "-r", str(signed), timeout=300, check=False)
            if "Success" not in installed:
                raise RuntimeError(f"Consumer install failed: {installed.strip()[:300]}")
            call("logcat", "-c")
            call("shell", "am", "force-stop", CONSUMER_PACKAGE)
            launch = call("shell", "am", "start", "-W", "-n", f"{CONSUMER_PACKAGE}/.MainActivity", timeout=60)
            time.sleep(2)
            screen = dump("consumer-r8.xml")
            screenshot("consumer-r8.png")
            crash = call("logcat", "-d", "-b", "crash", timeout=30, check=False)
            missing = [text for text in CONSUMER_TEXTS if not find_nodes(screen, text=text)]
            alive = call("shell", "pidof", CONSUMER_PACKAGE, check=False).strip()
            problems = ([f"missing text {missing}"] if missing else []) + ([] if alive else ["process not running"]) + \
                ([f"crash log: {crash.strip()[:300]}"] if CONSUMER_PACKAGE in crash else []) + \
                ([] if "Status: ok" in launch else ["launch not confirmed"])
            checks.append({"id": "codegen_r8_consumer_renders_generated_factory", "kind": "adb",
                           "status": "failed" if problems else "passed", "failure": "; ".join(problems) or None,
                           "evidence": {"texts": list(CONSUMER_TEXTS), "signed_apk_sha256": digest(signed)}})
            call("shell", "am", "force-stop", CONSUMER_PACKAGE, check=False)
        except (RuntimeError, OSError, IndexError, subprocess.SubprocessError) as failure:
            checks.append({"id": "codegen_r8_consumer_renders_generated_factory", "kind": "adb", "status": "failed",
                           "evidence": None, "failure": str(failure)})

        if any(digest(root / name) != sha for name, sha in outputs.items()):
            errors.append("APK changed during the matrix run")
        if source_hash(root) != fingerprint or artifact_hashes(root) != artifacts:
            errors.append("Source or artifacts changed during the matrix run")
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as failure:
        errors.append(str(failure))
    failed = [item["id"] for item in checks if item["status"] != "passed"]
    passed = not errors and not failed and len(checks) == len(CASES) + 2
    receipt = {"schema_version": 1, "gate": "p2-matrix", "status": "passed" if passed else "blocked", "ready": passed,
               "source_sha256": fingerprint, "device": device, "test_count": sum(item["status"] == "passed" for item in checks),
               "check_count": len(checks), "failed": failed, "checks": checks, "manual_pending": list(MANUAL),
               "artifacts": artifacts, "outputs": outputs, "errors": errors,
               "started_epoch": started, "finished_epoch": time.time(),
               "reports": [p.relative_to(root).as_posix() for p in reports],
               "report_sha256": {p.relative_to(root).as_posix(): digest(p) for p in reports}}
    destination = args.destination or root / f"build/reports/device/p2-matrix/api{device.get('api', 'unknown')}.json"
    write(destination, receipt)
    print(json.dumps({"status": receipt["status"], "device": {k: device.get(k) for k in ("api", "model", "emulator")},
                      "passed": receipt["test_count"], "checks": len(checks), "failed": failed, "errors": errors,
                      "receipt": str(destination)}, ensure_ascii=False))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
