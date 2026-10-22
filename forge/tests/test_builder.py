"""Tests for project assembly (no Gradle, no Android SDK needed)."""

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from forgeapp import builder, generator  # noqa: E402


class PrepareBuildDirTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="forge-test-")

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _spec(self, idea="next bus from my location"):
        return generator.generate(idea, force_offline=True)

    def test_produces_expected_tree(self):
        spec = self._spec()
        bd = builder.prepare_build_dir(spec, Path(self.tmp) / "app")
        for rel in (
            "settings.gradle.kts",
            "build.gradle.kts",
            "app/build.gradle.kts",
            "app/src/main/AndroidManifest.xml",
            "app/src/main/java/dev/myarch/forge/gen/MainActivity.java",
            "app/src/main/assets/index.html",
            "gradlew",
            "gradle/wrapper/gradle-wrapper.jar",
        ):
            self.assertTrue((bd / rel).exists(), f"missing {rel}")

    def test_placeholders_substituted(self):
        spec = self._spec()
        bd = builder.prepare_build_dir(spec, Path(self.tmp) / "app")

        strings = (bd / "app/src/main/res/values/strings.xml").read_text()
        self.assertIn(spec.name, strings)
        self.assertNotIn("{{", strings)

        gradle = (bd / "app/build.gradle.kts").read_text()
        self.assertIn(spec.app_id, gradle)
        self.assertNotIn("{{", gradle)

        manifest = (bd / "app/src/main/AndroidManifest.xml").read_text()
        self.assertIn("ACCESS_FINE_LOCATION", manifest)  # location app
        self.assertIn("android.hardware.location", manifest)
        self.assertNotIn("{{", manifest)

        activity = (bd / "app/src/main/java/dev/myarch/forge/gen/MainActivity.java").read_text()
        self.assertIn("NEEDS_LOCATION = true", activity)
        self.assertNotIn("{{", activity)

    def test_non_location_app_has_no_location_permission(self):
        spec = self._spec("a pomodoro timer")
        bd = builder.prepare_build_dir(spec, Path(self.tmp) / "app")
        manifest = (bd / "app/src/main/AndroidManifest.xml").read_text()
        self.assertNotIn("ACCESS_FINE_LOCATION", manifest)
        activity = (bd / "app/src/main/java/dev/myarch/forge/gen/MainActivity.java").read_text()
        self.assertIn("NEEDS_LOCATION = false", activity)

    def test_gradlew_executable(self):
        spec = self._spec()
        bd = builder.prepare_build_dir(spec, Path(self.tmp) / "app")
        self.assertTrue(os.access(bd / "gradlew", os.X_OK))

    def test_label_xml_escaped(self):
        spec = generator.generate("x", force_offline=True)
        spec.name = 'Tom & "Jerry"'
        bd = builder.prepare_build_dir(spec, Path(self.tmp) / "app")
        strings = (bd / "app/src/main/res/values/strings.xml").read_text()
        self.assertIn("&amp;", strings)
        self.assertIn("&quot;", strings)
        self.assertNotIn("Tom & ", strings)

    def test_rebuild_overwrites_cleanly(self):
        spec = self._spec()
        target = Path(self.tmp) / "app"
        builder.prepare_build_dir(spec, target)
        (target / "stale.txt").write_text("old")
        builder.prepare_build_dir(spec, target)  # should wipe and recopy
        self.assertFalse((target / "stale.txt").exists())


class HelperTests(unittest.TestCase):
    def test_permissions_block(self):
        block = builder._permissions_block(
            ["android.permission.CAMERA", "android.permission.RECORD_AUDIO"])
        self.assertIn("CAMERA", block)
        self.assertEqual(block.count("uses-permission"), 2)

    def test_empty_features_block(self):
        self.assertEqual(builder._uses_features_block(False), "")
        self.assertIn("location", builder._uses_features_block(True))

    def test_find_apk_errors_when_absent(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(builder.BuildError):
                builder.find_apk(Path(d))


if __name__ == "__main__":
    unittest.main()
