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

    def home(self, m):
        return next(p for p in m["pages"] if p["id"] == "home")["widgets"]

    def test_committed_manifest_passes(self):
        self.assertEqual(self.errors(self.manifest, len(self.raw)), [])

    @unittest.skipUnless(os.environ.get("EDEN_SOURCE_ROOT"), "EDEN_SOURCE_ROOT not set")
    def test_committed_manifest_passes_real_runtime(self):
        literals = check_manifest.runtime_literals(os.environ["EDEN_SOURCE_ROOT"])
        self.assertEqual(check_manifest.check(self.manifest, literals, self.patterns,
                                              self.actions, len(self.raw))[0], [])

    def test_header_parse(self):
        self.assertEqual(self.actions, {
            "totk.ui.page": 3, "totk.inv.tab": 8, "totk.inv.tab_next": 0, "totk.inv.select": 0,
            "totk.map.layer": 3, "totk.gear.swap": 4, "totk.inv.use": 0,
            "totk.ability.select": 7})
        self.assertNotIn("totk.", self.patterns)  # the header's comments are not keys
        listed = lambda k: any(r.fullmatch(k) for r in self.patterns.values())
        for key in ("totk.ready", "totk.gear.arrow.count", "totk.ability.travel.owned",
                    "totk.inv.{i}.icon", "totk.inv.12.icon", "totk.inv.tab7.count"):
            self.assertTrue(listed(key), key)
        for key in ("totk.gear.head.present", "totk.inv.01.icon", "totk.inv.0.name",
                    "totk.inv.tab8.count",
                    "totk.gear.swap", "totk.inv.sel.colour"):
            self.assertFalse(listed(key), key)
        self.assertTrue(listed("totk.names.valid") and listed("totk.inv.sel.caption"))

    def test_unknown_object_key(self):
        self.fails(lambda m: self.home(m)[0].update(bogus_key=1), "key 'bogus_key'")
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
        self.fails(lambda m: self.home(m)[0].update(on_tap="nope"), "on_tap 'nope'")
        self.fails(lambda m: self.home(m)[0].update(on_swipe_left="nope"), "on_swipe_left")
        self.fails(lambda m: self.home(m)[0].update(drop_action="nope"), "drop_action")
        self.fails(lambda m: m["page_binds"][1]["when_equal"].update(page="nowhere"),
                   "page 'nowhere'")
        self.fails(lambda m: m["actions"].update(go={"kind": "page", "page": "nowhere"}),
                   "page 'nowhere'")
        self.fails(lambda m: self.home(m)[0].update(scroll="nope"), "scroll 'nope'")
        self.fails(lambda m: m.update(enforce=[{"action": "nope", "every_ms": 1}]), "enforce")

    def test_rect(self):
        self.fails(lambda m: self.home(m)[0].update(rect=[1200, 0, 100, 10]), "leaves")
        self.fails(lambda m: self.home(m)[0].update(rect=[0, 1000, 10, 100]), "leaves")
        self.fails(lambda m: self.home(m)[0].update(rect=[-1, 0, 10, 10]), "leaves")
        self.fails(lambda m: self.home(m)[0].update(rect=[0, 0, 10]), "4 numbers")
        self.fails(lambda m: self.home(m)[0].update(rect=[0, 0, "10", 10]), "4 numbers")

    def test_string_hide_eq(self):
        def mutate(m):
            notice = next(w for w in self.home(m) if "hide_eq" in w)
            notice["hide_eq"] = "1"
        self.fails(mutate, "hide_eq")

    def test_bind_not_in_keys_header(self):
        self.fails(lambda m: self.home(m)[0].update(need_bind="totk.gear.helmet.present"),
                   "'totk.gear.helmet.present'")
        self.fails(lambda m: self.home(m)[0].update(need_bind="!totk.inv.sel.colour"),
                   "'totk.inv.sel.colour'")
        self.fails(lambda m: m["derived"].append({"name": "x", "expr": "totk.bogus * 2"}),
                   "'totk.bogus'")

    def label(self, m, text):
        return next(w for w in self.home(m) if w.get("text") == text)

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
        self.fails(lambda m: self.home(m)[2].update(need_bind="ui.gear.weapon.knwn"),
                   "'ui.gear.weapon.knwn' is not a totk_keys.h key, a derived name")
        self.fails(lambda m: self.home(m)[2].update(need_bind="!ui.nothing"), "'ui.nothing'")
        self.fails(lambda m: self.home(m)[2].update(bind_text="module_error_mesage"),
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
        self.fails(lambda m: m["pages"][1].update(id="home"), "page id 'home' is defined twice")
        self.fails(lambda m: m["derived"].append(dict(m["derived"][0])), "is defined twice")

    def test_module_argument(self):
        for arg in ("zero", "1", 3, -1, True, 1.0):
            self.fails(lambda m: m["actions"]["ui_page_0"].update(argument=arg), "argument")
        ok = copy.deepcopy(self.manifest)
        ok["actions"]["ui_page_0"]["argument"] = "$payload"
        ok["actions"]["gear_swap_0"]["argument"] = 3
        self.assertEqual(self.errors(ok), [])

    def test_widget_type(self):
        self.fails(lambda m: self.home(m)[1].update(type="lable"), "type 'lable'")
        self.fails(lambda m: self.home(m)[1].pop("type"), "type None")

    def test_value_types(self):
        self.fails(lambda m: self.home(m)[1].update(text_scale="4"), "text_scale '4'")
        self.fails(lambda m: self.home(m)[1].update(wrap_width=True), "wrap_width True")
        self.fails(lambda m: m.update(canvas_w="1240"), "canvas_w")
        self.fails(lambda m: self.home(m)[1].update(color="#GG0000"), "is not a colour")
        self.fails(lambda m: self.home(m)[1].update(bg="#FF1A2028AA"), "is not a colour")

    def test_text_map_key(self):
        def mutate(m):
            w = next(w for w in m["pages"][0]["widgets"] if "text_map" in w)
            w["text_map"]["one"] = "From last save"
        self.fails(mutate, "text_map key 'one'")

    def test_enums(self):
        self.fails(lambda m: m["haptics"].update(tap="clik"), "haptics.tap")
        self.fails(lambda m: self.home(m)[1].update(haptic="loud"), "haptic 'loud'")
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
            if hv < w.get("keep_min", hv) or hv > w.get("keep_max", hv):
                return False
        return True

    def element(self, w, i):
        """Repeat template `w` as element i: "{i}" replaced in its strings, keep bounds "{i}" -> i
        (the runtime's ExpandWidgets)."""
        e = {k: v.replace("{i}", str(i)) if isinstance(v, str) else v for k, v in w.items()}
        for k in ("keep_min", "keep_max"):
            if w.get(k) == "{i}":
                e[k] = i
        return e

    def named(self, roles):
        """The manifest with every TEX role in `roles` named romfs:/T8A/<role> (as after T8a)."""
        saved = dict(gen_manifest.TEX)
        try:
            gen_manifest.TEX.update({r: {"src": f"romfs:/T8A/{r}"} for r in roles})
            return gen_manifest.manifest()
        finally:
            gen_manifest.TEX.clear()
            gen_manifest.TEX.update(saved)

    def test_committed_files_are_generated(self):
        for name, data in gen_manifest.outputs().items():
            self.assertEqual((gen_manifest.OUT / name).read_bytes(), data, name)

    def action(self, name):
        return self.m["actions"][name]

    def page_action(self, k):
        return {"kind": "module", "action": "totk.ui.page", "argument": k}

    def test_tabs_and_page_binds(self):
        """Every page's header: Map | Inventory | Abilities send totk.ui.page 2, 0, 1; the current
        tab has the select capsule; L/R and header swipes go to the screen neighbours, wrapping;
        totk.ui.page == k shows PAGES[k]."""
        captions = [c for _, c, _ in gen_manifest.PAGES]
        for k, (page_id, _, _) in enumerate(gen_manifest.PAGES):
            widgets = self.page(page_id)
            tabs = [w for w in widgets if w["type"] == "button" and w.get("text") in captions]
            self.assertEqual([w["text"] for w in tabs], ["Inventory", "Abilities", "Map"])
            for tab in tabs:
                j = captions.index(tab["text"])
                self.assertEqual(tab["rect"], [{2: 140, 0: 470, 1: 800}[j], 8, 300, 60])
                self.assertEqual(self.action(tab["on_tap"]), self.page_action(j), (page_id, j))
                self.assertEqual(tab["color"], gen_manifest.TEXT if j == k else gen_manifest.DIM)
                self.assertEqual(tab["text_scale"], 6)  # R5: tab labels at scale 6
            rings = [w for w in widgets if w.get("pill") and w.get("color") == gen_manifest.SELECT
                     and w["rect"][1] == 8]
            self.assertEqual([r["rect"][0] for r in rings], [gen_manifest.TAB_X[k]], page_id)
            left, right = {0: (2, 1), 1: (0, 2), 2: (1, 0)}[k]
            for glyph, target in (("", left), ("", right)):
                hint = next(w for w in widgets if w.get("text") == glyph)
                self.assertEqual(self.action(hint["on_tap"]), self.page_action(target))
            bar = next(w for w in widgets if w["rect"] == [0, 0, 1240, 76])
            self.assertEqual(self.action(bar["on_swipe_left"]), self.page_action(right))
            self.assertEqual(self.action(bar["on_swipe_right"]), self.page_action(left))
        binds = self.m["page_binds"]
        self.assertEqual(len(binds), len(gen_manifest.PAGES))
        for k, (page_id, _, _) in enumerate(gen_manifest.PAGES):
            matching = [b for b in binds if b["equals"] == k]
            self.assertEqual(len(matching), 1, k)
            self.assertEqual(matching[0]["point"], "totk.ui.page")
            self.assertEqual(matching[0]["when_equal"]["page"], page_id, k)

    def test_card_hold_sends_its_slot(self):
        """Holding the weapon, bow or shield card or the arrows chip sends totk.gear.swap with
        that slot (0-3)."""
        holds = {tuple(w["rect"]): w["on_hold"] for w in self.page("home")
                 if w.get("on_hold", "").startswith("gear_swap_")}
        expect = {(1100, 324, 112, 112): 0, (1100, 494, 112, 112): 1, (1100, 664, 112, 112): 2,
                  (1040, 212, 192, 64): 3}
        self.assertEqual(set(holds), set(expect))
        for rect, k in expect.items():
            self.assertEqual(self.action(holds[rect]), {
                "kind": "module", "action": "totk.gear.swap", "argument": k}, k)

    def test_category_tabs(self):
        """Category tab k sends totk.inv.tab k; its underline shows only while totk.inv.tab == k;
        its label is text there and dim elsewhere; with TEX.cat_<k> named (T8a), its icon is drawn
        at full tint on the current tab and B3FFFFFF on the others, one variant at a time."""
        tabs = [w for w in self.page("home") if w.get("on_tap", "").startswith("inv_tab_")]
        self.assertEqual([w["rect"][0] for w in tabs], [8 + 78 * k for k in range(8)])
        named = next(p for p in self.named([f"cat_{k}" for k in range(8)])["pages"]
                     if p["id"] == "home")["widgets"]
        shorts = [s for s, _ in gen_manifest.CATEGORIES]
        self.assertEqual(shorts, ["WPN", "BOW", "SHD", "ARM", "MAT", "FOOD", "ZON", "KEY"])
        for k, tab in enumerate(tabs):
            self.assertEqual(self.action(tab["on_tap"]), {
                "kind": "module", "action": "totk.inv.tab", "argument": k})
            line = next(w for w in self.page("home") if w["rect"] == [8 + 78 * k + 8, 152, 62, 4])
            labels = [w for w in self.page("home") if w.get("text") == shorts[k] and
                      w["rect"] == [8 + 78 * k + 39, 126, 0, 20]]
            icons = [w for w in named if w.get("src") == f"romfs:/T8A/cat_{k}"]
            self.assertEqual(len(labels), 2, k)
            self.assertEqual(len(icons), 2, k)
            for icon in icons:
                self.assertEqual(icon["rect"], [8 + 78 * k + 11, 92, 56, 56])
            for current in range(8):
                snap = {"totk.ready": 1, "totk.inv.tab": current}
                self.assertEqual(self.visible(line, snap), current == k, (k, current))
                self.assertEqual([w["color"] for w in labels if self.visible(w, snap)],
                                 [gen_manifest.TEXT if current == k else gen_manifest.DIM])
                self.assertEqual([w.get("tint") for w in icons if self.visible(w, snap)],
                                 [None if current == k else "#B3FFFFFF"], (k, current))

    GRID_KEYS = {"repeat": 400, "repeat_bind": "totk.inv.count", "repeat_cols": 5,
                 "repeat_dx": 124, "repeat_row_dy": 124, "scroll": "inv_grid"}

    def grid(self):
        return [w for w in self.page("home") if "scroll" in w]

    def test_grid_region_and_templates(self):
        """One scroll region inv_grid over the grid frame (5 columns, 124 px rows, the item count,
        offset reset on a tab change); every grid template repeats 400 times along it."""
        home = next(p for p in self.m["pages"] if p["id"] == "home")
        self.assertEqual(home["scrolls"], [{
            "id": "inv_grid", "rect": [8, 194, 624, 520], "count_bind": "totk.inv.count",
            "row_h": 124, "cols": 5, "reset_bind": "totk.inv.tab", "bar": gen_manifest.DIM,
            "bar_w": 4}])
        self.assertTrue(all(p.get("scrolls") is None for p in self.m["pages"] if p is not home))
        grid = self.grid()
        self.assertEqual(len(grid), 10)
        for w in grid:
            self.assertEqual({k: w[k] for k in self.GRID_KEYS}, self.GRID_KEYS, w)
        self.one(grid, type="image", rect=[16, 202, 108, 108], src_bind="totk.inv.{i}.icon",
                 draggable=None)
        self.one(grid, type="image", rect=[16, 202, 40, 40], src_bind="totk.inv.{i}.fuse_icon")

    def grid_role(self, w):
        if w.get("on_tap"):
            return "tap"
        if w.get("draggable"):
            return "drag"
        if w["type"] == "image":
            return "fuse" if w["src_bind"].endswith(".fuse_icon") else "icon"
        if w["type"] == "value":
            return "count" if w.get("text") == "x" else "durability"
        if w.get("bg") == gen_manifest.SHADE:
            return "shade"
        if w["rect"] == [107, 205, 14, 14]:
            return "mark"
        if w.get("frame") == 4 and w.get("color") == gen_manifest.SELECT:
            return "cursor"
        return "slot" if w.get("bg") == gen_manifest.PANEL else f"unknown {w}"

    def test_grid_cell_states(self):
        """Element i's visible cell parts per state: the value shade and number only for kind 1
        (durability) or 2 ("x" count), never for kind 0 or an absent kind (a value of 0 still
        shows); the equipped mark only at .equipped 1; the cursor and the drag handle only on the
        selected element (totk.inv.sel == i)."""
        i, base = 3, ["slot", "icon", "fuse", "tap"]
        for state, snap, expect in (
                ("durability, equipped, selected",
                 {"kind": 1, "value": 42, "equipped": 1, "sel": 3},
                 base + ["drag", "shade", "durability", "mark", "cursor"]),
                ("count 0, not equipped, other selected",
                 {"kind": 2, "value": 0, "equipped": 0, "sel": 2}, base + ["shade", "count"]),
                ("armor: kind 0", {"kind": 0, "sel": 4}, base),
                ("nothing published but the selection", {"sel": 0}, base),
                ("selected, kind 3 (unknown)", {"kind": 3, "value": 5, "sel": 3},
                 base + ["drag", "cursor"])):
            full = {"totk.ready": 1, "totk.inv.valid": 1, "totk.inv.count": 5}
            for k, v in snap.items():
                full["totk.inv.sel" if k == "sel" else f"totk.inv.{i}.{k}"] = v
            shown = sorted(self.grid_role(w) for w in self.grid()
                           if self.visible(self.element(w, i), full))
            self.assertEqual(shown, sorted(expect), state)
        for w in self.grid():
            if w["type"] == "value":
                self.assertEqual((w["bind"], w["align"], w["rect"]),
                                 ("totk.inv.{i}.value", "right", [122, 288, 0, 20]))
            if self.grid_role(w) == "mark":
                self.assertEqual(w["need_bind"], "totk.inv.{i}.equipped")

    def test_inventory_gestures(self):
        """Tap a cell: totk.inv.select with the cell's index; hold a cell or the detail icon:
        totk.inv.use (refused). Only the selected cell drags (the rest scroll and swipe), its hold
        is drawn above it (runtime 16 arms a hold over a drag only then), and the detail pane takes
        the drop as totk.inv.use. Swiping the grid left: the next category."""
        grid, home = self.grid(), self.page("home")
        tap = self.one(grid, on_tap="inv_select")
        self.assertEqual((tap["payload"], tap["on_hold"], tap["rect"]),
                         ("{i}", "inv_use", [12, 198, 116, 116]))
        self.assertEqual(self.action("inv_select"), {"kind": "module", "action": "totk.inv.select",
                                                     "argument": "$payload"})
        use = {"kind": "module", "action": "totk.inv.use", "argument": 0}
        self.assertEqual(self.action("inv_use"), use)
        drags = [w for w in self.m["pages"][0]["widgets"] if w.get("draggable")]
        self.assertEqual(len(drags), 1)
        drag = drags[0]
        self.assertEqual((drag["payload"], drag["select_group"], drag["src_bind"]),
                         ("{i}", "inv", "totk.inv.{i}.icon"))
        for sel in (2, 3, 4):
            snap = {"totk.ready": 1, "totk.inv.sel": sel}
            self.assertEqual(self.visible(self.element(drag, 3), snap), sel == 3)
        self.assertGreater(home.index(tap), home.index(drag))
        drop = self.one(home, drop_action="inv_use")
        self.assertEqual((drop["rect"], drop["accept_group"], drop["highlight_color"]),
                         ([8, 722, 624, 206], "inv", gen_manifest.SELECT))
        self.assertEqual(home.index(drop), max(i for i, w in enumerate(home)
                                               if 722 <= w["rect"][1] < 928 and
                                               w["rect"][0] < 632))
        holds = [w for w in home if w.get("on_hold") == "inv_use"]
        self.assertEqual(sorted(w["rect"] for w in holds),
                         [[12, 198, 116, 116], [20, 736, 128, 128]])
        frame = self.one(home, rect=[8, 194, 624, 520], type="rect")
        self.assertEqual(self.action(frame["on_swipe_left"]), {
            "kind": "module", "action": "totk.inv.tab_next", "argument": 0})

    def test_detail_pane(self):
        """The selected item's parts per state: icon slot and every text only while a selection is
        published (.kind; an absent text would draw "-"); Equipped badge only at .equipped 1;
        "Durability n" for kind 1, "x n" for kind 2 (0 shows), nothing for kind 0 or absent; food's
        hearts only while published. Texts bind their exact keys."""
        sel, home = "totk.inv.sel", self.page("home")
        pane = [w for w in home if 722 <= w["rect"][1] < 928 and w["rect"][0] < 632 and
                w["rect"] != [8, 722, 624, 206]]
        roles = {
            "slot": self.one(pane, type="rect", rect=[20, 736, 128, 128]),
            "icon": self.one(pane, type="image", src_bind=f"{sel}.icon", rect=[20, 736, 128, 128]),
            "name": self.one(pane, bind_text=f"{sel}.name", rect=[164, 738, 316, 34],
                             text_scale=6, max_lines=1, fit_text=True),
            "ring": self.one(pane, type="button", rect=[488, 738, 132, 32], pill=True),
            "badge": self.one(pane, text="Equipped", align="center", rect=[554, 744, 0, 20]),
            "durability": self.one(pane, type="value", bind=f"{sel}.value", text="Durability ",
                                   rect=[164, 778, 280, 26], text_scale=5),
            "count": self.one(pane, type="value", bind=f"{sel}.value", text="x",
                              rect=[164, 778, 280, 26], text_scale=5),
            "hearts": self.one(pane, type="value", bind=f"{sel}.hearts", align="right"),
            "fuse": self.one(pane, bind_text=f"{sel}.fuse_name", rect=[164, 808, 220, 24]),
            "effect": self.one(pane, bind_text=f"{sel}.effect", rect=[620, 808, 0, 24],
                               align="right"),
            "caption": self.one(pane, bind_text=f"{sel}.caption", rect=[164, 836, 456, 84],
                                text_scale=4, wrap_width=456, max_lines=3),
        }
        self.assertEqual(len(pane), len(roles))
        texts = ["name", "fuse", "effect", "caption"]
        item = ["slot", "icon"] + texts
        for state, snap, expect in (
                ("nothing selected", {}, []),
                ("weapon, equipped", {"kind": 1, "value": 42, "equipped": 1},
                 item + ["ring", "badge", "durability"]),
                ("food: count 0, hearts 0", {"kind": 2, "value": 0, "equipped": 0, "hearts": 0},
                 item + ["count", "hearts"]),
                ("armor", {"kind": 0}, item)):
            full = {"totk.ready": 1, "totk.inv.valid": 1,
                    **{f"{sel}.{k}": v for k, v in snap.items()}}
            shown = sorted(r for r, w in roles.items() if self.visible(w, full))
            self.assertEqual(shown, sorted(expect), state)

    def test_ability_bar(self):
        """Ability k taps totk.ability.select k; its icon and name show owned or locked."""
        home = self.page("home")
        for k, (a, name, x) in enumerate(gen_manifest.ABILITIES):
            rect = [18 + 172 * k + 54, 946, 64, 64]
            frame = next(w for w in home if w["rect"] == rect and w["type"] == "rect")
            self.assertEqual(self.action(frame["on_tap"]), {
                "kind": "module", "action": "totk.ability.select", "argument": k})
            parts = [w for w in home if w["rect"] in (rect, [18 + 172 * k + 86, 1014, 0, 22])
                     and w is not frame]
            self.assertEqual(len(parts), 4, a)
            self.assertTrue(all(w.get("src", "").endswith(f"/Obj_{x}.bntx.zs")
                                for w in parts if w["type"] == "image"))
            owned_key = f"totk.ability.{a}.owned"
            for valid, owned, expect in ((None, None, None), (0, None, None), (1, 0, "locked"),
                                         (1, None, "locked"), (1, 1, "owned")):
                snap = {"totk.ready": 1}
                if valid is not None:
                    snap["totk.ability.valid"] = valid
                if owned is not None:
                    snap[owned_key] = owned
                shown = {w.get("color", w.get("tint")) for w in parts if self.visible(w, snap)}
                self.assertEqual(shown, {
                    None: set(), "owned": {gen_manifest.TEXT, None},
                    "locked": {gen_manifest.DIM, gen_manifest.LOCKED}}[expect], (a, valid, owned))

    def one(self, widgets, **fields):
        found = [w for w in widgets if all(w.get(k) == v for k, v in fields.items())]
        self.assertEqual(len(found), 1, fields)
        return found[0]

    def test_status_and_text_gates(self):
        """Status bars, values and captions, the category counts and names, the source line and
        the later pages: exact binds, and each shown only while its keys are published (a .valid
        key also hides at 0; a value of 0 still shows)."""
        home, st = self.page("home"), "totk.status."
        hearts, stamina = [st + "hearts", st + "max_hearts"], [st + "max_stamina"]
        cases = [
            (self.one(home, type="bar", bind=st + "hearts", max_bind=st + "max_hearts",
                      color=gen_manifest.HEART), hearts),
            (self.one(home, type="value", bind=st + "hearts", max_bind=st + "max_hearts",
                      max_sep="/", align="right", rect=[1220, 98, 0, 32]), hearts),
            (self.one(home, type="bar", bind=st + "max_stamina", max_bind=st + "max_stamina",
                      color=gen_manifest.STAMINA), stamina),
            (self.one(home, type="value", bind=st + "max_stamina", text="Max ", align="right",
                      rect=[1220, 152, 0, 32]), stamina),
            (self.one(home, type="value", bind=st + "rupees", group=True), [st + "rupees"]),
            (self.one(home, type="value", bind=st + "max_battery", div=100),
             [st + "max_battery"]),
            (self.one(home, type="label", text="Rupees"), [st + "valid"]),
            (self.one(home, type="label", text="Battery"), [st + "valid"]),
            *((self.one(home, type="value", bind=f"totk.inv.tab{k}.count", align="right",
                        rect=[8 + 78 * k + 70, 90, 0, 20]), ["totk.inv.valid"])
              for k in range(8)),
        ]
        names = self.one(home, text_bind="totk.inv.tab")
        self.assertEqual(names["text_map"], {str(k): n for k, n in enumerate([
            "Weapons", "Bows and Arrows", "Shields", "Armor", "Materials", "Food",
            "Zonai Devices", "Key Items"])})
        cases.append((names, ["totk.inv.valid"]))
        for page_id, domain in (("home", "gear"), ("abilities", "ability"), ("map", "map")):
            ws = self.page(page_id)
            source = self.one(ws, text_bind=f"totk.{domain}.source", rect=[8, 1052, 600, 24],
                              text_map={"1": "From last save", "2": "Live"})
            cases.append((source, [f"totk.{domain}.valid"]))
            if page_id != "home":
                later = self.one(ws, text="Coming in a later update")
                cases.append((later, [f"totk.{domain}.valid"]))
        for w, keys in cases:
            snap = {"totk.ready": 1, **{k: 1 for k in keys}}
            self.assertTrue(self.visible(w, snap), w)
            for k in keys:
                self.assertFalse(self.visible(w, {x: v for x, v in snap.items() if x != k}),
                                 (w, k))
                self.assertEqual(self.visible(w, {**snap, k: 0}), not k.endswith(".valid"),
                                 (w, k))

    def test_plate_and_anchors(self):
        """The plate shows the weapon's own icon and fuse badge; every centre- or right-aligned
        label and value is anchored at its column's centre (rect x, width 0)."""
        home, w = self.page("home"), "totk.gear.weapon"
        self.one(home, rect=[808, 420, 256, 256], src_bind=f"{w}.icon",
                 need_bind=f"{w}.present")
        self.one(home, rect=[1000, 420, 72, 72], src_bind=f"{w}.fuse_icon",
                 need_bind=f"{w}.fuse")
        anchors = {122, 320, 554, 620, 936, 1156, 1220} | {104 + 172 * k for k in range(7)} | \
            {47 + 78 * k for k in range(8)} | {78 + 78 * k for k in range(8)}
        for page_id, _, _ in gen_manifest.PAGES:
            for wd in self.page(page_id):
                if wd["type"] in ("label", "value") and wd.get("align") in ("center", "right"):
                    self.assertEqual(wd["rect"][2], 0, (page_id, wd))
                    self.assertIn(wd["rect"][0], anchors, (page_id, wd))

    def test_textures_over_fallbacks(self):
        """Every picture sits over a widget drawn before it that covers its rect, so a texture or
        icon that does not load leaves the page readable; with every TEX role named (as after
        T8a), each role is drawn and still over its fallback. None = UNKNOWN emits no picture."""
        def check(m):
            for page in m["pages"]:
                ws = page["widgets"]
                for i, w in enumerate(ws):
                    if w["type"] != "image":
                        continue
                    x, y, wd, ht = w["rect"]
                    self.assertTrue(any(
                        u["type"] != "image" and u["rect"][0] <= x and u["rect"][1] <= y and
                        x + wd <= u["rect"][0] + u["rect"][2] and
                        y + ht <= u["rect"][1] + u["rect"][3] for u in ws[:i]),
                        (page["id"], w))
        check(self.m)
        unknown = {r for r, t in gen_manifest.TEX.items() if t is None}
        self.assertTrue(unknown)
        srcs = {w.get("src") for p in self.m["pages"] for w in p["widgets"]}
        self.assertFalse({f"romfs:/T8A/{r}" for r in unknown} & srcs)
        saved = dict(gen_manifest.TEX)
        try:
            gen_manifest.TEX.update({r: {"src": f"romfs:/T8A/{r}"} for r in unknown})
            named = gen_manifest.manifest()
        finally:
            gen_manifest.TEX.clear()
            gen_manifest.TEX.update(saved)
        check(named)
        used = {w["src"] for p in named["pages"] for w in p["widgets"] if "src" in w}
        self.assertEqual({gen_manifest.TEX[r]["src"] if gen_manifest.TEX[r] else
                          f"romfs:/T8A/{r}" for r in gen_manifest.TEX}, used)

    def test_empty_state(self):
        """"No save data yet" shows in the grid while totk.inv.valid is 0 or absent and on the
        weapon plate while totk.gear.valid is, never at 1; "Nothing here" only for an empty tab."""
        home = self.page("home")
        for domain in ("inv", "gear"):
            label = next(w for w in home if w.get("text") == "No save data yet" and
                         w["need_bind"] == f"!totk.{domain}.valid")
            for valid, shown in ((None, True), (0, True), (1, False)):
                snap = {"totk.ready": 1} if valid is None else {
                    "totk.ready": 1, f"totk.{domain}.valid": valid}
                self.assertEqual(self.visible(label, snap), shown, (domain, valid))
        nothing = next(w for w in home if w.get("text") == "Nothing here")
        for count, shown in ((None, False), (0, True), (3, False)):
            snap = {"totk.ready": 1} if count is None else {"totk.ready": 1,
                                                            "totk.inv.count": count}
            self.assertEqual(self.visible(nothing, snap), shown, count)

    def test_bound_texts_gated(self):
        """Every label bound to a totk.* text has a need_bind: the runtime draws an absent text as
        "-", so a line shows only with its item (whose optional texts the module publishes, empty
        when there is nothing)."""
        for page_id, _, _ in gen_manifest.PAGES:
            for w in self.page(page_id):
                if w.get("bind_text", "").startswith("totk."):
                    self.assertTrue(w.get("need_bind"), (page_id, w))

    def test_fit_text_floor(self):
        """Every fit_text label shrinks no smaller than scale 4 (20 px): the runtime's floor is 1,
        and these are single lines, so text that still does not fit ends in "..." instead."""
        fits = [w for p in self.m["pages"] for w in p["widgets"] if w.get("fit_text")]
        self.assertTrue(fits)
        for w in fits:
            self.assertGreaterEqual(w.get("text_min_scale", 1), 4, w)
            self.assertEqual(w.get("max_lines"), 1, w)

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
        """Each slot's exact visible widgets (its card, the weapon's plate, the arrows chip) per
        state of .present (and .fuse, the value): 1 the item, 0 "None", absent nothing."""
        captions = {"weapon": "WEAPON", "bow": "BOW", "shield": "SHIELD", "arrow": "Arrows"}

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

        home = self.page("home")
        for slot, caption in captions.items():
            gated = re.compile(rf"!?(?:totk|ui)\.gear\.{slot}\.")
            mine = [w for w in home if gated.match(w.get("need_bind", ""))]
            field = "count" if slot == "arrow" else "life"
            fuses = slot in gen_manifest.FUSES
            item = ["image", "value"] + (["caption", "card"] if slot != "arrow" else ["caption"])
            if slot == "weapon":  # the plate: icon, name, effect, fuse badge and fuse name
                item += ["image", "name", "effect", "fuse_icon", "fuse_name"]
            if slot in ("bow", "shield"):
                item.append("effect")
            if fuses:
                item.append("fuse_icon")
            full = {f"totk.gear.{slot}.present": 1, f"totk.gear.{slot}.{field}": 42}
            if fuses:
                full[f"totk.gear.{slot}.fuse"] = 1
            no_value = {k: v for k, v in full.items() if not k.endswith(field)}
            unfused = [r for r in item if r not in ("fuse_icon", "fuse_name")]
            states = [("present 1", full, item),
                      ("present 1, value unknown", no_value, [r for r in item if r != "value"]),
                      ("present 0", {f"totk.gear.{slot}.present": 0},
                       [r for r in ("caption", "card", "none")
                        if slot != "arrow" or r != "card"]),
                      ("absent", {}, [])]
            if fuses:  # the module publishes .fuse 0 for an unfused weapon or shield
                states.append(("present 1, fuse 0", {**full, f"totk.gear.{slot}.fuse": 0},
                               unfused))
            for state, snap, expect in states:
                snap = {"totk.ready": 1, "totk.gear.valid": 1, **snap}
                shown = sorted(role(w, caption) for w in mine if self.visible(w, snap))
                self.assertEqual(shown, sorted(expect), f"{slot}, {state}")


if __name__ == "__main__":
    unittest.main()
