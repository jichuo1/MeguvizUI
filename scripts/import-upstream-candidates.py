"""Copy explicitly pinned, already published sibling candidates; never builds a sibling project."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import tomllib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
GROUP = Path("io/github/jichuo1/meguviz")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--artifact", action="append", required=True)
    args = parser.parse_args()
    source = args.repository.resolve(strict=True)
    target = (ROOT / "build/upstream-repo").resolve()
    pins = tomllib.loads((ROOT / "gradle/upstream-candidates.toml").read_text("utf-8"))["candidates"]
    receipt_path = target / "import-manifest.json"
    receipt = json.loads(receipt_path.read_text("utf-8")) if receipt_path.exists() else {"schema_version": 1, "artifacts": {}}
    prepared = {}
    records = {}
    for artifact in args.artifact:
        if artifact not in pins:
            raise SystemExit("Artifact is not explicitly pinned: " + artifact)
        pin = pins[artifact]
        if re.fullmatch(r"\d+\.\d+\.\d+", pin["version"]):
            raise SystemExit("Released artifact is resolved from Maven Central; no snapshot import is required: " + artifact)
        relative = GROUP / artifact / "0.1.0-SNAPSHOT"
        base = (source / relative).resolve(strict=True)
        if not base.is_relative_to(source):
            raise SystemExit("Repository path escapes its declared root")
        metadata_path = base / "maven-metadata.xml"
        metadata_bytes = metadata_path.read_bytes()
        if hashlib.sha256(metadata_bytes).hexdigest() != pin["files"]["maven-metadata.xml"]:
            raise SystemExit("Pinned Maven metadata changed: " + artifact)
        entries = ET.fromstring(metadata_bytes).findall("./versioning/snapshotVersions/snapshotVersion")
        primary = [entry for entry in entries if entry.findtext("extension") == pin["packaging"] and entry.find("classifier") is None]
        if len(primary) != 1 or primary[0].findtext("value") != pin["version"]:
            raise SystemExit("Upstream metadata changed; explicitly review the new candidate before importing: " + artifact)
        files = {}
        for entry in entries:
            version = entry.findtext("value", "")
            extension = entry.findtext("extension", "")
            classifier = entry.findtext("classifier")
            if not re.fullmatch(r"0\.1\.0-\d{8}\.\d{6}-\d+", version) or extension not in {"jar", "aar", "pom", "module"} or classifier not in {None, "sources"}:
                raise SystemExit("Unexpected published filename fields")
            name = f"{artifact}-{version}{'-' + classifier if classifier else ''}.{extension}"
            path = (base / name).resolve(strict=True)
            if not path.is_relative_to(base):
                raise SystemExit("Published file escapes version directory")
            data = path.read_bytes()
            sha = hashlib.sha256(data).hexdigest()
            if extension == pin["packaging"] and classifier is None and sha != pin["sha256"]:
                raise SystemExit("Pinned binary checksum mismatch: " + artifact)
            if not (extension == pin["packaging"] and classifier is None) and pin["files"].get(name) != sha:
                raise SystemExit("Unreviewed upstream metadata/source artifact: " + name)
            prepared[relative / name] = data
            files[(relative / name).as_posix()] = sha
        prepared[relative / "maven-metadata.xml"] = metadata_bytes
        files[(relative / "maven-metadata.xml").as_posix()] = hashlib.sha256(metadata_bytes).hexdigest()
        artifact_metadata = (source / GROUP / artifact / "maven-metadata.xml").read_bytes()
        prepared[GROUP / artifact / "maven-metadata.xml"] = artifact_metadata
        if metadata_path.read_bytes() != metadata_bytes:
            raise SystemExit("Upstream changed during import")
        records[artifact] = {"coordinate": f"io.github.jichuo1.meguviz:{artifact}:0.1.0-SNAPSHOT", "resolved_version": pin["version"], "binary_sha256": pin["sha256"], "files": files}
    for relative, data in prepared.items():
        path = (target / relative).resolve()
        if not path.is_relative_to(target):
            raise SystemExit("Destination escapes UI repository")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        if path.read_bytes() != data:
            raise SystemExit("Copied candidate verification failed")
    receipt["artifacts"].update(records)
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", "utf-8")
    print(json.dumps({"copied_artifacts": records, "manifest": str(receipt_path)}, indent=2))


if __name__ == "__main__":
    main()
