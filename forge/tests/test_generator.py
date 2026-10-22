"""Tests for the pure generation logic (no network, no Claude)."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from forgeapp import generator  # noqa: E402


class SlugAndPackageTests(unittest.TestCase):
    def test_slugify(self):
        self.assertEqual(generator.slugify("Next Bus!"), "next-bus")
        self.assertEqual(generator.slugify("  Weird   Name  "), "weird-name")
        self.assertEqual(generator.slugify("***"), "app")

    def test_package_segment_is_valid(self):
        self.assertEqual(generator.package_segment("Next Bus"), "nextbus")
        self.assertEqual(generator.package_segment("123 go"), "a123go")
        self.assertEqual(generator.package_segment("!!!"), "app")

    def test_derive_app_id(self):
        self.assertEqual(
            generator.derive_app_id("Next Bus"),
            "dev.myarch.forge.gen.nextbus",
        )


class LocationInferenceTests(unittest.TestCase):
    def test_keyword_hits(self):
        self.assertTrue(generator.infer_location("next bus from my location", {}))
        self.assertTrue(generator.infer_location("weather near me", {}))

    def test_geolocation_in_code(self):
        files = {"index.html": "<script>navigator.geolocation.getCurrentPosition()</script>"}
        self.assertTrue(generator.infer_location("my app", files))

    def test_no_location(self):
        self.assertFalse(generator.infer_location("a pomodoro timer", {}))


class NormaliseTests(unittest.TestCase):
    def test_requires_index_html(self):
        with self.assertRaises(ValueError):
            generator.normalise_spec(
                idea="x", name="X", files={"app.js": "x"},
                needs_location=False, extra_permissions=[], source="claude",
            )

    def test_strips_path_traversal(self):
        spec = generator.normalise_spec(
            idea="x", name="X",
            files={"index.html": "<html>", "../evil.sh": "rm -rf", "/abs.txt": "a"},
            needs_location=False, extra_permissions=[], source="claude",
        )
        self.assertIn("index.html", spec.files)
        self.assertNotIn("../evil.sh", spec.files)
        self.assertIn("abs.txt", spec.files)  # leading slash stripped, kept

    def test_location_adds_permissions(self):
        spec = generator.normalise_spec(
            idea="bus near me", name="Bus",
            files={"index.html": "<html>"},
            needs_location=None, extra_permissions=None, source="offline",
        )
        self.assertTrue(spec.needs_location)
        self.assertIn("android.permission.ACCESS_FINE_LOCATION", spec.extra_permissions)

    def test_drops_non_android_permissions(self):
        spec = generator.normalise_spec(
            idea="x", name="X", files={"index.html": "<html>"},
            needs_location=False,
            extra_permissions=["android.permission.CAMERA", "root.access", "rm -rf /"],
            source="claude",
        )
        self.assertEqual(spec.extra_permissions, ["android.permission.CAMERA"])


class JSONExtractionTests(unittest.TestCase):
    def test_plain(self):
        self.assertEqual(generator._extract_json('{"a": 1}'), {"a": 1})

    def test_fenced(self):
        self.assertEqual(
            generator._extract_json('```json\n{"a": 1}\n```'), {"a": 1})

    def test_with_prose_and_braces_in_strings(self):
        text = 'Here you go:\n{"name": "a {weird} name", "n": 2} trailing'
        self.assertEqual(
            generator._extract_json(text), {"name": "a {weird} name", "n": 2})

    def test_no_json(self):
        with self.assertRaises(ValueError):
            generator._extract_json("no object here")


class OfflineGenerationTests(unittest.TestCase):
    def test_location_template(self):
        spec = generator.generate("next bus from my location", force_offline=True)
        self.assertEqual(spec.source, "offline")
        self.assertTrue(spec.needs_location)
        self.assertIn("navigator.geolocation", spec.files["index.html"])
        self.assertTrue(spec.app_id.startswith("dev.myarch.forge.gen."))

    def test_timer_template(self):
        spec = generator.generate("a 25 minute pomodoro timer", force_offline=True)
        self.assertFalse(spec.needs_location)
        self.assertIn("setInterval", spec.files["index.html"])

    def test_note_template(self):
        spec = generator.generate("a quick note app", force_offline=True)
        self.assertIn("localStorage", spec.files["index.html"])

    def test_generic_placeholder(self):
        spec = generator.generate("a chess engine with alpha-beta pruning", force_offline=True)
        self.assertIn("index.html", spec.files)

    def test_empty_idea_rejected(self):
        with self.assertRaises(ValueError):
            generator.generate("   ", force_offline=True)


if __name__ == "__main__":
    unittest.main()
