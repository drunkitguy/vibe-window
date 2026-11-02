# SPDX-License-Identifier: GPL-3.0-or-later
"""Smoke test for the render tool (tools/totk/render, docs/PLAN.md T4r).

Builds render_page with render.sh against EDEN_SOURCE_ROOT (zstd.h from ZSTD_DIR), renders every
page of the committed manifest in every fixture state, checks that a widget key the runtime
does not read fails the run, and that an --assets PNG replaces a fixture key's placeholder (the
tree needs patch 0012, which draws a src_bind romfs: text). Skipped without both variables.
"""
import copy
import json
import os
import re
import struct
import subprocess
import tempfile
import unittest
import zlib
from pathlib import Path

import check_manifest

RENDER = Path(__file__).resolve().parent / "render"


def solid_png(rgba):
    """A 1x1 RGBA PNG."""
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\x00" + bytes(rgba))) + chunk(b"IEND", b""))


def pixel(path, x, y):
    """RGB at (x, y) of an 8-bit RGBA PNG, unfiltering rows 0..y only."""
    data, pos, idat = path.read_bytes(), 8, b""
    while pos < len(data):
        n = int.from_bytes(data[pos:pos + 4], "big")
        kind, body = data[pos + 4:pos + 8], data[pos + 8:pos + 8 + n]
        if kind == b"IHDR":
            width = struct.unpack(">I", body[:4])[0]
        elif kind == b"IDAT":
            idat += body
        pos += 12 + n
    raw, stride, prev = zlib.decompress(idat), width * 4, bytearray(width * 4)
    for row in range(y + 1):
        start = row * (stride + 1)
        f, line = raw[start], bytearray(raw[start + 1:start + 1 + stride])
        for i in range(stride):
            a, b = line[i - 4] if i >= 4 else 0, prev[i]
            c = prev[i - 4] if i >= 4 else 0
            p = a + b - c
            paeth = (a if abs(p - a) <= min(abs(p - b), abs(p - c))
                     else b if abs(p - b) <= abs(p - c) else c)
            pred = (0, a, b, (a + b) // 2, paeth)[f]
            line[i] = (line[i] + pred) & 255
        prev = line
    return tuple(prev[x * 4:x * 4 + 3])


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

    def test_assets_png_replaces_placeholder(self):
        state = RENDER / "states" / "full.json"
        key = json.loads(state.read_text())["texts"]["totk.gear.weapon.icon"]
        assets = self.out / "dump"
        assets.mkdir()
        dumped = assets / (re.sub(r"[^A-Za-z0-9._-]", "_", key) + ".png")
        dumped.write_bytes(solid_png((255, 0, 0, 255)))
        page, (x, y, w, h) = next(
            (p["id"], wd["rect"]) for p in self.manifest["pages"] for wd in p["widgets"]
            if wd.get("src_bind") == "totk.gear.weapon.icon")
        run, png = self.render(check_manifest.MANIFEST, page, state)
        self.assertEqual(run.returncode, 0, run.stderr)
        red = lambda rgb: rgb[0] > 200 and rgb[1] < 30 and rgb[2] < 30  # after the widget's tint
        self.assertFalse(red(pixel(png, x + w // 2, y + h // 2)))  # the grey placeholder
        run, png = self.render(check_manifest.MANIFEST, page, state, "--assets", str(assets))
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertTrue(red(pixel(png, x + w // 2, y + h // 2)))


if __name__ == "__main__":
    unittest.main()
