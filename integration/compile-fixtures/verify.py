#!/usr/bin/env python3
"""Compile typed consumers against the exact published AARs and compare JVM API descriptors."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import tomllib
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--update-api", action="store_true", help="Explicitly replace the API baseline after reviewing the generated dump")
    parser.add_argument("--gradle-cache", type=Path, default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")))
    parser.add_argument("--android-jar", type=Path)
    parser.add_argument("--profiles", default="core", help="Explicit core,lumen,ref,android profile selection")
    args = parser.parse_args()
    profiles = set(args.profiles.split(","))
    if "core" not in profiles or not profiles.issubset({"core", "lumen", "ref", "android"}):
        parser.error("Profiles must include core and contain only supported library profiles")
    output = ROOT / "build/reports/compile-fixtures"
    output.mkdir(parents=True, exist_ok=True)
    receipt = {"schema_version": 1, "status": "failed", "started_at_unix": time.time(), "profiles": sorted(profiles), "scope": "Published AAR typed source compilation and JVM descriptor baseline; no device behavior or previous-release binary certification", "checks": [], "artifacts": []}
    exit_code = 1
    try:
        versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text("utf-8"))["versions"]
        kotlin = versions["kotlin"]
        cache = args.gradle_cache / "caches/modules-2/files-2.1"

        def jar(group: str, artifact: str, version: str) -> Path:
            matches = sorted((cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
            if len(matches) != 1:
                raise RuntimeError(f"Expected one cached exact compiler dependency: {group}:{artifact}:{version}; found {len(matches)}")
            return matches[0]

        compiler = jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", kotlin)
        stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", kotlin)
        compiler_jars = [compiler, stdlib]
        pom_paths = list(compiler.parent.glob("*.pom")) or sorted((cache / "org.jetbrains.kotlin/kotlin-compiler-embeddable" / kotlin).glob("*/*.pom"))
        if len(pom_paths) != 1:
            raise RuntimeError("Exact Kotlin compiler POM missing or ambiguous")
        pom = ET.parse(pom_paths[0]).getroot()
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        for dependency in pom.findall("m:dependencies/m:dependency", ns):
            values = [dependency.findtext(f"m:{field}", namespaces=ns) for field in ("groupId", "artifactId", "version")]
            dependency_jar = jar(*values)
            if dependency_jar not in compiler_jars:
                compiler_jars.append(dependency_jar)
        compiler_jars.append(jar("org.jetbrains", "annotations", "13.0"))

        android_jar = args.android_jar
        if android_jar is None:
            local = (ROOT / "local.properties").read_text("utf-8")
            sdk_lines = [line[len("sdk.dir="):] for line in local.splitlines() if line.startswith("sdk.dir=")]
            if len(sdk_lines) != 1:
                raise RuntimeError("Pass --android-jar or configure sdk.dir in local.properties")
            sdk = Path(sdk_lines[0].replace("\\:", ":").replace("\\\\", "\\"))
            candidates = [sdk / "platforms/android-37/android.jar", sdk / "platforms/android-37.0/android.jar"]
            android_jar = next((path for path in candidates if path.is_file()), None)
        if android_jar is None or not android_jar.is_file():
            raise RuntimeError("Android 37 public SDK android.jar unavailable")

        candidate_jars = []
        version = next(line.split("=", 1)[1].strip() for line in
                       (ROOT / "gradle.properties").read_text("utf-8").splitlines()
                       if line.startswith("meguviz.version="))
        modules = ["core", "widgets"] + [name + "-bridge" for name in ("lumen", "ref", "android") if name in profiles]
        for module in modules:
            artifact = f"meguviz-ui-{module}"
            base = ROOT / "build/repo/io/github/jichuo1/meguviz" / artifact / version
            matches = [version]
            if version.endswith("-SNAPSHOT"):
                metadata = ET.parse(base / "maven-metadata.xml").getroot()
                matches = [item.findtext("value") for item in metadata.findall("./versioning/snapshotVersions/snapshotVersion") if item.findtext("extension") == "aar" and item.find("classifier") is None]
                if len(matches) != 1:
                    raise RuntimeError(f"Exact candidate AAR is ambiguous: {artifact}")
            aar = base / f"{artifact}-{matches[0]}.aar"
            built = ROOT / f"ui-{module}/build/outputs/aar/ui-{module}-release.aar"
            if sha(aar) != sha(built):
                raise RuntimeError(f"Published candidate differs from current release output: {artifact}")
            jar_path = output / f"{artifact}.jar"
            with zipfile.ZipFile(aar) as archive:
                jar_path.write_bytes(archive.read("classes.jar"))
            candidate_jars.append(jar_path)
            receipt["artifacts"].append({"coordinate": f"io.github.jichuo1.meguviz:{artifact}:{matches[0]}", "path": str(aar.relative_to(ROOT)), "sha256": sha(aar)})

        external_classpath = []
        external_sources = {}
        published_modules = {"ui-" + module for module in modules}
        for module in modules[2:]:
            report = ROOT / f"ui-{module}/build/reports/compile-fixture-classpath.txt"
            if not report.is_file():
                raise RuntimeError(f"Missing exact resolved compile classpath: {report}")
            for line in report.read_text("utf-8").splitlines():
                if not line.strip():
                    continue
                dependency = Path(line.strip()).resolve(strict=True)
                if dependency.is_relative_to(ROOT) and dependency.relative_to(ROOT).parts[0] in published_modules:
                    continue  # Compile against the published candidate, never its project classes.
                if not dependency.is_file() or dependency.suffix not in {".jar", ".aar"}:
                    raise RuntimeError(f"Unsupported resolved compile artifact: {dependency}")
                fingerprint = sha(dependency)
                external_sources[str(dependency)] = fingerprint
                if dependency.suffix == ".aar":
                    extracted = output / f"dependency-{fingerprint}.jar"
                    with zipfile.ZipFile(dependency) as archive:
                        extracted.write_bytes(archive.read("classes.jar"))
                    dependency = extracted
                if dependency != stdlib and dependency not in external_classpath:
                    external_classpath.append(dependency)
        receipt["resolved_compile_artifacts"] = external_sources

        java = shutil.which("java")
        javap = shutil.which("javap")
        if not java or not javap:
            raise RuntimeError("java and javap must be available on PATH")
        receipt["compiler"] = {"kotlin": kotlin, "sha256": sha(compiler), "java": java, "android_jar": str(android_jar), "android_sha256": sha(android_jar)}
        source_files = sorted(path for module in published_modules for path in (ROOT / module / "src/main").rglob("*") if path.is_file())
        receipt["observed_source_fingerprint"] = hashlib.sha256("\n".join(f"{path.relative_to(ROOT).as_posix()}:{sha(path)}" for path in source_files).encode()).hexdigest()
        receipt["source_binding"] = "The observed workspace hash is not build provenance; aggregate verifyHost must bind fresh assemble/publish tasks and unchanged preflight source to these exact artifact hashes."
        classpath = os.pathsep.join(map(str, [android_jar, stdlib, *candidate_jars, *external_classpath]))
        compiler_command = [java, "-cp", os.pathsep.join(map(str, compiler_jars)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-jvm-target", "17", "-no-stdlib", "-no-reflect", "-classpath", classpath, "-Xrender-internal-diagnostic-names"]
        fixture_root = Path(__file__).resolve().parent
        fixtures = [("positive", True), ("negative-parent", False), ("negative-ref", False), ("negative-state", False), ("negative-scope", False)]
        fixtures += [("positive-" + name, True) for name in ("lumen", "ref", "android") if name in profiles]
        for fixture, expected_success in fixtures:
            sources = sorted((fixture_root / fixture).glob("*.kt"))
            if not sources:
                raise RuntimeError(f"No sources in required fixture {fixture}")
            command = [*compiler_command, "-d", str(output / f"{fixture}.jar"), *map(str, sources)]
            result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
            log = result.stdout + result.stderr
            (output / f"{fixture}.log").write_text(log, "utf-8")
            expected_diagnostic = bool(re.search(r"\[(?:ARGUMENT_TYPE_MISMATCH|TYPE_MISMATCH|INITIALIZER_TYPE_MISMATCH|RETURN_TYPE_MISMATCH|DSL_SCOPE_VIOLATION)\]", log))
            passed = result.returncode == 0 if expected_success else result.returncode != 0 and expected_diagnostic
            receipt["checks"].append({"name": fixture, "status": "passed" if passed else "failed", "exit_code": result.returncode, "expected_compile_success": expected_success, "command": command, "log": str((output / f"{fixture}.log").relative_to(ROOT)), "sources": {str(path.relative_to(ROOT)): sha(path) for path in sources}})

        public_sections = {"public-api": []}
        for module, module_jar in zip(modules, candidate_jars):
            with zipfile.ZipFile(module_jar) as archive:
                classes = sorted(name[:-6].replace("/", ".") for name in archive.namelist() if name.endswith(".class") and name.startswith("io/github/jichuo1/meguviz/ui/"))
            if not classes:
                raise RuntimeError(f"Published AAR contains no UI classes: {module_jar.name}")
            result = subprocess.run([javap, "-public", "-s", "-classpath", classpath, *classes], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
            if result.returncode != 0:
                raise RuntimeError(f"javap failed: {result.stderr}")
            sections = re.split(r'Compiled from "[^\"]+"\r?\n', result.stdout)
            name = "public-api" if module in {"core", "widgets"} else "ui-" + module
            public_sections.setdefault(name, []).extend(section.strip() for section in sections if section.lstrip().startswith("public "))
        compilation_passed = all(check["status"] == "passed" for check in receipt["checks"])
        api_passed = True
        for name, sections in public_sections.items():
            dump = "// JVM public descriptors. Kotlin source contracts are additionally checked by compile fixtures.\n\n" + "\n\n".join(sorted(sections)) + "\n"
            (output / f"{name}.txt").write_text(dump, "utf-8")
            baseline = ROOT / "api" / f"{name}.txt"
            if args.update_api and compilation_passed:
                baseline.parent.mkdir(parents=True, exist_ok=True)
                baseline.write_text(dump, "utf-8")
            matches = baseline.is_file() and baseline.read_text("utf-8") == dump
            api_passed = api_passed and matches
            check_name = "jvm-api-baseline" if name == "public-api" else name + "-api-baseline"
            receipt["checks"].append({"name": check_name, "status": "passed" if matches else "failed", "baseline": str(baseline.relative_to(ROOT)), "current": str((output / f"{name}.txt").relative_to(ROOT)), "baseline_updated": bool(args.update_api and compilation_passed), "scope": "Initial candidate snapshot until an earlier independently published release exists"})
        receipt["status"] = "passed" if compilation_passed and api_passed else "failed"
        exit_code = 0 if receipt["status"] == "passed" else 1
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.TimeoutExpired, ET.ParseError, zipfile.BadZipFile) as error:
        receipt["status"] = "blocked"
        receipt["reason"] = str(error)
        exit_code = 2
    receipt["finished_at_unix"] = time.time()
    (output / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", "utf-8")
    print(json.dumps({"status": receipt["status"], "checks": [{"name": item["name"], "status": item["status"]} for item in receipt["checks"]], "reason": receipt.get("reason"), "receipt": str(output / "receipt.json")}, ensure_ascii=False))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
