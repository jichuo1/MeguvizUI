#!/usr/bin/env python3
"""Build the P2 Maven-only consumer and record source-bound host evidence."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

CONSUMER = Path(__file__).resolve().parent
ROOT = CONSUMER.parents[1]
sys.path.insert(0, str(ROOT / "build-logic/verification"))
from gates import artifact_hashes, source_hash, write  # noqa: E402


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    started = time.time()
    receipt_path = ROOT / "build/reports/gates/p2-consumer.json"
    log = CONSUMER / "build/reports/p2-consumer" / f"build-{time.time_ns()}.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    checks: list[str] = []
    errors: list[str] = []
    outputs: dict[str, str] = {}
    artifacts: dict[str, str] = {}
    fingerprint = source_hash(ROOT)
    try:
        prepare = subprocess.run([sys.executable, str(CONSUMER / "prepare_artifacts.py")], cwd=ROOT,
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8")
        if prepare.returncode:
            raise RuntimeError(f"Artifact preparation failed: {prepare.stdout.strip()}")
        checks.append("published artifacts match current module binaries and POM coordinates")
        fingerprint = source_hash(ROOT)
        artifacts = artifact_hashes(ROOT)
        inputs_path = CONSUMER / "build/artifact-inputs.json"
        inputs = json.loads(inputs_path.read_text("utf-8"))
        candidates: dict[str, str] = {}
        for artifact, record in inputs["artifacts"].items():
            base = ROOT / "build/repo/io/github/jichuo1/meguviz" / artifact / inputs["version"]
            for filename, expected in record["files"].items():
                path = base / filename
                if not path.is_file() or sha(path) != expected:
                    raise RuntimeError(f"Published candidate changed after preparation: {artifact}/{filename}")
                candidates[path.relative_to(ROOT).as_posix()] = expected
            checks.append(f"published candidate {artifact} verified")
        env = os.environ.copy()
        if os.name == "nt":
            temporary = ROOT / ".tools/tmp"
            temporary.mkdir(parents=True, exist_ok=True)
            option = '"-Djdk.net.unixdomain.tmpdir=' + temporary.as_posix() + '"'
            env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") + " " + option).strip()
            wrapper = ["cmd.exe", "/d", "/c", "gradlew.bat"]
        else:
            wrapper = ["bash", "gradlew"]
        command = wrapper + ["-p", "samples/p2-consumer", ":app:assembleDebug", ":app:assembleRelease",
                             ":app:lintDebug", "--no-daemon", "--max-workers=2", "--console=plain"]
        with log.open("w", encoding="utf-8") as stream:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError(f"Independent consumer Gradle build exited {result.returncode}; see {log}")
        checks.append("strict Gradle debug and minified release build")
        generated = list((CONSUMER / "app/build/generated/ksp").glob("*/kotlin/**/*UiGenerated.kt"))
        if not generated or not any("independent.generated.label" in p.read_text("utf-8") for p in generated):
            raise RuntimeError("KSP did not produce the expected independent factory")
        checks.append("KSP factory generated from the published processor")
        files = [
            CONSUMER / "app/build/outputs/apk/debug/app-debug.apk",
            CONSUMER / "app/build/outputs/apk/release/app-release-unsigned.apk",
            CONSUMER / "app/build/outputs/mapping/release/mapping.txt",
            CONSUMER / "gradle/verification-metadata.xml",
            inputs_path,
        ]
        for path in files:
            if not path.is_file() or not path.stat().st_size:
                raise RuntimeError(f"Missing or empty consumer output: {path}")
            outputs[path.relative_to(ROOT).as_posix()] = sha(path)
            checks.append(f"verified output {path.relative_to(ROOT).as_posix()}")
        lint = CONSUMER / "app/build/reports/lint-results-debug.xml"
        if not lint.is_file() or lint.stat().st_mtime < started - 2:
            raise RuntimeError("Missing or stale independent consumer lint report")
        if any(issue.get("severity") in {"Warning", "Error", "Fatal"} for issue in ET.parse(lint).getroot()):
            raise RuntimeError("Independent consumer lint contains findings")
        checks.append("independent consumer lint")
        if source_hash(ROOT) != fingerprint:
            raise RuntimeError("Root source changed during independent consumer verification")
        if artifact_hashes(ROOT) != artifacts:
            raise RuntimeError("Root release artifacts changed during independent consumer verification")
        for relative, expected in candidates.items():
            if not (ROOT / relative).is_file() or sha(ROOT / relative) != expected:
                raise RuntimeError(f"Published candidate changed during independent consumer verification: {relative}")
            if relative not in artifacts:
                outputs[relative] = expected
        checks.append("source and candidate hashes unchanged through build")
    except (OSError, ValueError, RuntimeError, ET.ParseError) as failure:
        errors.append(str(failure))
    if not log.exists():
        log.write_text("Gradle was not started; see receipt errors.\n", encoding="utf-8")
    relative_log = log.relative_to(ROOT).as_posix()
    receipt = {
        "schema_version": 1,
        "gate": "p2-consumer",
        "status": "passed" if not errors and checks else "blocked",
        "ready": bool(checks) and not errors,
        "source_sha256": fingerprint,
        "profiles": ["core", "plan", "recycler", "text", "layouts", "prewarm", "codegen"],
        "check_count": len(checks),
        "test_count": 0,
        "checks": checks,
        "artifacts": artifacts,
        "outputs": outputs,
        "reports": [relative_log],
        "report_sha256": {relative_log: sha(log)},
        "started_epoch": started,
        "finished_epoch": time.time(),
        "errors": errors,
        "scope": "Independent Maven-only KSP and R8 host build; no device execution or performance conclusion",
    }
    write(receipt_path, receipt)
    print(json.dumps({"status": receipt["status"], "check_count": receipt["check_count"],
                      "errors": errors, "receipt": str(receipt_path)}, ensure_ascii=False))
    return 0 if receipt["ready"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
