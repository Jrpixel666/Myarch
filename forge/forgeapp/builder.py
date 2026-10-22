"""Assemble a generated :class:`AppSpec` into a buildable Android project and
run Gradle to produce a debug APK.

The project itself is the fixed template in ``forge/apk-template`` — known-good
and never generated — so the only things that vary per app are the web assets,
the label, the applicationId and the declared permissions. That is what makes
the build reliable: the Gradle/AGP scaffolding is constant.

``prepare_build_dir`` does all the file work and is pure enough to unit-test
without a toolchain; ``run_gradle`` and ``build`` invoke the real build.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

from .generator import AppSpec, slugify

REPO_ROOT = Path(__file__).resolve().parents[2]
TEMPLATE_DIR = REPO_ROOT / "forge" / "apk-template"

LogFn = Callable[[str], None]


class BuildError(RuntimeError):
    pass


def _xml_escape(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def _permissions_block(permissions: list[str]) -> str:
    lines = [
        f'    <uses-permission android:name="{_xml_escape(p)}" />'
        for p in permissions
    ]
    return "\n".join(lines)


def _uses_features_block(needs_location: bool) -> str:
    if not needs_location:
        return ""
    return (
        '    <uses-feature android:name="android.hardware.location" '
        'android:required="false" />'
    )


def _substitute(text: str, mapping: dict[str, str]) -> str:
    for key, value in mapping.items():
        text = text.replace("{{" + key + "}}", value)
    return text


# Template-relative files that carry {{PLACEHOLDER}}s to fill in.
_TEMPLATED_FILES = (
    "app/src/main/res/values/strings.xml",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/dev/myarch/forge/gen/MainActivity.java",
)


def find_sdk_dir(explicit: Optional[str] = None) -> Optional[str]:
    for candidate in (explicit, os.environ.get("ANDROID_HOME"),
                      os.environ.get("ANDROID_SDK_ROOT")):
        if candidate and Path(candidate).is_dir():
            return str(Path(candidate).resolve())
    return None


def prepare_build_dir(
    spec: AppSpec,
    build_dir: Path,
    *,
    version_name: str = "1.0",
    sdk_dir: Optional[str] = None,
) -> Path:
    """Materialise ``spec`` into a self-contained Gradle project at ``build_dir``.

    Returns the build directory. Raises :class:`BuildError` if the template is
    missing. Does not invoke Gradle.
    """
    if not TEMPLATE_DIR.is_dir():
        raise BuildError(f"APK template not found at {TEMPLATE_DIR}")

    build_dir = Path(build_dir)
    if build_dir.exists():
        shutil.rmtree(build_dir)
    # Copy the template, skipping the placeholder keepfile and any stale build.
    shutil.copytree(
        TEMPLATE_DIR,
        build_dir,
        ignore=shutil.ignore_patterns(".gitkeep", "build", ".gradle"),
    )

    # Bring in the exact Gradle wrapper Myarch uses (8.11.1), so we never
    # duplicate the wrapper jar in the template and versions can't drift.
    (build_dir / "gradle" / "wrapper").mkdir(parents=True, exist_ok=True)
    for rel in ("gradlew", "gradlew.bat",
                "gradle/wrapper/gradle-wrapper.jar",
                "gradle/wrapper/gradle-wrapper.properties"):
        src = REPO_ROOT / rel
        if src.exists():
            dst = build_dir / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
    os.chmod(build_dir / "gradlew", 0o755)

    # Fill in the per-app placeholders.
    mapping = {
        "APP_LABEL": _xml_escape(spec.name),
        "APP_ID": spec.app_id,
        "VERSION_NAME": version_name,
        "PERMISSIONS": _permissions_block(spec.extra_permissions),
        "USES_FEATURES": _uses_features_block(spec.needs_location),
        "NEEDS_LOCATION": "true" if spec.needs_location else "false",
    }
    for rel in _TEMPLATED_FILES:
        path = build_dir / rel
        path.write_text(_substitute(path.read_text(), mapping))

    # Write the generated web app into the assets directory.
    assets = build_dir / "app" / "src" / "main" / "assets"
    assets.mkdir(parents=True, exist_ok=True)
    for rel_path, content in spec.files.items():
        out = assets / rel_path
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(content)

    # Point Gradle at the SDK if we can find one; otherwise leave it to the
    # user's own local.properties / environment.
    resolved_sdk = find_sdk_dir(sdk_dir)
    if resolved_sdk:
        (build_dir / "local.properties").write_text(f"sdk.dir={resolved_sdk}\n")

    return build_dir


def run_gradle(build_dir: Path, log: Optional[LogFn] = None, timeout: int = 1800) -> None:
    """Run ``assembleDebug`` in ``build_dir``, streaming output to ``log``."""
    log = log or (lambda _s: None)
    gradlew = build_dir / "gradlew"
    if not gradlew.exists():
        raise BuildError("gradlew is missing from the build directory")

    cmd = [str(gradlew), ":app:assembleDebug", "--no-daemon", "--console=plain"]
    log(f"$ {' '.join(cmd)}")
    proc = subprocess.Popen(
        cmd,
        cwd=str(build_dir),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    try:
        assert proc.stdout is not None
        for line in proc.stdout:
            log(line.rstrip("\n"))
        code = proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        raise BuildError(f"Gradle build timed out after {timeout}s")
    if code != 0:
        raise BuildError(f"Gradle exited with status {code}")


def find_apk(build_dir: Path) -> Path:
    out_dir = build_dir / "app" / "build" / "outputs" / "apk" / "debug"
    apks = sorted(out_dir.glob("*.apk"))
    if not apks:
        raise BuildError(f"no APK produced under {out_dir}")
    return apks[0]


@dataclass
class BuildResult:
    spec: AppSpec
    apk_path: Path
    build_dir: Path
    seconds: float


def build(
    spec: AppSpec,
    *,
    work_root: Path,
    out_dir: Path,
    version_name: str = "1.0",
    sdk_dir: Optional[str] = None,
    log: Optional[LogFn] = None,
) -> BuildResult:
    """End-to-end: prepare, build, and copy the APK into ``out_dir``."""
    log = log or (lambda _s: None)
    slug = slugify(spec.name)
    build_dir = Path(work_root) / f"{slug}"
    started = time.monotonic()

    log(f"Preparing project for “{spec.name}” ({spec.app_id})…")
    prepare_build_dir(spec, build_dir, version_name=version_name, sdk_dir=sdk_dir)

    if not find_sdk_dir(sdk_dir):
        raise BuildError(
            "No Android SDK found. Set ANDROID_HOME (or write sdk.dir in "
            f"{build_dir}/local.properties) and try again. Run `forge doctor`."
        )

    log("Building with Gradle (this takes a few minutes the first time)…")
    run_gradle(build_dir, log=log)

    apk = find_apk(build_dir)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    dest = out_dir / f"{slug}-debug.apk"
    shutil.copy2(apk, dest)
    seconds = time.monotonic() - started
    log(f"Done in {seconds:.0f}s → {dest}")
    return BuildResult(spec=spec, apk_path=dest, build_dir=build_dir, seconds=seconds)
