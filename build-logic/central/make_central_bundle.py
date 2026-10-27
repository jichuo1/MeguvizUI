"""Assemble a Maven Central Portal upload bundle from the local candidate repository.

Fails closed on: missing artifacts, incomplete POM metadata, SNAPSHOT or unresolvable dependencies,
and sensitive local data inside any published file. Signs only when GnuPG can do so without asking for
a passphrase; otherwise it leaves a script for the key owner and does not produce a signed zip.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

GROUP = "io.github.jichuo1.meguviz"
MODULES = ("meguviz-ui-core", "meguviz-ui-widgets", "meguviz-ui-ref-bridge", "meguviz-ui-plan", "meguviz-ui-recycler",
           "meguviz-ui-text", "meguviz-ui-layouts", "meguviz-ui-prewarm", "meguviz-ui-codegen-api", "meguviz-ui-codegen")
REPOSITORIES = ("https://repo1.maven.org/maven2/", "https://dl.google.com/dl/android/maven2/")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
SENSITIVE = [re.compile(p) for p in (rb"[A-Za-z]:[\\/]+(?:Users|MeguvizUI|Bilibili|Gradle)", rb"/c/Users/", rb"Administrator",
                                      rb"3B1646002YU00000", rb"[\w.+-]+@(?:gmail|outlook|hotmail|qq|163)\.com")]
JAVADOC_README = """MeguvizUI {version} — {artifact}

API documentation for this artifact is the Kotlin source in {artifact}-{version}-sources.jar and the
project documentation at https://github.com/jichuo1/MeguvizUI . This jar is intentionally a
placeholder, as permitted by the Maven Central requirements for non-Java (Kotlin/Android) libraries.
"""


def text(node, path: str) -> str:
    found = node.find(path, NS)
    return (found.text or "").strip() if found is not None else ""


def check_pom(pom: Path, artifact: str, version: str, bundle: set[str], cache: dict[str, str]) -> list[str]:
    errors = []
    root = ET.parse(pom).getroot()
    if (text(root, "m:groupId"), text(root, "m:artifactId"), text(root, "m:version")) != (GROUP, artifact, version):
        errors.append("coordinates differ")
    for field in ("m:name", "m:description", "m:url", "m:licenses/m:license/m:name", "m:licenses/m:license/m:url",
                  "m:developers/m:developer/m:id", "m:scm/m:url", "m:scm/m:connection"):
        if not text(root, field):
            errors.append(f"missing {field.replace('m:', '')}")
    for dependency in root.findall("m:dependencies/m:dependency", NS):
        group, name, dep_version = (text(dependency, f"m:{key}") for key in ("groupId", "artifactId", "version"))
        scope = text(dependency, "m:scope") or "compile"
        coordinate = f"{group}:{name}:{dep_version}"
        if not dep_version or "SNAPSHOT" in dep_version or "${" in dep_version:
            errors.append(f"unpinned or SNAPSHOT dependency {coordinate}")
            continue
        if scope in ("test", "provided", "system"):
            continue
        if group == GROUP and name in bundle and dep_version == version:
            continue
        if coordinate not in cache:
            path = f"{group.replace('.', '/')}/{name}/{dep_version}/{name}-{dep_version}.pom"
            cache[coordinate] = "unresolved"
            for repository in REPOSITORIES:
                try:
                    with urllib.request.urlopen(urllib.request.Request(repository + path, method="HEAD"), timeout=20) as response:
                        if response.status == 200:
                            cache[coordinate] = repository; break
                except (urllib.error.URLError, TimeoutError):
                    pass
        if cache[coordinate] == "unresolved":
            errors.append(f"dependency not on Maven Central or Google Maven: {coordinate}")
    return errors


def sensitive_hits(name: str, data: bytes) -> list[str]:
    hits = [f"{name}: {pattern.pattern.decode()}" for pattern in SENSITIVE if pattern.search(data)]
    if data[:2] == b"PK":
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for entry in archive.namelist():
                if not entry.endswith("/"):
                    hits += sensitive_hits(f"{name}!{entry}", archive.read(entry))
    return hits


def placeholder_javadoc(artifact: str, version: str) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")
        archive.writestr("README.txt", JAVADOC_README.format(version=version, artifact=artifact))
    return buffer.getvalue()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--key", required=True, help="GnuPG key id used for detached signatures")
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    if "SNAPSHOT" in args.version:
        parser.error("Central releases cannot be SNAPSHOT versions")
    repo = root / "build/repo" / GROUP.replace(".", "/")
    out = root / "build/central"
    staging = out / "staging"
    if staging.exists():
        shutil.rmtree(staging)
    errors: list[str] = []
    manifest: dict = {"group": GROUP, "version": args.version, "modules": {}}
    cache: dict[str, str] = {}
    for artifact in MODULES:
        source = repo / artifact / args.version
        target = staging / GROUP.replace(".", "/") / artifact / args.version
        target.mkdir(parents=True)
        pom = source / f"{artifact}-{args.version}.pom"
        if not pom.is_file():
            errors.append(f"{artifact}: missing POM (run publishCandidate)"); continue
        packaging = text(ET.parse(pom).getroot(), "m:packaging") or "jar"
        files = {f"{artifact}-{args.version}.pom": pom, f"{artifact}-{args.version}.{packaging}": source / f"{artifact}-{args.version}.{packaging}",
                 f"{artifact}-{args.version}-sources.jar": source / f"{artifact}-{args.version}-sources.jar",
                 f"{artifact}-{args.version}.module": source / f"{artifact}-{args.version}.module"}
        for name, path in files.items():
            if not path.is_file():
                errors.append(f"{artifact}: missing {name}"); continue
            data = path.read_bytes()
            errors += [f"sensitive data in {hit}" for hit in sensitive_hits(name, data)]
            (target / name).write_bytes(data)
        (target / f"{artifact}-{args.version}-javadoc.jar").write_bytes(placeholder_javadoc(artifact, args.version))
        errors += [f"{artifact}: {error}" for error in check_pom(pom, artifact, args.version, set(MODULES), cache)]
        manifest["modules"][artifact] = {"packaging": packaging}
    manifest["external_dependencies"] = cache
    if errors:
        print(json.dumps({"status": "failed", "errors": errors}, ensure_ascii=False, indent=2))
        return 1

    payload = sorted(p for p in staging.rglob("*") if p.is_file())
    for path in payload:
        data = path.read_bytes()
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            Path(f"{path}.{algorithm}").write_text(hashlib.new(algorithm, data).hexdigest(), "ascii")
    unsigned = [p for p in payload]
    signed = True
    for path in unsigned:
        result = subprocess.run(["gpg", "--batch", "--yes", "--pinentry-mode", "error", "--local-user", args.key,
                                 "--armor", "--detach-sign", "--output", f"{path}.asc", str(path)], capture_output=True, text=True)
        if result.returncode:
            signed = False
            Path(f"{path}.asc").unlink(missing_ok=True)
            break
    script = out / "sign-and-zip.sh"
    script.write_text(f"""#!/usr/bin/env bash
# Run by the key owner: signs every payload file with {args.key} (GnuPG asks for the passphrase), then zips.
set -euo pipefail
cd "$(dirname "$0")/staging"
find . -type f ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' ! -name '*.asc' -print0 |
  xargs -0 -I{{}} gpg --yes --local-user {args.key} --armor --detach-sign {{}}
rm -f ../meguvizui-{args.version}-central-bundle.zip
python -c "import shutil; shutil.make_archive('../meguvizui-{args.version}-central-bundle', 'zip', '.')"
echo "Bundle: $(cd .. && pwd)/meguvizui-{args.version}-central-bundle.zip"
""", "utf-8", newline="\n")
    bundle = out / f"meguvizui-{args.version}-central-bundle.zip"
    bundle.unlink(missing_ok=True)
    if signed:
        shutil.make_archive(str(bundle.with_suffix("")), "zip", staging)
        manifest["bundle"] = {"path": bundle.relative_to(root).as_posix(), "sha256": hashlib.sha256(bundle.read_bytes()).hexdigest()}
    manifest.update(signed=signed, key=args.key, files=len(payload),
                    next_step="Upload the zip at https://central.sonatype.com/publishing" if signed else f"Run {script.relative_to(root).as_posix()} to sign with your key, then upload the zip")
    (out / "bundle-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), "utf-8")
    print(json.dumps({"status": "ready" if signed else "needs-signature", "modules": len(MODULES), "payload_files": len(payload),
                      "bundle": manifest.get("bundle"), "next_step": manifest["next_step"]}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
