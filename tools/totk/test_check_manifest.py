# SPDX-License-Identifier: GPL-3.0-or-later
"""Tests for check_manifest.py and gen_manifest.py.

python3 -I -m unittest discover -s tools/totk
The runtime-literal rule runs against a stand-in source tree holding every key the committed
manifest uses, so these tests need no Eden Duo checkout; with EDEN_SOURCE_ROOT set, the committed
manifest is also checked against the real runtime sources.
"""
import copy
import json
import os
import tempfile
import unittest
from pathlib import Path

import check_manifest
import gen_manifest


def all_keys(value, out):
    if isinstance(value, dict):
        for key, child in value.items():
            out.add(key)
            all_keys(child, out)
    elif isinstance(value, list):
        for child in value:
            all_keys(child, out)
    return out


class CheckManifestTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = check_manifest.MANIFEST.read_bytes()
        cls.manifest = json.loads(cls.raw)
        cls.patterns, cls.actions = check_manifest.parse_keys_header(
            check_manifest.KEYS_HEADER.read_text())
        with tempfile.TemporaryDirectory() as eden:
            mods = Path(eden) / "src/core/mods"
            mods.mkdir(parents=True)
            (mods / "stand_in.cpp").write_text(
                "".join(f'"{k}";\n' for k in sorted(all_keys(cls.manifest, set()))))
            cls.literals = check_manifest.runtime_literals(eden)

    def errors(self, manifest, size=0):
        return check_manifest.check(manifest, self.literals, self.patterns, self.actions,
                                    size)[0]

    def fails(self, mutate, expect):
        m = copy.deepcopy(self.manifest)
        mutate(m)
        errors = self.errors(m)
        self.assertTrue(any(expect in e for e in errors), f"{expect!r} not in {errors}")

    def gear(self, m):
        return next(p for p in m["pages"] if p["id"] == "gear")["widgets"]

    def test_committed_manifest_passes(self):
        self.assertEqual(self.errors(self.manifest, len(self.raw)), [])

    @unittest.skipUnless(os.environ.get("EDEN_SOURCE_ROOT"), "EDEN_SOURCE_ROOT not set")
    def test_committed_manifest_passes_real_runtime(self):
        literals = check_manifest.runtime_literals(os.environ["EDEN_SOURCE_ROOT"])
        self.assertEqual(check_manifest.check(self.manifest, literals, self.patterns,
                                              self.actions, len(self.raw))[0], [])

    def test_header_parse(self):
        self.assertEqual(self.actions, {
            "totk.ui.page", "totk.inv.tab", "totk.inv.tab_next", "totk.inv.select",
            "totk.map.layer", "totk.gear.swap", "totk.inv.use", "totk.ability.select"})
        self.assertNotIn("totk.", self.patterns)  # the header's comments are not keys
        listed = lambda k: any(r.fullmatch(k) for r in self.patterns.values())
        for key in ("totk.ready", "totk.gear.arrow.count", "totk.ability.travel.owned",
                    "totk.inv.{i}.icon", "totk.inv.12.icon", "totk.inv.tab7.count"):
            self.assertTrue(listed(key), key)
        for key in ("totk.gear.head.present", "totk.inv.01.icon", "totk.inv.tab8.count",
                    "totk.gear.swap", "totk.names.valid"):
            self.assertFalse(listed(key), key)

    def test_unknown_object_key(self):
        self.fails(lambda m: self.gear(m)[0].update(bogus_key=1), "key 'bogus_key'")
        self.fails(lambda m: m.update(bogus_top=1), "key 'bogus_top'")

    def test_names_are_not_runtime_keys(self):
        m = copy.deepcopy(self.manifest)
        m["actions"]["not_a_runtime_literal"] = m["actions"]["ui_page_0"]
        self.assertEqual(self.errors(m), [])

    def test_bad_action_kind(self):
        for kind in ("write", "slot_write", "call", "sequence", "button", "flag", None):
            self.fails(lambda m: m["actions"]["ui_page_0"].update(kind=kind), "kind")

    def test_unknown_module_action(self):
        self.fails(lambda m: m["actions"]["gear_swap_0"].update(action="totk.gear.drop"),
                   "module action 'totk.gear.drop'")

    def test_undefined_references(self):
        self.fails(lambda m: self.gear(m)[0].update(on_tap="nope"), "on_tap 'nope'")
        self.fails(lambda m: self.gear(m)[0].update(on_swipe_left="nope"), "on_swipe_left")
        self.fails(lambda m: self.gear(m)[0].update(drop_action="nope"), "drop_action")
        self.fails(lambda m: m["page_binds"][1]["when_equal"].update(page="nowhere"),
                   "page 'nowhere'")
        self.fails(lambda m: m["actions"].update(go={"kind": "page", "page": "nowhere"}),
                   "page 'nowhere'")
        self.fails(lambda m: self.gear(m)[0].update(scroll="nope"), "scroll 'nope'")
        self.fails(lambda m: m.update(enforce=[{"action": "nope", "every_ms": 1}]), "enforce")

    def test_rect(self):
        self.fails(lambda m: self.gear(m)[0].update(rect=[1200, 0, 100, 10]), "leaves")
        self.fails(lambda m: self.gear(m)[0].update(rect=[0, 1000, 10, 100]), "leaves")
        self.fails(lambda m: self.gear(m)[0].update(rect=[-1, 0, 10, 10]), "leaves")
        self.fails(lambda m: self.gear(m)[0].update(rect=[0, 0, 10]), "4 numbers")
        self.fails(lambda m: self.gear(m)[0].update(rect=[0, 0, "10", 10]), "4 numbers")

    def test_string_hide_eq(self):
        def mutate(m):
            notice = next(w for w in self.gear(m) if "hide_eq" in w)
            notice["hide_eq"] = "1"
        self.fails(mutate, "hide_eq")

    def test_bind_not_in_keys_header(self):
        self.fails(lambda m: self.gear(m)[0].update(need_bind="totk.gear.helmet.present"),
                   "'totk.gear.helmet.present'")
        self.fails(lambda m: self.gear(m)[0].update(need_bind="!totk.names.valid"),
                   "'totk.names.valid'")
        self.fails(lambda m: m["derived"].append({"name": "x", "expr": "totk.bogus * 2"}),
                   "'totk.bogus'")

    def test_too_big(self):
        errors = self.errors(self.manifest, check_manifest.MAX_BYTES + 1)
        self.assertTrue(any("over" in e for e in errors), errors)


class GenManifestTest(unittest.TestCase):
    def test_committed_files_are_generated(self):
        for name, data in gen_manifest.outputs().items():
            self.assertEqual((gen_manifest.OUT / name).read_bytes(), data, name)

    def test_gear_card_states(self):
        """.present 1: item widgets; 0: "None"; absent: nothing (every gate is a need_bind on
        .present or on a derived value that is missing while .present is)."""
        m = gen_manifest.manifest()
        widgets = m["pages"][0]["widgets"]
        derived = {d["name"]: d for d in m["derived"]}
        for slot in ("weapon", "bow", "shield", "arrow"):
            present = f"totk.gear.{slot}.present"
            self.assertEqual(derived[f"ui.gear.{slot}.none"],
                             {"name": f"ui.gear.{slot}.none", "cmp": "eq", "a": present, "b": 0})
            self.assertIn(present, derived[f"ui.gear.{slot}.known"]["expr"])
            card = [w for w in widgets if f"totk.gear.{slot}." in json.dumps(w) or
                    f"ui.gear.{slot}." in json.dumps(w)]
            self.assertTrue(card)
            for w in card:
                self.assertNotIn("hide_bind", w)
                self.assertFalse(w["need_bind"].startswith("!"), w)


if __name__ == "__main__":
    unittest.main()
