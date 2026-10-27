"""Fail-closed gate receipts for actual Gradle outputs; Python standard library only."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import time
import tomllib
import xml.etree.ElementTree as ET

SOURCE_SUFFIXES = {".kt", ".kts", ".java", ".xml", ".toml", ".properties", ".pro", ".py", ".yml", ".yaml", ".lockfile"}
EXCLUDE = {".git", ".gradle", "build", ".tools", ".integration-worktrees", "worktree", "research", "__pycache__"}


def source_hash(root: Path) -> str:
    digest = hashlib.sha256()
    paths = []
    for directory, children, files in os.walk(root):
        children[:] = sorted(child for child in children if child not in EXCLUDE)
        paths.extend(Path(directory) / name for name in sorted(files))
    for path in sorted(paths):
        relative = path.relative_to(root)
        if any(part in EXCLUDE for part in relative.parts) or not path.is_file():
            continue
        if relative.as_posix() == "samples/p2-consumer/gradle/verification-metadata.xml":
            continue  # Generated from the current, separately hash-bound Maven candidates.
        service_descriptor = "META-INF" in relative.parts and "services" in relative.parts
        if path.name == "local.properties" or (path.suffix not in SOURCE_SUFFIXES and path.name not in {"gradlew", "gradlew.bat"} and not (relative.parts[0] == "api" and path.suffix == ".txt") and not service_descriptor):
            continue
        digest.update(relative.as_posix().encode("utf-8") + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


def write(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def artifact_hashes(root: Path) -> dict[str, str]:
    paths = list(root.glob("ui-*/build/outputs/aar/*-release.aar"))
    paths += [p for p in root.glob("ui-codegen*/build/libs/*.jar") if not p.name.endswith("-sources.jar")]
    paths += list(root.glob("build/repo/**/*.aar"))
    paths += list(root.glob("build/repo/**/meguviz-ui-codegen*.jar"))
    paths += list(root.glob("build/repo/**/*.pom"))
    paths += [p for p in root.glob("build/upstream-repo/**/*") if p.is_file() and p.suffix in {".jar", ".aar", ".pom", ".module", ".xml", ".json"}]
    return {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(paths)}


def parse_tests(paths: list[Path], started: float) -> tuple[int, list[str]]:
    count = 0
    errors = []
    if not paths:
        return 0, ["No JUnit XML reports; zero tests cannot pass"]
    for path in paths:
        if path.stat().st_mtime < started - 2:
            errors.append(f"Stale test report: {path}")
            continue
        suite = ET.parse(path).getroot()
        suites = [suite] if suite.tag == "testsuite" else list(suite.iter("testsuite"))
        for entry in suites:
            tests = int(entry.attrib.get("tests", "0"))
            skipped = int(entry.attrib.get("skipped", entry.attrib.get("disabled", "0")))
            failed = int(entry.attrib.get("failures", "0")) + int(entry.attrib.get("errors", "0"))
            count += tests
            if failed or skipped:
                errors.append(f"Failed or skipped tests in {path}: failed={failed}, skipped={skipped}")
    if count == 0:
        errors.append("No executed tests")
    return count, errors


def core_dependency_errors(lines: list[str]) -> list[str]:
    return [line for line in lines if line and not (
        line.startswith("org.jetbrains.kotlin:") or line.startswith("org.jetbrains:annotations:")
        or line in {"project :ui-core", "project ':ui-core'"})]


def required_upstreams(profiles: set[str]) -> dict[str, list[str]]:
    return {profile: artifacts for profile, artifacts in {
        "ref": ["meguviz-ref-core"],
        "android": ["meguviz-android-runtime", "meguviz-android-window"],
    }.items() if profile in profiles}


def upstream_snapshot(root: Path, profiles: set[str]) -> tuple[str, list[str]]:
    required = required_upstreams(profiles)
    if not required:
        return "disabled", []
    errors = []
    mirror = root / "build/upstream-repo"
    pin_path = root / "gradle/upstream-candidates.toml"
    if not pin_path.is_file():
        return "missing", ["Selected bridges require explicitly pinned upstream versions"]
    pins = tomllib.loads(pin_path.read_text("utf-8"))["candidates"]
    released = {name: pins[name] for names in required.values() for name in names
                if re.fullmatch(r"\d+\.\d+\.\d+", pins.get(name, {}).get("version", ""))}
    required = {profile: [name for name in names if name not in released]
                for profile, names in required.items()}
    required = {profile: names for profile, names in required.items() if names}
    if not required:
        return hashlib.sha256(json.dumps(released, sort_keys=True).encode()).hexdigest(), []
    manifest_path = mirror / "import-manifest.json"
    if not pin_path.is_file() or not manifest_path.is_file():
        return "missing", ["Selected bridges require the explicitly pinned upstream mirror"]
    imported = read(manifest_path).get("artifacts", {})
    selected = dict(released)
    for names in required.values():
        for name in names:
            pin = pins.get(name, {})
            record = imported.get(name, {})
            selected[name] = record
            if not re.fullmatch(r"0\.1\.0-\d{8}\.\d{6}-\d+", pin.get("version", "")) or pin.get("packaging") not in {"jar", "aar"}:
                errors.append(f"Invalid upstream version or packaging pin: {name}"); continue
            if record.get("resolved_version") != pin.get("version") or record.get("binary_sha256") != pin.get("sha256") or not record.get("files"):
                errors.append(f"Unreviewed or missing upstream candidate: {name}")
                continue
            base = mirror / "io/github/jichuo1/meguviz" / name / "0.1.0-SNAPSHOT"
            binary = base / f"{name}-{pin['version']}.{pin['packaging']}"
            if not binary.is_file() or hashlib.sha256(binary.read_bytes()).hexdigest() != pin["sha256"]:
                errors.append(f"Pinned upstream binary changed: {name}")
            if not pin.get("files"):
                errors.append(f"Missing metadata pins for upstream: {name}")
            for filename, expected in pin.get("files", {}).items():
                file = (base / filename).resolve()
                relative = file.relative_to(mirror.resolve()).as_posix() if file.is_relative_to(mirror.resolve()) else "invalid"
                if (not file.is_relative_to(base.resolve()) or not file.is_file()
                        or hashlib.sha256(file.read_bytes()).hexdigest() != expected
                        or record["files"].get(relative) != expected):
                    errors.append(f"Unreviewed upstream metadata/source artifact: {name}/{filename}")
            for relative, expected in record["files"].items():
                file = (mirror / relative).resolve()
                if not file.is_relative_to(mirror.resolve()) or not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != expected:
                    errors.append(f"Imported upstream file missing or changed: {relative}")
    return hashlib.sha256(json.dumps(selected, sort_keys=True).encode()).hexdigest(), errors


def resolved_upstream_errors(root: Path, profiles: set[str]) -> list[str]:
    errors = []
    if not required_upstreams(profiles):
        return errors
    pins = tomllib.loads((root / "gradle/upstream-candidates.toml").read_text("utf-8"))["candidates"]
    for profile, artifacts in required_upstreams(profiles).items():
        report = root / f"ui-{profile}-bridge/build/reports/runtime-artifacts.tsv"
        if not report.is_file():
            errors.append(f"Missing actual resolved artifact report for {profile}"); continue
        entries = [line.split("\t", 2) for line in report.read_text("utf-8").splitlines()]
        for name in artifacts:
            pin = pins[name]
            matches = [item for item in entries if len(item) == 3 and item[0].startswith(name + "-") and item[0].endswith("." + pin["packaging"])]
            if re.fullmatch(r"\d+\.\d+\.\d+", pin["version"]):
                matches = [item for item in matches if item[0] == f"{name}-{pin['version']}.{pin['packaging']}"]
            if len(matches) != 1 or matches[0][1] != pin["sha256"]:
                errors.append(f"Resolved classpath differs from pinned upstream: {name}"); continue
            resolved = Path(matches[0][2])
            if not resolved.is_file() or hashlib.sha256(resolved.read_bytes()).hexdigest() != pin["sha256"]:
                errors.append(f"Resolved upstream file changed after Gradle resolution: {name}")
    return errors


def start(root: Path, gate: str, profiles: set[str]) -> int:
    upstream, errors = upstream_snapshot(root, profiles)
    value = {"schema_version": 1, "gate": gate, "status": "running", "ready": False,
             "source_sha256": source_hash(root), "started_epoch": time.time(), "profiles": sorted(profiles),
             "upstream_snapshot": upstream, "errors": errors}
    if errors:
        value["status"] = "blocked"
    write(root / f"build/reports/gates/{gate}.json", value)
    return 2 if errors else 0


def finish_host(root: Path, profiles: set[str]) -> int:
    path = root / "build/reports/gates/host.json"
    value = read(path) if path.is_file() else {}
    errors = []
    if value.get("status") != "running":
        errors.append("Missing current host preflight")
    if value.get("source_sha256") != source_hash(root):
        errors.append("Source changed during verification")
    if set(value.get("profiles", [])) != profiles:
        errors.append("Profile selection changed during verification")
    started = value.get("started_epoch", time.time())
    test_modules = ["ui-core"] + [f"ui-{profile}-bridge" for profile in ("ref", "android") if profile in profiles]
    reports = []
    counts = {}
    for module in test_modules:
        module_reports = list(root.glob(f"{module}/build/test-results/testDebugUnitTest/TEST-*.xml"))
        counts[module], test_errors = parse_tests(module_reports, started)
        reports += module_reports
        errors += [f"{module}: {error}" for error in test_errors]
    count = sum(counts.values())
    lint_modules = ["ui-core", "ui-widgets", "samples/standalone", "samples/xml-interop"]
    lint_modules += [f"ui-{profile}-bridge" for profile in ("lumen", "ref", "android") if profile in profiles]
    for module in lint_modules:
        report = root / module / "build/reports/lint-results-debug.xml"
        if not report.is_file() or report.stat().st_mtime < started - 2:
            errors.append(f"Missing/stale lint report: {module}")
        elif any(item.attrib.get("severity") in ("Error", "Fatal", "Warning") for item in ET.parse(report).getroot()):
            errors.append(f"Lint findings: {module}")
    boundary = root / "ui-core/build/reports/runtime-dependencies.txt"
    if not boundary.is_file():
        errors.append("Missing resolved core dependency boundary report")
    else:
        forbidden = core_dependency_errors(boundary.read_text(encoding="utf-8").splitlines())
        if forbidden:
            errors.append("Core dependency boundary: " + ", ".join(forbidden))
    artifacts = artifact_hashes(root)
    for module in ["ui-core", "ui-widgets"] + [f"ui-{profile}-bridge" for profile in ("lumen", "ref", "android") if profile in profiles]:
        if f"{module}/build/outputs/aar/{module}-release.aar" not in artifacts:
            errors.append(f"Missing actual release AAR: {module}")
    fixture_path = root / "build/reports/compile-fixtures/receipt.json"
    fixture = read(fixture_path) if fixture_path.is_file() else {}
    expected_fixtures = {"positive", "negative-parent", "negative-ref", "negative-state", "negative-scope", "jvm-api-baseline"}
    for profile in profiles & {"lumen", "ref", "android"}:
        expected_fixtures.update({f"positive-{profile}", f"ui-{profile}-bridge-api-baseline"})
    observed_fixtures = {item.get("name") for item in fixture.get("checks", []) if item.get("status") == "passed"}
    if fixture.get("status") != "passed" or fixture.get("started_at_unix", 0) < started or not expected_fixtures.issubset(observed_fixtures):
        errors.append("Missing/failing/stale published AAR compilation and API fixtures")
    for item in fixture.get("artifacts", []):
        artifact = root / item["path"]
        if not artifact.is_file() or hashlib.sha256(artifact.read_bytes()).hexdigest() != item["sha256"]:
            errors.append("Compile fixture candidate artifact changed")
    expected_artifacts = {"meguviz-ui-core", "meguviz-ui-widgets"}
    expected_artifacts.update(f"meguviz-ui-{profile}-bridge" for profile in profiles & {"lumen", "ref", "android"})
    observed_artifacts = {item.get("coordinate", "").split(":")[1] for item in fixture.get("artifacts", []) if len(item.get("coordinate", "").split(":")) == 3}
    if observed_artifacts != expected_artifacts:
        errors.append("Compile fixtures did not verify every selected candidate module")
    contracts_path = root / "build/reports/gates/gate-contracts.json"
    contracts = read(contracts_path) if contracts_path.is_file() else {}
    if contracts.get("status") != "passed" or contracts.get("test_count", 0) == 0 or contracts.get("finished_epoch", 0) < started:
        errors.append("Missing/failing/stale gate negative contracts")
    upstream, upstream_errors = upstream_snapshot(root, profiles)
    errors += upstream_errors
    if value.get("upstream_snapshot") != upstream:
        errors.append("Upstream candidate mirror changed during this build")
    errors += resolved_upstream_errors(root, profiles)
    value.update(status="failed" if errors else "passed", ready=not errors,
                 finished_epoch=time.time(), test_count=count, module_test_counts=counts,
                 reports=[p.relative_to(root).as_posix() for p in reports],
                 report_sha256={p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in reports},
                 artifacts=artifacts, errors=errors)
    write(path, value)
    for profile in profiles & {"lumen", "ref", "android"}:
        module = f"ui-{profile}-bridge"
        selected_reports = [p for p in reports if p.relative_to(root).parts[0] == module]
        write(root / f"build/reports/gates/profile-{profile}.json", {
            "schema_version": 1, "gate": f"profile-{profile}", "status": "failed" if errors else "blocked",
            "ready": False, "host_status": value["status"], "device_status": "not_run",
            "source_sha256": value["source_sha256"], "profiles": ["core", profile],
            "test_count": counts.get(module, 0), "host_test_count": counts.get(module, 0),
            "started_epoch": started, "finished_epoch": value["finished_epoch"],
            "reports": [p.relative_to(root).as_posix() for p in selected_reports],
            "report_sha256": {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in selected_reports},
            "artifacts": artifacts, "errors": errors,
            "remaining": ["Actual bridge consumer APK device contracts and the selected behavior/visual matrix are not yet executed"],
        })
    print(json.dumps(value, ensure_ascii=False, indent=2))
    return 1 if errors else 0


def device(root: Path, profiles: set[str]) -> int:
    # Device claims require real connected test reports generated by the declared runner.
    path = root / "build/reports/gates/device.json"
    value = read(path) if path.is_file() else {}
    errors = []
    if value.get("status") != "running" or value.get("source_sha256") != source_hash(root):
        errors.append("Missing current device preflight")
    reports = list(root.glob("samples/standalone/build/outputs/androidTest-results/connected/**/TEST-*.xml"))
    count, test_errors = parse_tests(reports, value.get("started_epoch", time.time()))
    errors += test_errors
    applications = list(root.glob("samples/standalone/build/outputs/apk/debug/*.apk"))
    test_apks = list(root.glob("samples/standalone/build/outputs/apk/androidTest/debug/*.apk"))
    if len(applications) != 1 or len(test_apks) != 1:
        errors.append("The exact tested application APK and instrumentation APK are required")
    outputs = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in applications + test_apks}
    matrix_path = root / "build/reports/device/matrix.json"
    matrix = read(matrix_path) if matrix_path.is_file() else {}
    required = {"api27", "api29", "api37", "rotation", "process-death", "ime", "accessibility", "dialog", "xml-appcompat", "theme-change"}
    if "lumen" in profiles:
        required.add("lumen-visual")
    profile_rows = {"lumen-visual": "lumen"} if "lumen" in profiles else {}
    if "ref" in profiles:
        profile_rows.update({"ref-code-scope": "ref", "ref-loader-isolation": "ref"})
    if "android" in profiles:
        profile_rows.update({"android-single-frame": "android", "android-resource-authority": "android", "android-window-reattach": "android"})
    required.update(profile_rows)
    rows = {row.get("id"): row for row in matrix.get("checks", [])}
    current_artifacts = artifact_hashes(root)
    digest = source_hash(root)
    for name in sorted(required):
        row = rows.get(name, {})
        needed = {"core", profile_rows[name]} if name in profile_rows else {"core"}
        if validate_receipt(row, digest, needed, current_artifacts, root) or not row.get("evidence") or not row.get("device"):
            errors.append(f"Missing current device behavior matrix: {name}")
            continue
        if name in profile_rows:
            bridge = f"ui-{profile_rows[name]}-bridge"
            if (f"{bridge}/build/outputs/aar/{bridge}-release.aar" not in row.get("artifacts", {})
                    or not any(path.endswith(".apk") for path in row.get("outputs", {}))):
                errors.append(f"Missing actual bridge consumer APK and bridge artifact binding: {name}")
        for evidence in row["evidence"]:
            if not isinstance(evidence, dict):
                errors.append(f"Device evidence requires a file and SHA: {name}"); continue
            file = (root / evidence.get("path", "")).resolve()
            if not file.is_relative_to(root) or not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != evidence.get("sha256"):
                errors.append(f"Missing/changed device evidence: {name}")
    value.update(schema_version=1, gate="device", status="blocked" if errors else "passed",
                 ready=not errors, source_sha256=source_hash(root), test_count=count,
                 finished_epoch=time.time(), artifacts=artifact_hashes(root), errors=errors,
                 profiles=sorted(profiles), instrumentation_test_count=count,
                 outputs=outputs,
                 reports=[p.relative_to(root).as_posix() for p in reports],
                 report_sha256={p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in reports})
    write(path, value)
    print(json.dumps(value, ensure_ascii=False, indent=2))
    return 2 if errors else 0


def validate_receipt(receipt: dict, digest: str, profiles: set[str], artifacts: dict,
                     root: Path | None = None, require_reports: bool = False, require_device_apks: bool = False,
                     count_field: str = "test_count") -> list[str]:
    errors = []
    if receipt.get("status") != "passed" or not receipt.get("ready"):
        errors.append("gate is not passed")
    if receipt.get("source_sha256") != digest:
        errors.append("source revision is stale")
    if not profiles.issubset(set(receipt.get("profiles", []))):
        errors.append("required profile is missing")
    count = receipt.get(count_field, 0)
    if not isinstance(count, int) or isinstance(count, bool) or count < 1:
        errors.append("zero checks" if count_field == "check_count" else "zero tests")
    recorded = receipt.get("artifacts", {})
    if not recorded or any(artifacts.get(name) != sha for name, sha in recorded.items()):
        errors.append("artifact missing or changed")
    outputs = receipt.get("outputs", {})
    if require_device_apks and (not any("/apk/debug/" in name and name.endswith(".apk") for name in outputs)
                                or not any("/apk/androidTest/debug/" in name and name.endswith(".apk") for name in outputs)):
        errors.append("exact tested application and instrumentation APK hashes are missing")
    reports = receipt.get("reports", [])
    report_hashes = receipt.get("report_sha256", {})
    if require_reports and not reports:
        errors.append("executed test reports are missing")
    if root is not None:
        for name, expected in outputs.items():
            path = (root / name).resolve()
            if not path.is_relative_to(root) or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
                errors.append(f"output missing or changed: {name}")
        for name in reports:
            path = (root / name).resolve()
            if not path.is_relative_to(root) or not path.is_file():
                errors.append(f"test report missing: {name}")
            elif (path.stat().st_mtime < receipt.get("started_epoch", float("inf")) - 2
                  or hashlib.sha256(path.read_bytes()).hexdigest() != report_hashes.get(name)):
                errors.append(f"test report stale or changed: {name}")
    return errors


def release(root: Path, profiles: set[str], p2_profiles: set[str] | None = None) -> int:
    digest = source_hash(root)
    artifacts = artifact_hashes(root)
    state_path = root / "PROJECT_STATE.json"
    state = read(state_path) if state_path.is_file() else {}
    target = state.get("execution_target", "full_replacement")
    errors = []
    if target not in {"full_replacement", "core_candidate"}:
        errors.append("Unknown execution_target")

    def selection(value: object, label: str) -> set[str]:
        if (not isinstance(value, list) or not value or
                any(not isinstance(item, str) or not item.strip() for item in value)):
            errors.append(f"Invalid selected profiles in {label}: a non-empty explicit list is required")
            return set()
        result = set(value)
        if len(result) != len(value) or "core" not in result:
            errors.append(f"Invalid selected profiles in {label}: core is required and duplicates are forbidden")
        return result

    required_profiles = selection(state.get("selected_profiles"), "PROJECT_STATE.json")
    declaration_path = root / "integration/profiles.json"
    declaration = read(declaration_path) if declaration_path.is_file() else {}
    if declaration.get("execution_target") != target:
        errors.append("Execution target disagrees with the integration declaration")
    declared_profiles = selection(declaration.get("selected_profiles"), "integration/profiles.json")
    if required_profiles != declared_profiles:
        errors.append("Selected profiles disagree with the integration declaration")
    known_profiles = declaration.get("profiles", {})
    if any(name not in known_profiles or known_profiles[name].get("enabled") is not True for name in required_profiles):
        errors.append("A selected profile is unknown or disabled in the integration declaration")
    if not profiles.issubset(required_profiles):
        errors.append("Invocation profiles are outside the declared release scope")
    requested_p2 = p2_profiles or set()
    allowed_p2 = {"plan", "codegen", "recycler", "text", "layouts", "prewarm"}
    state_p2 = state.get("selected_p2_profiles", [])
    declaration_p2 = declaration.get("selected_p2_profiles", [])
    if (not isinstance(state_p2, list) or not isinstance(declaration_p2, list)
            or any(not isinstance(value, str) for value in state_p2 + declaration_p2)):
        errors.append("Invalid P2 profile declaration")
        required_p2 = set()
    else:
        required_p2 = set(state_p2)
        if len(required_p2) != len(state_p2) or len(set(declaration_p2)) != len(declaration_p2):
            errors.append("Duplicate P2 profile declaration")
        if required_p2 != set(declaration_p2) or requested_p2 != required_p2:
            errors.append("P2 invocation, project state and integration declaration must match")
        if not required_p2.issubset(allowed_p2):
            errors.append("Unknown P2 profile")
    receipts = {}
    for name in ("host", "device"):
        path = root / f"build/reports/gates/{name}.json"
        receipt = read(path) if path.is_file() else {}
        receipts[name] = receipt.get("status", "not_run")
        needed = required_profiles - {"bil"}
        errors += [f"{name}: {e}" for e in validate_receipt(receipt, digest, needed, artifacts, root,
                   require_reports=True, require_device_apks=name == "device")]
    # Performance and integration cannot be manufactured from host success.
    required = ["r8", "performance"]
    if required_profiles & {"lumen", "ref", "android"}:
        required.append("bridge-r8")
    if target == "full_replacement":
        required += ["bil-full", "dependency-exit", "second-project"]
    required += ["profile-" + name for name in sorted(required_profiles - {"core", "bil"})]
    for name in required:
        path = root / f"build/reports/gates/{name}.json"
        receipt = read(path) if path.is_file() else {}
        receipts[name] = receipt.get("status", "not_run")
        if name == "bridge-r8":
            needed = required_profiles - {"bil"}
        elif name.startswith("profile-"):
            needed = {name.removeprefix("profile-")}
        else:
            needed = {"core"}
        errors += [f"{name}: {e}" for e in validate_receipt(receipt, digest, needed, artifacts, root)]
    if required_p2:
        for name in ("p2-host", "p2-consumer", "p2-device", "p2-performance"):
            path = root / f"build/reports/gates/{name}.json"
            receipt = read(path) if path.is_file() else {}
            receipts[name] = receipt.get("status", "not_run")
            errors += [f"{name}: {e}" for e in validate_receipt(
                receipt, digest, required_p2, artifacts, root, require_reports=True,
                count_field="check_count" if name == "p2-consumer" else "test_count")]
    value = {"schema_version": 1, "gate": "release", "status": "blocked" if errors else "passed",
             "ready": not errors, "execution_target": target, "source_sha256": digest,
             "profiles": sorted(required_profiles), "p2_profiles": sorted(required_p2),
             "artifacts": artifacts, "gates": receipts, "errors": errors,
             "finished_epoch": time.time()}
    write(root / "build/reports/gates/release.json", value)
    write(root / "gate-results.json", value)
    print(json.dumps(value, ensure_ascii=False, indent=2))
    return 2 if errors else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("start-host", "start-device", "host", "device", "release", "hash"))
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--profiles", default="core", help="Explicit comma-separated profiles included in this invocation")
    parser.add_argument("--p2-profiles", default="", help="Explicit optional P2 profiles included in the release")
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    profiles = {name for name in args.profiles.split(",") if name}
    if not profiles or "core" not in profiles:
        parser.error("Profiles must explicitly include core")
    if args.mode.startswith("start-"):
        return start(root, args.mode.removeprefix("start-"), profiles)
    if args.mode == "hash":
        print(source_hash(root)); return 0
    if args.mode == "release":
        return release(root, profiles, set(filter(None, args.p2_profiles.split(","))))
    return {"host": finish_host, "device": device, "release": release}[args.mode](root, profiles)


if __name__ == "__main__":
    sys.exit(main())
