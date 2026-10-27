#!/usr/bin/env python3
"""Run clean, incremental and invalid-declaration KSP checks in an isolated source copy."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", required=True, type=Path, help="New directory under the project build directory")
    args = parser.parse_args()
    workspace = args.workspace.resolve()
    build_root = (ROOT / "build").resolve()
    if not workspace.is_relative_to(build_root) or workspace == build_root or workspace.exists():
        parser.error("workspace must be a new directory below this project's build directory")
    workspace.mkdir(parents=True)
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
    for name in filter(None, tracked):
        source = ROOT / name
        if source.is_file():
            target = workspace / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    for name in ("ui-codegen-api", "ui-codegen", "samples/generated-consumer"):
        shutil.copytree(ROOT / name, workspace / name, dirs_exist_ok=True,
                        ignore=shutil.ignore_patterns("build", "__pycache__"))
    shutil.copy2(ROOT / "local.properties", workspace / "local.properties")
    logs = workspace / "validation-logs"
    logs.mkdir()
    windows = os.name == "nt"
    gradle = (["powershell", "-NoProfile", "-File", str(workspace / "scripts/gradle-local.ps1")]
              if windows else [str(workspace / "gradlew")])
    command = gradle + ["-PenableCodegen=true", ":samples:generated-consumer:assembleDebug", "--no-build-cache", "--no-daemon", "--max-workers=2"]

    def run(name: str, expected: bool) -> str:
        result = subprocess.run(command, cwd=workspace, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
        (logs / f"{name}.log").write_text(result.stdout, encoding="utf-8")
        if (result.returncode == 0) != expected:
            raise RuntimeError(f"{name}: unexpected exit {result.returncode}; see {logs / (name + '.log')}")
        return result.stdout

    generated = workspace / "samples/generated-consumer/build/generated/ksp/debug/kotlin/io/github/jichuo1/meguviz/samples/generated/GeneratedLabelUiGenerated.kt"
    app = workspace / "samples/generated-consumer/src/main/kotlin/io/github/jichuo1/meguviz/samples/generated/MainActivity.kt"
    try:
        fresh = run("fresh-build", True)
        if "> Task :samples:generated-consumer:kspDebugKotlin FROM-CACHE" in fresh or "> Task :samples:generated-consumer:kspDebugKotlin UP-TO-DATE" in fresh:
            raise RuntimeError("Fresh build did not execute KSP")
        original = generated.read_text("utf-8")
        if 'ComponentType("generated.label", GeneratedLabel::class.java, 1)' not in original:
            raise RuntimeError("Fresh build generated unexpected type identity")
        source = app.read_text("utf-8")
        changed = source.replace('@GenerateUiFactory(id = "generated.label")', '@GenerateUiFactory(id = "generated.label.changed", schema = 2)')
        if changed == source:
            raise RuntimeError("Expected source declaration not found")
        app.write_text(changed, encoding="utf-8")
        incremental = run("incremental-version-change", True)
        if "> Task :samples:generated-consumer:kspDebugKotlin FROM-CACHE" in incremental or "> Task :samples:generated-consumer:kspDebugKotlin UP-TO-DATE" in incremental:
            raise RuntimeError("Incremental declaration change did not execute KSP")
        updated = generated.read_text("utf-8")
        if 'ComponentType("generated.label.changed", GeneratedLabel::class.java, 2)' not in updated or updated == original:
            raise RuntimeError("Incremental generation did not update id and schema")
        invalid = workspace / "samples/generated-consumer/src/main/kotlin/io/github/jichuo1/meguviz/samples/generated/InvalidDeclaration.kt"
        invalid.write_text('''package io.github.jichuo1.meguviz.samples.generated
import android.content.Context
import android.view.View
import io.github.jichuo1.meguviz.ui.codegen.GenerateUiFactory
@GenerateUiFactory(id = "invalid")
class InvalidDeclaration(context: Context, extra: Int) : View(context) { val value = extra }
''', encoding="utf-8")
        output = run("invalid-constructor", False)
        if "requires a public top-level concrete non-generic View" not in output:
            raise RuntimeError("Build failed for a reason other than invalid declaration")
        invalid.write_text('''package io.github.jichuo1.meguviz.samples.generated
import android.content.Context
import android.view.View
class ValidAfterConstructor(context: Context) : View(context)
''', encoding="utf-8")
        keyword = invalid.parent / "InvalidKeyword.kt"
        keyword.write_text('''package io.github.jichuo1.meguviz.samples.generated
import android.content.Context
import android.view.View
import io.github.jichuo1.meguviz.ui.codegen.GenerateUiFactory
@GenerateUiFactory(id = "invalid-keyword")
class `class`(context: Context) : View(context)
''', encoding="utf-8")
        output = run("invalid-keyword", False)
        if "requires a conventional class name in a named package" not in output:
            raise RuntimeError("Build failed for a reason other than invalid keyword name")
        keyword.write_text('''package io.github.jichuo1.meguviz.samples.generated
import android.content.Context
import android.view.View
class ValidAfterKeyword(context: Context) : View(context)
''', encoding="utf-8")
        removable = invalid.parent / "RemovedLabel.kt"
        removable.write_text('''package io.github.jichuo1.meguviz.samples.generated
import android.content.Context
import android.view.View
import io.github.jichuo1.meguviz.ui.codegen.GenerateUiFactory
@GenerateUiFactory(id = "generated.remove")
class RemovedLabel(context: Context) : View(context)
''', encoding="utf-8")
        removed_output = generated.parent / "RemovedLabelUiGenerated.kt"
        run("add-isolating-declaration", True)
        if not removed_output.is_file():
            raise RuntimeError("Added declaration did not generate a source")
        removable.write_text('''package io.github.jichuo1.meguviz.samples.generated
import android.content.Context
import android.view.View
class RemovedLabel(context: Context) : View(context)
''', encoding="utf-8")
        run("remove-isolating-declaration", True)
        if removed_output.exists():
            raise RuntimeError("Stale generated source survived removal of its declaration")
        result = {"status": "passed", "checks": ["fresh-build", "incremental-version-change", "invalid-constructor", "invalid-keyword", "incremental-declaration-removal"],
                  "scope": "Host compilation only; R8 device behavior and performance are separate"}
        (logs / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(json.dumps(result))
        return 0
    except (OSError, RuntimeError) as error:
        result = {"status": "failed", "reason": str(error)}
        (logs / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(json.dumps(result), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
