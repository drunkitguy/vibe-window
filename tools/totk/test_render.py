# SPDX-License-Identifier: GPL-3.0-or-later
"""Smoke test for the render tool (tools/totk/render, docs/PLAN.md T4r).

Builds render_page with render.sh against EDEN_SOURCE_ROOT (zstd.h from ZSTD_DIR), renders every
page of the committed manifest in every fixture state, and checks that a widget key the runtime
does not read fails the run. Skipped without both variables.
"""
import copy
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import check_manifest

RENDER = Path(__file__).resolve().parent / "render"


@unittest.skipUnless(os.environ.get("EDEN_SOURCE_ROOT") and os.environ.get("ZSTD_DIR"),
                     "EDEN_SOURCE_ROOT and ZSTD_DIR not set")
class RenderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.out = Path(cls.tmp.name)
        subprocess.run(["sh", str(RENDER / "render.sh"), os.environ["EDEN_SOURCE_ROOT"],
                        str(cls.out / "build")], check=True, capture_output=True)
        cls.tool = cls.out / "build" / "render_page"
        cls.manifest = json.loads(check_manifest.MANIFEST.read_text())

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def render(self, manifest, page, state, *extra):
        png = self.out / f"{page}-{Path(state).stem}.png"
        run = subprocess.run([str(self.tool), str(manifest), page, str(state), str(png), *extra],
                             capture_output=True, text=True)
        return run, png

    def test_every_page_in_every_state(self):
        states = sorted((RENDER / "states").glob("*.json"))
        self.assertEqual({s.stem for s in states}, {"full", "mixed", "none", "nosave", "notready"})
        for page in (p["id"] for p in self.manifest["pages"]):
            for state in states:
                with self.subTest(page=page, state=state.stem):
                    run, png = self.render(check_manifest.MANIFEST, page, state)
                    self.assertEqual(run.returncode, 0, run.stderr)
                    self.assertEqual(png.read_bytes()[:8], b"\x89PNG\r\n\x1a\n")

    def test_unknown_widget_key_fails(self):
        m = copy.deepcopy(self.manifest)
        page = m["pages"][0]
        page["widgets"][0]["not_a_runtime_key"] = 1
        path = self.out / "manifest.json"
        path.write_text(json.dumps(m))
        (self.out / "assets").symlink_to(check_manifest.MANIFEST.parent / "assets")  # file: srcs
        state = RENDER / "states" / "full.json"
        run, _ = self.render(path, page["id"], state)
        self.assertEqual(run.returncode, 1)
        self.assertIn("'not_a_runtime_key' is not read by the runtime", run.stderr)
        run, _ = self.render(path, page["id"], state, "--allow-unknown-keys")
        self.assertEqual(run.returncode, 0, run.stderr)


if __name__ == "__main__":
    unittest.main()
