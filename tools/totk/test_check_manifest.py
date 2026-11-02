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
import re
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
            "totk.ui.page": 4, "totk.inv.tab": 8, "totk.inv.tab_next": 0, "totk.inv.select": 0,
            "totk.map.layer": 3, "totk.gear.swap": 4, "totk.inv.use": 0,
            "totk.ability.select": 7})
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

    def label(self, m, text):
        return next(w for w in self.gear(m) if w.get("text") == text)

    def test_aligned_text_extent(self):
        # "No save data yet" at scale 5 is 320 px wide: anchored at x 24 it starts at x -136.
        self.fails(lambda m: self.label(m, "No save data yet").update(rect=[24, 400, 1192, 60]),
                   "center-aligned text 'No save data yet'")
        self.fails(lambda m: self.label(m, "No save data yet").update(rect=[1100, 400, 0, 60]),
                   "off the canvas")
        self.fails(lambda m: self.label(m, "No save data yet").update(align="right",
                                                                       rect=[300, 400, 0, 60]),
                   "right-aligned text")
        ok = copy.deepcopy(self.manifest)  # right-aligned, ending inside the canvas: fine
        self.label(ok, "No save data yet").update(align="right", rect=[1216, 400, 0, 60])
        self.assertEqual(self.errors(ok), [])

    def test_gates_resolve(self):
        self.fails(lambda m: self.gear(m)[2].update(need_bind="ui.gear.weapon.knwn"),
                   "'ui.gear.weapon.knwn' is not a totk_keys.h key, a derived name")
        self.fails(lambda m: self.gear(m)[2].update(need_bind="!ui.nothing"), "'ui.nothing'")
        self.fails(lambda m: self.gear(m)[2].update(bind_text="module_error_mesage"),
                   "'module_error_mesage'")
        self.fails(lambda m: m["page_binds"][0].update(point="totk.ui.pag"), "'totk.ui.pag'")
        self.fails(lambda m: m["derived"].append({"name": "x", "cmp": "eq", "a": "ui.no", "b": 0}),
                   "'ui.no'")
        self.fails(lambda m: m["derived"].append({"name": "x", "expr": "ui.nothing * 0 + 1"}),
                   "'ui.nothing'")

    def test_expr_parse(self):
        names = check_manifest.expr_names
        self.assertEqual(names("totk.a * 0 + b.c * 0 + 1"), ["totk.a", "b.c"])
        self.assertEqual(names("x ? max(y, 0x1F, 1e3) : -'odd name' % .5"),
                         ["x", "y", "odd name"])
        self.assertEqual(names("@clock.hour >= 19 || !(a && b != 2)"), ["@clock.hour", "a", "b"])
        for bad in ("a * * 1", "a +", "(a", "a b", "max(a,", "a ? b", "nofn(a)", "", "a $ b"):
            with self.assertRaises(ValueError, msg=bad):
                names(bad)
        self.fails(lambda m: m["derived"][0].update(expr="totk.status.hearts * * 1"),
                   "does not parse")

    def test_duplicates(self):
        self.fails(lambda m: m["pages"][1].update(id="gear"), "page id 'gear' is defined twice")
        self.fails(lambda m: m["derived"].append(dict(m["derived"][0])), "is defined twice")

    def test_module_argument(self):
        for arg in ("zero", "1", 4, -1, True, 1.0):
            self.fails(lambda m: m["actions"]["ui_page_0"].update(argument=arg), "argument")
        ok = copy.deepcopy(self.manifest)
        ok["actions"]["ui_page_0"]["argument"] = "$payload"
        ok["actions"]["gear_swap_0"]["argument"] = 3
        self.assertEqual(self.errors(ok), [])

    def test_widget_type(self):
        self.fails(lambda m: self.gear(m)[1].update(type="lable"), "type 'lable'")
        self.fails(lambda m: self.gear(m)[1].pop("type"), "type None")

    def test_value_types(self):
        self.fails(lambda m: self.gear(m)[1].update(text_scale="4"), "text_scale '4'")
        self.fails(lambda m: self.gear(m)[1].update(wrap_width=True), "wrap_width True")
        self.fails(lambda m: m.update(canvas_w="1240"), "canvas_w")
        self.fails(lambda m: self.gear(m)[1].update(color="#GG0000"), "is not a colour")
        self.fails(lambda m: self.gear(m)[1].update(bg="#FF1A2028AA"), "is not a colour")

    def test_text_map_key(self):
        def mutate(m):
            w = next(w for w in m["pages"][0]["widgets"] if "text_map" in w)
            w["text_map"]["one"] = "From last save"
        self.fails(mutate, "text_map key 'one'")

    def test_enums(self):
        self.fails(lambda m: m["haptics"].update(tap="clik"), "haptics.tap")
        self.fails(lambda m: self.gear(m)[1].update(haptic="loud"), "haptic 'loud'")
        self.fails(lambda m: m["page_binds"][0]["when_equal"].update(transition="fdae"),
                   "transition 'fdae'")
        self.fails(lambda m: m["page_binds"][0]["when_equal"].update(easing="ease"),
                   "easing 'ease'")
        self.fails(lambda m: next(d for d in m["derived"] if "cmp" in d).update(cmp="equals"),
                   "cmp 'equals'")

    def test_too_big(self):
        errors = self.errors(self.manifest, check_manifest.MAX_BYTES + 1)
        self.assertTrue(any("over" in e for e in errors), errors)


class GenManifestTest(unittest.TestCase):
    """The generated manifest's behaviour, evaluated with the runtime's rules: need_bind "k" hides
    unless k is published and non-zero, "!k" hides only when it is non-zero; hide_bind reads a
    missing value as 0; a derived cmp/expr has no value while one of its inputs is missing."""

    def setUp(self):
        self.m = gen_manifest.manifest()
        self.derived = {d["name"]: d for d in self.m["derived"]}
        self.gear = self.page("gear")

    def page(self, page_id):
        return next(p for p in self.m["pages"] if p["id"] == page_id)["widgets"]

    def value(self, key, snap):
        if key in snap:
            return snap[key]
        d = self.derived.get(key)
        if d is None:
            return None
        if "cmp" in d:
            a, b = (d[s] if isinstance(d[s], int) else self.value(d[s], snap) for s in "ab")
            if a is None or b is None:
                return None
            return int({"eq": a == b, "ne": a != b, "ge": a >= b, "gt": a > b,
                        "le": a <= b, "lt": a < b}[d["cmp"]])
        # The generator's only expr form: "k * 0 + ... + 1" (1 while every k exists).
        self.assertRegex(d["expr"], r"^(?:[\w.]+ \* 0 \+ )+1$")
        names = check_manifest.expr_names(d["expr"])
        return None if any(self.value(n, snap) is None for n in names) else 1

    def visible(self, w, snap):
        gate = w.get("need_bind", "")
        if gate:
            v = self.value(gate.lstrip("!"), snap)
            shown = v is not None and v != 0
            if shown == gate.startswith("!"):
                return False
        if "hide_bind" in w:
            hv = self.value(w["hide_bind"], snap) or 0
            if "hide_eq" in w and hv == w["hide_eq"]:
                return False
        return True

    def test_committed_files_are_generated(self):
        for name, data in gen_manifest.outputs().items():
            self.assertEqual((gen_manifest.OUT / name).read_bytes(), data, name)

    def test_tabs_and_page_binds(self):
        """Tab k sends totk.ui.page k on every page, and totk.ui.page == k shows PAGES[k]."""
        actions = self.m["actions"]
        for page_id, _, _ in gen_manifest.PAGES:
            tabs = [w for w in self.page(page_id) if w["type"] == "button"]
            self.assertEqual(len(tabs), len(gen_manifest.PAGES), page_id)
            for k, (_, caption, _) in enumerate(gen_manifest.PAGES):
                tab = next(w for w in tabs if w["text"] == caption)
                self.assertEqual(tab["rect"], [310 * k, 968, 310, 112])
                self.assertEqual(actions[tab["on_tap"]], {
                    "kind": "module", "action": "totk.ui.page", "argument": k}, (page_id, k))
        binds = self.m["page_binds"]
        self.assertEqual(len(binds), len(gen_manifest.PAGES))
        for k, (page_id, _, _) in enumerate(gen_manifest.PAGES):
            matching = [b for b in binds if b["equals"] == k]
            self.assertEqual(len(matching), 1, k)
            self.assertEqual(matching[0]["point"], "totk.ui.page")
            self.assertEqual(matching[0]["when_equal"]["page"], page_id, k)

    def test_card_hold_sends_its_slot(self):
        """Card k (weapon, bow, shield, arrows) holds to totk.gear.swap k."""
        cards = sorted((w for w in self.gear if w["type"] == "rect" and "on_hold" in w),
                       key=lambda w: w["rect"][0])
        self.assertEqual(len(cards), len(gen_manifest.SLOTS))
        for k, card in enumerate(cards):
            self.assertEqual(card["rect"], [24 + 300 * k, 140, 284, 430])
            self.assertEqual(self.m["actions"][card["on_hold"]], {
                "kind": "module", "action": "totk.gear.swap", "argument": k}, k)

    def test_empty_state(self):
        """"No save data yet" shows while totk.gear.valid is 0 or absent, never at 1."""
        label = next(w for w in self.gear if w.get("text") == "No save data yet")
        for valid, shown in ((None, True), (0, True), (1, False)):
            snap = {"totk.ready": 1} if valid is None else {"totk.ready": 1,
                                                            "totk.gear.valid": valid}
            self.assertEqual(self.visible(label, snap), shown, valid)

    def test_notice(self):
        """The notice covers every page while totk.ready is absent or 0, and goes at 1."""
        for page_id, _, _ in gen_manifest.PAGES:
            notice = [w for w in self.page(page_id) if w.get("hide_bind") == "totk.ready"]
            self.assertEqual(len(notice), 3, page_id)
            self.assertTrue(notice[0]["input_block"])
            self.assertEqual(self.page(page_id)[-3:], notice)  # on top
            for ready, shown in ((None, True), (0, True), (1, False)):
                snap = {} if ready is None else {"totk.ready": ready}
                for w in notice:
                    self.assertEqual(self.visible(w, snap), shown, (page_id, ready))

    def test_gear_card_states(self):
        """Each card's exact visible widgets per state of .present (and .fuse, the value)."""
        def role(w, caption):
            src = w.get("src_bind", "") + w.get("bind_text", "")
            for suffix, name in ((".fuse_icon", "fuse_icon"), (".icon", "image"),
                                 (".fuse_name", "fuse_name"), (".name", "name"),
                                 (".effect", "effect")):
                if src.endswith(suffix):
                    return name
            return {"rect": "card", "value": "value"}.get(w["type"]) or (
                "caption" if w.get("text") == caption else
                "none" if w.get("text") == "None" else f"unknown {w}")

        for k, (slot, caption, field, _) in enumerate(gen_manifest.SLOTS):
            x0, g = 24 + 300 * k, f"totk.gear.{slot}"
            card = [w for w in self.gear if x0 <= w["rect"][0] <= x0 + 284 and
                    120 <= w["rect"][1] < 570]
            fuses = slot in ("weapon", "shield")
            item = {"caption", "card", "image", "name", "value"}
            if fuses:
                item |= {"fuse_icon", "fuse_name"}
            if slot != "arrow":
                item.add("effect")
            full = {f"{g}.present": 1, f"{g}.{field}": 42}  # only numbers gate (SnapshotNumber)
            if fuses:
                full[f"{g}.fuse"] = 1
            no_value = {k: v for k, v in full.items() if not k.endswith(field)}
            states = [("present 1", full, item),
                      ("present 1, value unknown", no_value, item - {"value"}),
                      ("present 0", {f"{g}.present": 0}, {"caption", "card", "none"}),
                      ("absent", {}, set())]
            if fuses:  # the module publishes .fuse 0 for an unfused weapon or shield
                states.append(("present 1, fuse 0", {**full, f"{g}.fuse": 0},
                               item - {"fuse_icon", "fuse_name"}))
            for state, snap, expect in states:
                snap = {"totk.ready": 1, "totk.gear.valid": 1, **snap}
                shown = sorted(role(w, caption) for w in card if self.visible(w, snap))
                self.assertEqual(shown, sorted(expect), f"{slot}, {state}")

if __name__ == "__main__":
    unittest.main()
