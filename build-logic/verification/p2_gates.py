"""Fail-closed host receipt for all explicitly selected P2 modules."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import time
import xml.etree.ElementTree as ET

from gates import parse_tests, source_hash, write

MODULES = ("plan", "recycler", "text", "layouts", "prewarm")
PROFILES = set(MODULES) | {"codegen"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("start", "finish"))
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--profiles", required=True)
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    profiles = set(filter(None, args.profiles.split(",")))
    receipt_path = root / "build/reports/gates/p2-host.json"
    if args.mode == "start":
        errors = [] if profiles == PROFILES else ["P2 host verification requires all six profiles"]
        receipt = {"schema_version": 1, "gate": "p2-host", "status": "failed" if errors else "running",
                   "ready": False, "profiles": sorted(profiles), "source_sha256": source_hash(root),
                   "started_epoch": time.time(), "errors": errors}
        write(receipt_path, receipt)
        print(json.dumps(receipt, ensure_ascii=False))
        return 2 if errors else 0

    receipt = json.loads(receipt_path.read_text("utf-8")) if receipt_path.is_file() else {}
    started = receipt.get("started_epoch", time.time())
    errors = []
    if receipt.get("status") != "running" or set(receipt.get("profiles", [])) != PROFILES:
        errors.append("Missing current full P2 preflight")
    if receipt.get("source_sha256") != source_hash(root):
        errors.append("Source changed during P2 host verification")
    counts = {}
    all_reports = []
    for name in MODULES:
        reports = list(root.glob(f"ui-{name}/build/test-results/testDebugUnitTest/TEST-*.xml"))
        count, failures = parse_tests(reports, started)
        all_reports.extend(reports)
        counts[name] = count
        errors.extend(f"{name}: {error}" for error in failures)
        lint = root / f"ui-{name}/build/reports/lint-results-debug.xml"
        if not lint.is_file() or lint.stat().st_mtime < started - 2:
            errors.append(f"Missing or stale lint report: {name}")
        aar = root / f"ui-{name}/build/outputs/aar/ui-{name}-release.aar"
        if not aar.is_file():
            errors.append(f"Missing release artifact: {name}")
    for variant in ("debug", "release"):
        generated = list(root.glob(f"samples/generated-consumer/build/generated/ksp/{variant}/kotlin/**/*UiGenerated.kt"))
        apks = list(root.glob(f"samples/generated-consumer/build/outputs/apk/{variant}/*.apk"))
        if not generated or not apks:
            errors.append(f"Missing real generated source or {variant} APK")
    codegen_lint = root / "samples/generated-consumer/build/reports/lint-results-debug.xml"
    if not codegen_lint.is_file() or codegen_lint.stat().st_mtime < started - 2:
        errors.append("Missing or stale generated consumer lint report")
    api_receipt = root / "build/reports/p2-api/receipt.json"
    api = json.loads(api_receipt.read_text("utf-8")) if api_receipt.is_file() else {}
    if (api.get("status") != "passed" or api.get("started_epoch", 0) < started or
            len(api.get("checks", [])) != 7):
        errors.append("Missing, failing or stale published P2 JVM API baselines")
    artifacts = {}
    built = {}
    for name in MODULES:
        path = root / f"ui-{name}/build/outputs/aar/ui-{name}-release.aar"
        if path.is_file():
            artifacts[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
            built[name] = path
    for name in ("ui-codegen", "ui-codegen-api"):
        jars = [p for p in (root / name / "build/libs").glob("*.jar") if not p.name.endswith("-sources.jar")]
        if len(jars) != 1:
            errors.append(f"Missing or ambiguous processor artifact: {name}")
        else:
            artifacts[jars[0].relative_to(root).as_posix()] = hashlib.sha256(jars[0].read_bytes()).hexdigest()
            built[name.removeprefix("ui-")] = jars[0]
    versions = [line.split("=", 1)[1].strip() for line in (root / "gradle.properties").read_text("utf-8").splitlines()
                if line.startswith("meguviz.version=")]
    if len(versions) != 1:
        errors.append("Missing or ambiguous UI version")
    else:
        version = versions[0]
        for module, output in built.items():
            artifact_name = f"meguviz-ui-{module}"
            folder = root / "build/repo/io/github/jichuo1/meguviz" / artifact_name / version
            resolved = version
            if version.endswith("-SNAPSHOT"):
                try:
                    metadata = ET.parse(folder / "maven-metadata.xml").getroot()
                    candidates = [node.findtext("value") for node in metadata.findall("./versioning/snapshotVersions/snapshotVersion")
                                  if node.findtext("extension") == output.suffix[1:] and node.find("classifier") is None]
                    if len(candidates) != 1 or not candidates[0]:
                        raise ValueError("ambiguous candidate")
                    resolved = candidates[0]
                except (OSError, ValueError, ET.ParseError):
                    errors.append(f"Missing or ambiguous published snapshot: {artifact_name}")
                    continue
            published = folder / f"{artifact_name}-{resolved}{output.suffix}"
            pom = folder / f"{artifact_name}-{resolved}.pom"
            if not published.is_file() or hashlib.sha256(published.read_bytes()).digest() != hashlib.sha256(output.read_bytes()).digest():
                errors.append(f"Published artifact differs from current output: {artifact_name}")
            if not pom.is_file():
                errors.append(f"Missing published POM: {artifact_name}")
            for item in (published, pom):
                if item.is_file():
                    artifacts[item.relative_to(root).as_posix()] = hashlib.sha256(item.read_bytes()).hexdigest()
    receipt.update(status="failed" if errors else "passed", ready=not errors, finished_epoch=time.time(),
                   test_count=sum(counts.values()), test_counts=counts, artifacts=artifacts, errors=errors,
                   reports=[p.relative_to(root).as_posix() for p in all_reports],
                   report_sha256={p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in all_reports},
                   scope="Host compile, tests, lint, generated debug/release apps and artifact hashes; no device or performance claim")
    write(receipt_path, receipt)
    print(json.dumps(receipt, ensure_ascii=False, indent=2))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
