"""Compare opt-in P2 published artifacts with reviewed JVM descriptor baselines."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import zipfile

MODULES = ("plan", "recycler", "text", "layouts", "prewarm", "codegen", "codegen-api")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--update-api", action="store_true", help="Write new baselines only after API review")
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    started = time.time()
    output = root / "build/reports/p2-api"
    output.mkdir(parents=True, exist_ok=True)
    errors = []
    checks = []
    try:
        version = next(line.split("=", 1)[1].strip() for line in (root / "gradle.properties").read_text("utf-8").splitlines()
                       if line.startswith("meguviz.version="))
        if version.endswith("-SNAPSHOT"):
            raise ValueError("P2 ABI comparison requires an explicit immutable local candidate version")
        javap = shutil.which("javap")
        if not javap:
            raise RuntimeError("javap is required")
        jars = {}
        for module in MODULES:
            artifact = f"meguviz-ui-{module}"
            extension = "jar" if module.startswith("codegen") else "aar"
            file = root / "build/repo/io/github/jichuo1/meguviz" / artifact / version / f"{artifact}-{version}.{extension}"
            if not file.is_file():
                raise RuntimeError(f"Missing published artifact: {artifact}")
            if extension == "aar":
                jar = output / f"{artifact}.jar"
                with zipfile.ZipFile(file) as archive:
                    jar.write_bytes(archive.read("classes.jar"))
            else:
                jar = file
            jars[module] = (jar, file)
        classpath = os.pathsep.join(str(pair[0]) for pair in jars.values())
        for module, (jar, published) in jars.items():
            with zipfile.ZipFile(jar) as archive:
                classes = sorted(name[:-6].replace("/", ".") for name in archive.namelist()
                                 if name.endswith(".class") and name.startswith("io/github/jichuo1/meguviz/ui/"))
            if not classes:
                raise RuntimeError(f"No UI classes in {module}")
            result = subprocess.run([javap, "-public", "-s", "-classpath", classpath, *classes],
                                    capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
            if result.returncode:
                raise RuntimeError(f"javap failed for {module}: {result.stderr}")
            sections = re.split(r'Compiled from "[^\"]+"\r?\n', result.stdout)
            dump = "// JVM public descriptors. Kotlin source contracts require separate compilation.\n\n" + \
                   "\n\n".join(sorted(section.strip() for section in sections if section.lstrip().startswith("public "))) + "\n"
            baseline = root / "api" / f"ui-{module}.txt"
            observed = output / f"ui-{module}.txt"
            observed.write_text(dump, encoding="utf-8")
            if args.update_api:
                baseline.write_text(dump, encoding="utf-8")
            matched = baseline.is_file() and baseline.read_text("utf-8") == dump
            if not matched:
                errors.append(f"Public JVM descriptor baseline differs: {module}")
            checks.append({"module": module, "status": "passed" if matched else "failed",
                           "artifact": published.relative_to(root).as_posix(),
                           "sha256": hashlib.sha256(published.read_bytes()).hexdigest(),
                           "baseline": baseline.relative_to(root).as_posix()})
    except (OSError, RuntimeError, ValueError, StopIteration, zipfile.BadZipFile, subprocess.TimeoutExpired) as failure:
        errors.append(str(failure))
    receipt = {"status": "failed" if errors else "passed", "started_epoch": started,
               "checks": checks, "errors": errors,
               "scope": "Current published JVM descriptors; no historical stable ABI comparison"}
    (output / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, ensure_ascii=False))
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
