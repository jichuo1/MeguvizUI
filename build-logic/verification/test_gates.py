"""Negative release gate contracts; never substitute for Android behavior tests."""
import copy
import contextlib
import io
import hashlib
import json
from pathlib import Path
import tempfile
import time
import unittest

from gates import core_dependency_errors, parse_tests, release, resolved_upstream_errors, source_hash, upstream_snapshot, validate_receipt, write

TEMP_ROOT = Path(__file__).resolve().parents[2] / "build/reports/gates/test-tmp"
TEMP_ROOT.mkdir(parents=True, exist_ok=True)


class GateContracts(unittest.TestCase):
    def setUp(self):
        self.artifacts = {"core.aar": "actual-hash"}
        self.receipt = {"status": "passed", "ready": True, "source_sha256": "current",
                        "profiles": ["core"], "test_count": 1, "artifacts": self.artifacts}

    def check(self, receipt):
        return validate_receipt(receipt, "current", {"core"}, self.artifacts)

    def test_current_complete_evidence_is_accepted(self):
        self.assertEqual([], self.check(self.receipt))

    def test_stale_source_is_rejected(self):
        self.receipt["source_sha256"] = "previous"
        self.assertIn("source revision is stale", self.check(self.receipt))

    def test_zero_tests_are_rejected(self):
        self.receipt["test_count"] = 0
        self.assertIn("zero tests", self.check(self.receipt))

    def test_missing_profile_is_rejected(self):
        self.receipt["profiles"] = []
        self.assertIn("required profile is missing", self.check(self.receipt))

    def test_changed_artifact_is_rejected(self):
        changed = copy.deepcopy(self.receipt)
        changed["artifacts"]["core.aar"] = "old-hash"
        self.assertIn("artifact missing or changed", self.check(changed))

    def test_missing_artifact_is_rejected(self):
        self.receipt["artifacts"] = {}
        self.assertIn("artifact missing or changed", self.check(self.receipt))

    def test_running_and_blocked_cannot_pass(self):
        for status in ("running", "blocked", "not_run", "failed"):
            self.receipt["status"] = status
            self.assertIn("gate is not passed", self.check(self.receipt))

    def test_skip_and_failed_test_xml_is_rejected(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as directory:
            path = Path(directory) / "TEST-example.xml"
            path.write_text('<testsuite tests="2" skipped="1" failures="1" errors="0"/>', encoding="utf-8")
            count, errors = parse_tests([path], time.time())
            self.assertEqual(2, count)
            self.assertTrue(errors)

    def test_missing_and_stale_reports_are_rejected(self):
        self.assertTrue(parse_tests([], time.time())[1])
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as directory:
            path = Path(directory) / "TEST-example.xml"
            path.write_text('<testsuite tests="1" skipped="0" failures="0" errors="0"/>', encoding="utf-8")
            self.assertTrue(parse_tests([path], time.time() + 100)[1])

    def test_core_dependency_boundary_rejects_optional_products(self):
        self.assertEqual([], core_dependency_errors(["project :ui-core", "project ':ui-core'", "org.jetbrains.kotlin:kotlin-stdlib:2.4.21", "org.jetbrains:annotations:13.0"]))
        for dependency in ("com.highcapable.hikage:core:1.1.1", "io.github.jichuo1.meguviz:meguviz-ref-core:0.1.0-SNAPSHOT", "project :ui-testkit", "androidx.appcompat:appcompat:1.8.0"):
            self.assertEqual([dependency], core_dependency_errors([dependency]))

    def test_api_and_ci_are_bound_but_generated_state_is_not(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as directory:
            root = Path(directory)
            (root / "api").mkdir()
            (root / ".github/workflows").mkdir(parents=True)
            (root / "api/public-api.txt").write_text("one", encoding="utf-8")
            before = source_hash(root)
            (root / "PROJECT_STATE.json").write_text('{"status":"running"}', encoding="utf-8")
            self.assertEqual(before, source_hash(root))
            (root / "api/public-api.txt").write_text("two", encoding="utf-8")
            self.assertNotEqual(before, source_hash(root))
            before = source_hash(root)
            (root / ".github/workflows/verify.yml").write_text("name: check", encoding="utf-8")
            self.assertNotEqual(before, source_hash(root))

    def test_release_entry_rejects_empty_or_missing_profile_selection(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as directory:
            root = Path(directory)
            write(root / "integration/profiles.json", {"selected_profiles": ["core", "lumen"], "profiles": {"core": {"enabled": True}, "lumen": {"enabled": True}}})
            for value in (None, [], "core"):
                write(root / "PROJECT_STATE.json", {"execution_target": "full_replacement", "selected_profiles": value})
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(2, release(root, {"core"}))
                receipt = json.loads((root / "build/reports/gates/release.json").read_text(encoding="utf-8"))
                self.assertFalse(receipt["ready"])
                self.assertTrue(any("Invalid selected profiles in PROJECT_STATE.json" in error for error in receipt["errors"]))

    def test_release_entry_rejects_silently_narrowed_declared_scope(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as directory:
            root = Path(directory)
            write(root / "PROJECT_STATE.json", {"execution_target": "full_replacement", "selected_profiles": ["core"]})
            write(root / "integration/profiles.json", {"selected_profiles": ["core", "lumen"], "profiles": {"core": {"enabled": True}, "lumen": {"enabled": True}}})
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2, release(root, {"core"}))
            receipt = json.loads((root / "build/reports/gates/release.json").read_text(encoding="utf-8"))
            self.assertIn("Selected profiles disagree with the integration declaration", receipt["errors"])

    def test_release_entry_rejects_unknown_or_mismatched_target(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as directory:
            root = Path(directory)
            write(root / "integration/profiles.json", {"execution_target": "full_replacement", "selected_profiles": ["core"], "profiles": {"core": {"enabled": True}}})
            for target in ("full_replacment", "core_candidate"):
                write(root / "PROJECT_STATE.json", {"execution_target": target, "selected_profiles": ["core"]})
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(2, release(root, {"core"}))
                receipt = json.loads((root / "build/reports/gates/release.json").read_text(encoding="utf-8"))
                self.assertIn("Execution target disagrees with the integration declaration", receipt["errors"])
                if target == "full_replacment":
                    self.assertIn("Unknown execution_target", receipt["errors"])

    def test_changed_consumer_output_invalidates_receipt(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as directory:
            root = Path(directory)
            output = root / "app.apk"
            output.write_bytes(b"tested")
            self.receipt["outputs"] = {"app.apk": hashlib.sha256(b"tested").hexdigest()}
            self.assertEqual([], validate_receipt(self.receipt, "current", {"core"}, self.artifacts, root))
            output.write_bytes(b"different")
            self.assertTrue(any("output missing or changed" in error for error in validate_receipt(self.receipt, "current", {"core"}, self.artifacts, root)))

    def test_device_receipt_requires_exact_apks_and_executed_reports(self):
        errors = validate_receipt(self.receipt, "current", {"core"}, self.artifacts,
                                  require_reports=True, require_device_apks=True)
        self.assertIn("exact tested application and instrumentation APK hashes are missing", errors)
        self.assertIn("executed test reports are missing", errors)

    def test_mutated_or_old_report_invalidates_receipt(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as directory:
            root = Path(directory)
            report = root / "TEST-contract.xml"
            report.write_bytes(b'<testsuite tests="1"/>')
            self.receipt.update(reports=[report.name], report_sha256={report.name: hashlib.sha256(report.read_bytes()).hexdigest()}, started_epoch=time.time())
            self.assertEqual([], validate_receipt(self.receipt, "current", {"core"}, self.artifacts, root, require_reports=True))
            self.receipt["started_epoch"] = time.time() + 100
            self.assertTrue(any("test report stale or changed" in error for error in validate_receipt(self.receipt, "current", {"core"}, self.artifacts, root, require_reports=True)))

    def pinned_upstream_fixture(self, root):
        relative = Path("io/github/jichuo1/meguviz/meguviz-ref-core/0.1.0-SNAPSHOT")
        version = "0.1.0-20261026.000000-1"
        base = root / "build/upstream-repo" / relative
        base.mkdir(parents=True)
        binary = base / f"meguviz-ref-core-{version}.jar"
        binary.write_bytes(b"hash-validation-fixture")
        sha = hashlib.sha256(binary.read_bytes()).hexdigest()
        metadata = base / "maven-metadata.xml"
        metadata.write_bytes(b"<metadata/>")
        metadata_sha = hashlib.sha256(metadata.read_bytes()).hexdigest()
        (root / "gradle").mkdir()
        (root / "gradle/upstream-candidates.toml").write_text(
            f'[candidates.meguviz-ref-core]\nversion="{version}"\npackaging="jar"\nsha256="{sha}"\n'
            f'[candidates.meguviz-ref-core.files]\n"maven-metadata.xml"="{metadata_sha}"\n', encoding="utf-8")
        manifest = {"artifacts": {"meguviz-ref-core": {"resolved_version": version, "binary_sha256": sha,
            "files": {(relative / binary.name).as_posix(): sha, (relative / metadata.name).as_posix(): metadata_sha}}}}
        write(root / "build/upstream-repo/import-manifest.json", manifest)
        return binary, metadata, manifest

    def test_selected_bridge_requires_pinned_mirror_but_core_does_not(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as directory:
            root = Path(directory)
            self.assertEqual([], upstream_snapshot(root, {"core"})[1])
            self.assertTrue(upstream_snapshot(root, {"core", "ref"})[1])
            binary, _, _ = self.pinned_upstream_fixture(root)
            self.assertEqual([], upstream_snapshot(root, {"core", "ref"})[1])
            binary.write_bytes(b"changed")
            self.assertTrue(upstream_snapshot(root, {"core", "ref"})[1])

    def test_metadata_tampering_cannot_be_hidden_by_rewriting_import_manifest(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as directory:
            root = Path(directory)
            _, metadata, manifest = self.pinned_upstream_fixture(root)
            metadata.write_bytes(b"<changed/>")
            relative = metadata.relative_to(root / "build/upstream-repo").as_posix()
            manifest["artifacts"]["meguviz-ref-core"]["files"][relative] = hashlib.sha256(metadata.read_bytes()).hexdigest()
            write(root / "build/upstream-repo/import-manifest.json", manifest)
            self.assertTrue(any("Unreviewed upstream metadata" in error for error in upstream_snapshot(root, {"core", "ref"})[1]))

    def test_actual_resolved_file_must_match_pin_even_when_report_claims_it_does(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as directory:
            root = Path(directory)
            binary, _, _ = self.pinned_upstream_fixture(root)
            sha = hashlib.sha256(binary.read_bytes()).hexdigest()
            report = root / "ui-ref-bridge/build/reports/runtime-artifacts.tsv"
            report.parent.mkdir(parents=True)
            report.write_text(f"meguviz-ref-core-0.1.0-SNAPSHOT.jar\t{sha}\t{binary}\n", encoding="utf-8")
            self.assertEqual([], resolved_upstream_errors(root, {"core", "ref"}))
            binary.write_bytes(b"cache-changed")
            self.assertTrue(resolved_upstream_errors(root, {"core", "ref"}))

    def test_core_only_r8_receipt_cannot_cover_selected_bridges(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as directory:
            root = Path(directory)
            selected = ["core", "lumen", "ref", "android", "bil"]
            write(root / "PROJECT_STATE.json", {"execution_target": "full_replacement", "selected_profiles": selected})
            write(root / "integration/profiles.json", {"execution_target": "full_replacement", "selected_profiles": selected,
                  "profiles": {name: {"enabled": True} for name in selected}})
            narrow = dict(self.receipt, source_sha256=source_hash(root), profiles=["core"])
            write(root / "build/reports/gates/bridge-r8.json", narrow)
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2, release(root, {"core", "lumen", "ref", "android"}))
            result = json.loads((root / "build/reports/gates/release.json").read_text("utf-8"))
            self.assertIn("bridge-r8: required profile is missing", result["errors"])

    def test_released_ref_uses_resolved_hash_without_snapshot_mirror(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as directory:
            root = Path(directory)
            binary = root / "cache/meguviz-ref-core-1.0.0.jar"
            binary.parent.mkdir(parents=True)
            binary.write_bytes(b"released-ref")
            sha = hashlib.sha256(binary.read_bytes()).hexdigest()
            (root / "gradle").mkdir()
            (root / "gradle/upstream-candidates.toml").write_text(
                f'[candidates.meguviz-ref-core]\nversion="1.0.0"\npackaging="jar"\nsha256="{sha}"\n', encoding="utf-8")
            self.assertEqual([], upstream_snapshot(root, {"core", "ref"})[1])
            report = root / "ui-ref-bridge/build/reports/runtime-artifacts.tsv"
            report.parent.mkdir(parents=True)
            report.write_text(f"{binary.name}\t{sha}\t{binary}\n", encoding="utf-8")
            self.assertEqual([], resolved_upstream_errors(root, {"core", "ref"}))
            report.write_text(f"meguviz-ref-core-0.1.0-SNAPSHOT.jar\t{sha}\t{binary}\n", encoding="utf-8")
            self.assertTrue(resolved_upstream_errors(root, {"core", "ref"}))
            report.write_text(f"{binary.name}\t{sha}\t{binary}\n", encoding="utf-8")
            binary.write_bytes(b"mutated")
            self.assertTrue(resolved_upstream_errors(root, {"core", "ref"}))


if __name__ == "__main__":
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(GateContracts))
    write(Path(__file__).resolve().parents[2] / "build/reports/gates/gate-contracts.json", {
        "status": "passed" if result.wasSuccessful() and result.testsRun > 0 else "failed",
        "test_count": result.testsRun, "failures": len(result.failures), "errors": len(result.errors),
        "skipped": len(result.skipped), "finished_epoch": time.time(),
    })
    raise SystemExit(0 if result.wasSuccessful() and result.testsRun > 0 else 1)
