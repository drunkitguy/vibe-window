#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate the Tears of the Kingdom companion package files (docs/PLAN.md T4).

Writes packages/TearsOfTheKingdom/dualscreen/manifest.json, assets/map-blank.png,
ASSETS.provenance.json and totk_font.txt. With --check, compares them with what is committed
instead (exit 1 on a difference). Std-lib only; run with `python3 -I`. Nothing from the game is
written: pictures, names and the font come from the player's own files through the module at
runtime.
"""
import argparse
import json
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "packages/TearsOfTheKingdom/dualscreen"
CANVAS_W, CANVAS_H = 1240, 1080

BG = "#FF101418"
PANEL = "#FF1A2028"
FRAME = "#FF2E3842"
TEXT = "#FFE6ECF2"
DIM = "#FFA8B2BC"
ACCENT = "#FFE8C46A"
# The game font (PLAN.md T3a' B4): the module decodes this spec (totk_font.h) and serves the atlas.
FONT_SPEC = "body:48"

# Pages in totk.ui.page order: (id, tab caption, the domain whose .source/.valid the page shows).
PAGES = [("gear", "Gear", "gear"), ("abilities", "Abilities", "ability"),
         ("inventory", "Inventory", "inv"), ("map", "Map", "map")]
# Gear cards: (slot <s>, caption, the value key and its prefix). No armor row (no armor equip key).
SLOTS = [("weapon", "WEAPON", "life", "Durability "), ("bow", "BOW", "life", "Durability "),
         ("shield", "SHIELD", "life", "Durability "), ("arrow", "ARROWS", "count", "x")]
FUSES = ("weapon", "shield")      # only these fuse
EFFECTS = ("weapon", "bow", "shield")  # the module publishes .effect for these only
# Status strip: (caption, key, extra value keys).
STATUS = [("Hearts", "totk.status.hearts",
           {"max_bind": "totk.status.max_hearts", "max_sep": " / "}),
          ("Max stamina", "totk.status.max_stamina", {}),
          ("Rupees", "totk.status.rupees", {"group": True}),
          ("Max battery", "totk.status.max_battery", {})]


def exists(*keys):
    """Expression that is 1 while every key is published and missing otherwise, so a need_bind on
    it hides a widget whose value is unknown (a value widget would draw an absent key as 0)."""
    return " + ".join(f"{k} * 0" for k in keys) + " + 1"


def chrome(page_index, domain):
    """Widgets every page has: source line, tab bar; the notice goes last (on top)."""
    widgets = [
        {"type": "label", "rect": [24, 920, 1192, 40], "text_bind": f"totk.{domain}.source",
         "text_map": {"1": "From last save", "2": "Live"}, "color": DIM,
         "need_bind": f"totk.{domain}.valid"},
        {"type": "rect", "rect": [0, 968, 1240, 112], "bg": "#FF0C1014", "color": "#00000000"},
    ]
    for k, (_, caption, _) in enumerate(PAGES):
        current = k == page_index
        widgets.append({"type": "button", "rect": [310 * k, 968, 310, 112], "text": caption,
                        "text_scale": 4, "align": "center", "on_tap": f"ui_page_{k}",
                        "bg": FRAME if current else "#FF161B21",
                        "color": TEXT if current else "#FF8A96A3"})
    return widgets


def notice():
    """The T1 notice, on top of everything while the module is not ready (wrong build, module
    error, checksum or platform); absent totk.ready reads as 0, so it shows."""
    gate = {"hide_bind": "totk.ready", "hide_eq": 1}
    return [
        {"type": "rect", "rect": [0, 0, 1240, 1080], "bg": BG, "color": "#00000000",
         "input_block": True, **gate},
        {"type": "label", "rect": [100, 350, 1040, 150], "text": "Companion unavailable",
         "text_scale": 5, "color": TEXT, "wrap_width": 1040, **gate},
        {"type": "label", "rect": [100, 550, 1040, 220], "bind_text": "module_error_message",
         "text_scale": 3, "color": DIM, "wrap_width": 1040, "max_lines": 4, **gate},
    ]


def gear_page(derived, actions):
    widgets = [{"type": "rect", "rect": [0, 0, 1240, 120], "bg": PANEL, "color": "#00000000",
                "need_bind": "totk.status.valid"}]
    for k, (caption, key, extra) in enumerate(STATUS):
        x = 310 * k
        has = f"ui.has.{key[5:]}"
        derived.append({"name": has, "expr": exists(key, *([extra["max_bind"]]
                                                           if "max_bind" in extra else []))})
        widgets.append({"type": "label", "rect": [x + 24, 16, 270, 30], "text": caption,
                        "color": DIM, "need_bind": "totk.status.valid"})
        widgets.append({"type": "value", "rect": [x + 24, 56, 270, 50], "bind": key,
                        "text_scale": 5, "color": TEXT, "need_bind": has, **extra})
    for k, (slot, caption, value_field, prefix) in enumerate(SLOTS):
        x = 24 + 300 * k
        g = f"totk.gear.{slot}"
        known, none, has_value = f"ui.gear.{slot}.known", f"ui.gear.{slot}.none", \
            f"ui.gear.{slot}.has_value"
        # .present: 1 the item, 0 "None", absent nothing (need_bind on a missing value hides).
        derived.append({"name": known, "expr": exists(f"{g}.present")})
        derived.append({"name": none, "cmp": "eq", "a": f"{g}.present", "b": 0})
        derived.append({"name": has_value, "expr": exists(f"{g}.{value_field}")})
        actions[f"gear_swap_{k}"] = {"kind": "module", "action": "totk.gear.swap", "argument": k}
        widgets += [
            # Slot caption, 10 px text 5 px clear of the strip (ends at 120) and the card (140).
            {"type": "label", "rect": [x + 4, 125, 276, 10], "text": caption, "text_scale": 2,
             "color": DIM, "need_bind": known},
            {"type": "rect", "rect": [x, 140, 284, 430], "bg": PANEL, "color": FRAME,
             "on_hold": f"gear_swap_{k}", "need_bind": known},
            {"type": "image", "rect": [x + 62, 160, 160, 160], "src_bind": f"{g}.icon",
             "need_bind": f"{g}.present"},
            {"type": "label", "rect": [x + 12, 330, 260, 90], "bind_text": f"{g}.name",
             "color": TEXT, "wrap_width": 260, "max_lines": 2, "need_bind": f"{g}.present"},
            {"type": "value", "rect": [x + 12, 430, 260, 40], "bind": f"{g}.{value_field}",
             "text": prefix, "color": TEXT, "need_bind": has_value},
            # Centre-aligned text is centred on rect x (the runtime's anchor), here the card's.
            {"type": "label", "rect": [x + 142, 300, 0, 40], "text": "None", "text_scale": 4,
             "align": "center", "color": DIM, "need_bind": none},
        ]
        if slot in FUSES:
            widgets += [
                {"type": "image", "rect": [x + 190, 160, 64, 64], "src_bind": f"{g}.fuse_icon",
                 "need_bind": f"{g}.fuse"},
                {"type": "label", "rect": [x + 12, 476, 260, 36], "bind_text": f"{g}.fuse_name",
                 "color": DIM, "wrap_width": 260, "max_lines": 1, "need_bind": f"{g}.fuse"},
            ]
        if slot in EFFECTS:
            widgets.append({"type": "label", "rect": [x + 12, 520, 260, 36],
                            "bind_text": f"{g}.effect", "color": ACCENT, "wrap_width": 260,
                            "max_lines": 1, "need_bind": f"{g}.present"})
    widgets.append({"type": "label", "rect": [620, 400, 0, 60], "text": "No save data yet",
                    "text_scale": 5, "align": "center", "color": DIM,
                    "need_bind": "!totk.gear.valid"})
    return widgets


def placeholder_page():
    """Abilities, Inventory and Map are built in T5-T7; until then their tab shows this."""
    return [{"type": "label", "rect": [620, 400, 0, 60], "text": "Coming in a later update",
             "text_scale": 5, "align": "center", "color": DIM}]


def manifest():
    derived, actions = [], {}
    for k in range(len(PAGES)):
        actions[f"ui_page_{k}"] = {"kind": "module", "action": "totk.ui.page", "argument": k}
    pages = []
    for k, (page_id, _, domain) in enumerate(PAGES):
        body = gear_page(derived, actions) if page_id == "gear" else placeholder_page()
        pages.append({"id": page_id, "widgets": body + chrome(k, domain) + notice()})
    return {
        "format": 1,
        "title_id": "0100F2C0115B6000",
        "name": "Tears of the Kingdom companion",
        "min_runtime": 22,
        "requires_module": True,
        "canvas_w": CANVAS_W,
        "canvas_h": CANVAS_H,
        "background": BG,
        "font": "file:totk_font.txt",
        "font_atlas": f"module:totk:font/{FONT_SPEC}",
        "haptics": {"enabled": True, "respect_system": True, "tap": "click", "drop": "confirm",
                    "refused": "reject"},
        "pages": pages,
        "actions": actions,
        "derived": derived,
        # BotW's page_binds values [S2]: the module's totk.ui.page picks the page.
        "page_binds": [{"point": "totk.ui.page", "equals": k,
                        "when_equal": {"page": page_id, "transition": "fade", "duration_ms": 130,
                                       "easing": "ease_in_out"}}
                       for k, (page_id, _, _) in enumerate(PAGES)],
    }


def blank_png():
    """1x1 fully transparent RGBA PNG."""
    def chunk(kind, data):
        return (struct.pack(">I", len(data)) + kind + data +
                struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))
    return (b"\x89PNG\r\n\x1a\n" +
            chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(b"\x00\x00\x00\x00\x00", 9)) +
            chunk(b"IEND", b""))


def outputs():
    def dump(value):
        return (json.dumps(value, indent=1, ensure_ascii=False) + "\n").encode("utf-8")
    provenance = {"format": 1, "files": {"assets/map-blank.png": {
        "origin": "generated", "source": "tools/totk/gen_manifest.py -- 1x1 transparent"}}}
    return {"manifest.json": dump(manifest()), "assets/map-blank.png": blank_png(),
            "ASSETS.provenance.json": dump(provenance),
            "totk_font.txt": f"{FONT_SPEC}\n".encode()}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true",
                        help="compare with the committed files instead of writing them")
    args = parser.parse_args(argv)
    stale = []
    for name, data in outputs().items():
        path = OUT / name
        if args.check:
            if not path.is_file() or path.read_bytes() != data:
                stale.append(name)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    for name in stale:
        print(f"gen_manifest: {OUT / name} differs from the generator's output", file=sys.stderr)
    return 1 if stale else 0


if __name__ == "__main__":
    sys.exit(main())
