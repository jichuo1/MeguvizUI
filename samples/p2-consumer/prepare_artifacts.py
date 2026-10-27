#!/usr/bin/env python3
"""Bind this independent consumer to the locally built, published P2 artifacts."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

CONSUMER = Path(__file__).resolve().parent
ROOT = CONSUMER.parents[1]
GROUP = "io.github.jichuo1.meguviz"
VERSION = "1.0.0"
ARTIFACTS = {
    "core": "aar", "widgets": "aar", "plan": "aar", "recycler": "aar", "text": "aar",
    "layouts": "aar", "prewarm": "aar", "codegen-api": "jar", "codegen": "jar",
}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    base_xml = (ROOT / "gradle/verification-metadata.xml").read_text("utf-8")
    entries = []
    records = {}
    for name, extension in ARTIFACTS.items():
        artifact = f"meguviz-ui-{name}"
        folder = ROOT / "build/repo" / GROUP.replace(".", "/") / artifact / VERSION
        published = folder / f"{artifact}-{VERSION}.{extension}"
        built = (ROOT / f"ui-{name}/build/outputs/aar/ui-{name}-release.aar" if extension == "aar"
                 else ROOT / f"ui-{name}/build/libs/ui-{name}-{VERSION}.jar")
        pom = folder / f"{artifact}-{VERSION}.pom"
        metadata = folder / f"{artifact}-{VERSION}.module"
        for path in (published, built, pom, metadata):
            if not path.is_file():
                raise SystemExit(f"Required current candidate file is missing: {path}")
        if digest(published) != digest(built):
            raise SystemExit(f"Published candidate does not match current module output: {artifact}")
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        document = ET.parse(pom).getroot()
        actual = tuple(document.findtext(f"m:{key}", namespaces=ns) for key in ("groupId", "artifactId", "version"))
        if actual != (GROUP, artifact, VERSION):
            raise SystemExit(f"Unexpected published POM coordinates: {artifact}: {actual}")
        marker = f'<component group="{GROUP}" name="{artifact}" version="{VERSION}">'
        if marker in base_xml:
            raise SystemExit(f"Candidate already exists in baseline verification metadata: {artifact}")
        block = "      " + marker + "\n"
        files = {}
        for path in (published, pom, metadata):
            sha = digest(path)
            files[path.name] = sha
            block += f'         <artifact name="{path.name}">\n            <sha256 value="{sha}" origin="Compared with local release output"/>\n         </artifact>\n'
        block += "      </component>\n"
        entries.append(block)
        records[artifact] = {"built": built.relative_to(ROOT).as_posix(), "files": files}
    if base_xml.count("   </components>") != 1:
        raise SystemExit("Unexpected baseline dependency verification XML")
    result = base_xml.replace("   </components>", "".join(entries) + "   </components>")
    ET.fromstring(result)
    verification = CONSUMER / "gradle/verification-metadata.xml"
    verification.write_text(result, encoding="utf-8")
    report = CONSUMER / "build/artifact-inputs.json"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({"version": VERSION, "artifacts": records}, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared strict verification for {len(records)} published candidates")


if __name__ == "__main__":
    main()
