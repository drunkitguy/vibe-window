#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate the Tears of the Kingdom companion package files (docs/PLAN.md T4, T4v, R5).

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

# Tokens [docs/PLAN.md R5]: fallback colours under or instead of the game's textures. UNVERIFIED
# until T8a gives the BFLYT material colours.
BG = "#FF0A0E0D"
PANEL = "#E6121815"
EDGE = "#FF3E4A36"
TEXT = "#FFECE5C8"
DIM = "#FF8F907F"
VALUE = "#FFE9C46A"
SELECT = "#FF6FF0C0"
HEART = "#FFE0464B"
STAMINA = "#FF74D14C"
LOCKED = "#66FFFFFF"
CLEAR = "#00000000"
# The game font (PLAN.md T3a' B4): the module decodes this spec (totk_font.h) and serves the atlas.
FONT_SPEC = "body:48"

# Pages in totk.ui.page order: (id, tab caption, the domain whose .source the page shows).
PAGES = [("home", "Inventory", "gear"), ("abilities", "Abilities", "ability"),
         ("map", "Map", "map")]
# Header tabs left to right (page indices) and their x; L/R go to the screen neighbours, wrapping.
TAB_ORDER = [2, 0, 1]
TAB_X = {2: 140, 0: 470, 1: 800}
# Gear cards in the equipment panel, top to bottom: (slot <s>, caption, card y).
CARDS = [("weapon", "WEAPON", 300), ("bow", "BOW", 470), ("shield", "SHIELD", 640)]
FUSES = ("weapon", "shield")  # only these fuse
# Inventory tabs in game order (PLAN.md "Data model"): (fallback label, name).
CATEGORIES = [("WPN", "Weapons"), ("BOW", "Bows and Arrows"), ("SHD", "Shields"),
              ("ARM", "Armor"), ("MAT", "Materials"), ("FOOD", "Food"),
              ("ZON", "Zonai Devices"), ("KEY", "Key Items")]
# Abilities in totk.ability.select order: (<a>, short name, the save's Obj_<X>).
ABILITIES = [("ultrahand", "Ultrahand", "UltraHand"), ("fuse", "Fuse", "OneTouchBond"),
             ("ascend", "Ascend", "Tooreroof"), ("recall", "Recall", "ReverseRecorder"),
             ("autobuild", "Autobuild", "AutoBuilder"), ("camera", "Camera", "Camera"),
             ("travel", "Travel", "Warp")]

# The game's UI textures by role [PLAN.md R2, R5 "Asset list"]: extra image keys (src, fill,
# slice, tint), drawn over the role's widget fallback. Every entry is UNVERIFIED until the T8a
# dump confirms it; None = the name is UNKNOWN (T8a): only the fallback is drawn, never a guessed
# name. A set entry that does not load draws nothing, and the fallback under it still reads.
TEX = {role: None for role in (
    "header", "tab_on", "slot", "cursor", "detail", "badge", "equipped", "equip_panel", "plate",
    "bar_bg", "heart", "stamina", "rupee", "battery", *(f"cat_{k}" for k in range(8)))}
# R5's first candidate for the ability icons: the item icon of the save's Obj_<X> (UNVERIFIED).
TEX.update({f"ability_{a}": {"src": f"romfs:/UI/Tex/Icon/Obj_{x}.bntx.zs"}
            for a, _, x in ABILITIES})


def textured(role, rect, fallback, **extra):
    """The fallback widgets, then TEX[role] over them at `rect` with `extra` (gates, tint)."""
    # shortcut: opaque fallbacks (the heart and stamina squares) show through a texture with
    # transparent parts; the T8a commit that fills TEX must drop or move those fallbacks.
    tex = TEX[role]
    return fallback + ([{"type": "image", "rect": rect, **tex, **extra}] if tex else [])


def panel(rect, role=None, **extra):
    """A `panel` rect with a 2 px `edge` frame, under TEX[role] stretched or 9-sliced over it."""
    box = [{"type": "rect", "rect": rect, "bg": PANEL, "color": EDGE, **extra}]
    return textured(role, rect, box, **extra) if role else box


def exists(*keys):
    """Expression that is 1 while every key is published and missing otherwise, so a need_bind on
    it hides a widget whose value is unknown (a value widget would draw an absent key as 0)."""
    return " + ".join(f"{k} * 0" for k in keys) + " + 1"


def has(derived, key, *more):
    """need_bind name that is 1 while `key` (and `more`) are published."""
    name = f"ui.has.{key[5:]}"
    if all(d["name"] != name for d in derived):
        derived.append({"name": name, "expr": exists(key, *more)})
    return name


def header(page_index, domain):
    """Widgets every page has: header with the three tabs and L/R, the source line."""
    pos = TAB_ORDER.index(page_index)
    left = TAB_ORDER[(pos - 1) % len(TAB_ORDER)]
    right = TAB_ORDER[(pos + 1) % len(TAB_ORDER)]
    # Swiping the header left (finger moving left) turns to the right neighbour, as R does.
    widgets = panel([0, 0, 1240, 76], "header", on_swipe_left=f"ui_page_{right}",
                    on_swipe_right=f"ui_page_{left}")
    for k, (_, caption, _) in enumerate(PAGES):
        rect = [TAB_X[k], 8, 300, 60]
        if k == page_index:
            ring = [{"type": "button", "rect": rect, "text": "", "pill": True, "border": 3,
                     "bg": CLEAR, "color": SELECT}]
            widgets += textured("tab_on", rect, ring)
        widgets.append({"type": "button", "rect": rect, "text": caption, "text_scale": 6,
                        "align": "center", "border": 0, "bg": CLEAR,
                        "color": TEXT if k == page_index else DIM, "on_tap": f"ui_page_{k}"})
    # L and R: the game font's button pictograms (External.bfotf U+E044 / U+E045).
    for x, glyph, target in ((40, "", left), (1152, "", right)):
        widgets.append({"type": "button", "rect": [x, 14, 48, 48], "text": glyph,
                        "text_scale": 8, "align": "center", "border": 0, "bg": CLEAR,
                        "color": TEXT, "on_tap": f"ui_page_{target}"})
    widgets.append({"type": "label", "rect": [8, 1052, 600, 24],
                    "text_bind": f"totk.{domain}.source", "text_scale": 4,
                    "text_map": {"1": "From last save", "2": "Live"}, "color": DIM,
                    "need_bind": f"totk.{domain}.valid"})
    return widgets


def notice():
    """The T1 notice, on top of everything while the module is not ready (wrong build, module
    error, checksum or platform); absent totk.ready reads as 0, so it shows."""
    gate = {"hide_bind": "totk.ready", "hide_eq": 1}
    return [
        {"type": "rect", "rect": [0, 0, 1240, 1080], "bg": BG, "color": CLEAR,
         "input_block": True, **gate},
        {"type": "label", "rect": [100, 350, 1040, 150], "text": "Companion unavailable",
         "text_scale": 5, "color": TEXT, "wrap_width": 1040, **gate},
        {"type": "label", "rect": [100, 550, 1040, 220], "bind_text": "module_error_message",
         "text_scale": 4, "color": DIM, "wrap_width": 1040, "max_lines": 4, **gate},
    ]


SHADE = "#A0000000"  # under a cell's count, as BotW's badge backdrop
TAB_OTHER = "#B3FFFFFF"  # tint of the category icons that are not the current tab [R5]
# The grid [R5]: BotW's template pattern, one cell per element of the current tab.
GRID = {"repeat": 400, "repeat_bind": "totk.inv.count", "repeat_cols": 5, "repeat_dx": 124,
        "repeat_row_dy": 124, "scroll": "inv_grid"}
GRID_REGION = {"id": "inv_grid", "rect": [8, 194, 624, 520], "count_bind": "totk.inv.count",
               "row_h": 124, "cols": 5, "reset_bind": "totk.inv.tab", "bar": DIM, "bar_w": 4}


def kind_is(key, kind, **extra):
    """Shown only while the int `key` equals `kind` (absent reads 0: hidden for kinds 1 and 2)."""
    return {"hide_bind": key, "keep_min": kind, "keep_max": kind, **extra}


def category_tabs(derived, actions):
    """Category tabs: icon (TEX.cat_<k>, full tint on the current tab, B3FFFFFF on the others)
    over a short label (text / dim), the count and the current tab's underline."""
    widgets = []
    for k, (short, _) in enumerate(CATEGORIES):
        x = 8 + 78 * k
        current = f"ui.inv.tab{k}"
        derived.append({"name": current, "cmp": "eq", "a": "totk.inv.tab", "b": k})
        actions[f"inv_tab_{k}"] = {"kind": "module", "action": "totk.inv.tab", "argument": k}
        widgets.append({"type": "button", "rect": [x, 84, 78, 72], "text": "", "border": 2,
                        "bg": PANEL, "color": EDGE, "on_tap": f"inv_tab_{k}"})
        for gate, colour, tint in ((current, TEXT, None), (f"!{current}", DIM, TAB_OTHER)):
            label = {"type": "label", "rect": [x + 39, 126, 0, 20], "text": short,
                     "text_scale": 4, "align": "center", "color": colour, "need_bind": gate}
            widgets += textured(f"cat_{k}", [x + 11, 92, 56, 56], [label], need_bind=gate,
                                **({"tint": tint} if tint else {}))
        widgets += [
            {"type": "value", "rect": [x + 70, 90, 0, 20], "bind": f"totk.inv.tab{k}.count",
             "text_scale": 4, "align": "right", "color": VALUE, "need_bind": "totk.inv.valid"},
            {"type": "rect", "rect": [x + 8, 152, 62, 4], "bg": SELECT, "color": CLEAR,
             "need_bind": current},
        ]
    return widgets


def grid(actions):
    """The current tab's items, 5 per row in the inv_grid scroll region. Tap selects (the
    module's totk.inv.sel, which the cursor follows), hold uses (refused); only the selected
    cell drags (onto the detail pane: refused use), so the others scroll and swipe."""
    actions["inv_select"] = {"kind": "module", "action": "totk.inv.select", "argument": "$payload"}
    actions["inv_use"] = {"kind": "module", "action": "totk.inv.use", "argument": 0}
    actions["inv_tab_next"] = {"kind": "module", "action": "totk.inv.tab_next", "argument": 0}
    item, cell, icon = "totk.inv.{i}", [12, 198, 116, 116], [16, 202, 108, 108]
    selected = {"hide_bind": "totk.inv.sel", "keep_min": "{i}", "keep_max": "{i}"}
    widgets = panel([8, 194, 624, 520], on_swipe_left="inv_tab_next")
    widgets += panel(cell, "slot", **GRID)
    widgets += [
        {"type": "image", "rect": icon, "src_bind": f"{item}.icon", **GRID},
        {"type": "image", "rect": icon, "src_bind": f"{item}.icon", "draggable": True,
         "payload": "{i}", "select_group": "inv", **selected, **GRID},
        {"type": "image", "rect": [16, 202, 40, 40], "src_bind": f"{item}.fuse_icon", **GRID},
        {"type": "rect", "rect": [68, 286, 56, 24], "bg": SHADE, "color": CLEAR, "keep_min": 1,
         "keep_max": 2, "hide_bind": f"{item}.kind", **GRID},
        {"type": "value", "rect": [122, 288, 0, 20], "bind": f"{item}.value", "text_scale": 4,
         "align": "right", "color": VALUE, **kind_is(f"{item}.kind", 1, **GRID)},
        {"type": "value", "rect": [122, 288, 0, 20], "bind": f"{item}.value", "text": "x",
         "text_scale": 4, "align": "right", "color": VALUE, **kind_is(f"{item}.kind", 2, **GRID)},
    ]
    mark = [{"type": "rect", "rect": [107, 205, 14, 14], "bg": SELECT, "color": CLEAR,
             "need_bind": f"{item}.equipped", **GRID}]
    widgets += textured("equipped", [104, 202, 20, 20], mark, need_bind=f"{item}.equipped",
                        **GRID)
    ring = [{"type": "rect", "rect": cell, "bg": CLEAR, "color": SELECT, "frame": 4, **selected,
             **GRID}]
    widgets += textured("cursor", cell, ring, **selected, **GRID)
    widgets.append({"type": "rect", "rect": cell, "bg": CLEAR, "color": CLEAR,
                    "on_tap": "inv_select", "on_hold": "inv_use", "payload": "{i}", **GRID})
    return widgets


def detail_pane(derived):
    """The selected item: icon (hold: refused use), name, Equipped badge, value line (durability
    or count; food's LifeRecover on the right), fuse and effect, caption. The whole pane takes a
    dragged item (refused use). Every line is gated on a selection (.kind) or its own key, so an
    absent int never reads as 0 and an absent text never draws the runtime's "-" (the module
    publishes the optional texts empty while an item is selected)."""
    sel = "totk.inv.sel"
    picked = has(derived, f"{sel}.kind")
    widgets = panel([8, 722, 624, 206], "detail")
    widgets += panel([20, 736, 128, 128], "slot", on_hold="inv_use", need_bind=picked)
    widgets += [
        {"type": "image", "rect": [20, 736, 128, 128], "src_bind": f"{sel}.icon",
         "need_bind": picked},
        {"type": "label", "rect": [164, 738, 316, 34], "bind_text": f"{sel}.name",
         "text_scale": 6, "color": TEXT, "wrap_width": 316, "max_lines": 1, "fit_text": True,
         "text_min_scale": 4, "need_bind": picked},
    ]
    # R5's badge is 108 wide; 132 holds "Equipped" at scale 4 in the built-in font too.
    badge = [{"type": "button", "rect": [488, 738, 132, 32], "text": "", "pill": True,
              "border": 2, "bg": CLEAR, "color": SELECT, "need_bind": f"{sel}.equipped"}]
    widgets += textured("badge", [488, 738, 132, 32], badge, need_bind=f"{sel}.equipped")
    widgets += [
        {"type": "label", "rect": [554, 744, 0, 20], "text": "Equipped", "text_scale": 4,
         "align": "center", "color": SELECT, "wrap_width": 128, "max_lines": 1,
         "need_bind": f"{sel}.equipped"},
        {"type": "value", "rect": [164, 778, 280, 26], "bind": f"{sel}.value",
         "text": "Durability ", "text_scale": 5, "color": VALUE, **kind_is(f"{sel}.kind", 1)},
        {"type": "value", "rect": [164, 778, 280, 26], "bind": f"{sel}.value", "text": "x",
         "text_scale": 5, "color": VALUE, **kind_is(f"{sel}.kind", 2)},
        # LifeRecover as the save stores it (unit UNVERIFIED, T8 compares with the game).
        {"type": "value", "rect": [620, 780, 0, 24], "bind": f"{sel}.hearts", "text": "Hearts ",
         "text_scale": 4, "align": "right", "color": VALUE,
         "need_bind": has(derived, f"{sel}.hearts")},
        # Fuse left, effect right: 220 px each, so "Durability Up+" fits in the built-in font.
        {"type": "label", "rect": [164, 808, 220, 24], "bind_text": f"{sel}.fuse_name",
         "text_scale": 4, "color": DIM, "wrap_width": 220, "max_lines": 1,
         "need_bind": picked},
        {"type": "label", "rect": [620, 808, 0, 24], "bind_text": f"{sel}.effect",
         "text_scale": 4, "align": "right", "color": DIM, "need_bind": picked},
        {"type": "label", "rect": [164, 836, 456, 84], "bind_text": f"{sel}.caption",
         "text_scale": 4, "color": TEXT, "wrap_width": 456, "max_lines": 3, "need_bind": picked},
        {"type": "rect", "rect": [8, 722, 624, 206], "bg": CLEAR, "color": CLEAR, "frame": 4,
         "accept_group": "inv", "drop_action": "inv_use", "highlight_color": SELECT},
    ]
    return widgets


def inventory_side(derived, actions):
    """Left side (x 0-632): category tabs, the category name, the grid with its empty states,
    the detail pane."""
    derived.append({"name": "ui.inv.empty", "cmp": "eq", "a": "totk.inv.count", "b": 0})
    return [
        *category_tabs(derived, actions),
        {"type": "label", "rect": [16, 162, 400, 28], "text_bind": "totk.inv.tab",
         "text_map": {str(k): name for k, (_, name) in enumerate(CATEGORIES)},
         "text_scale": 5, "color": TEXT, "need_bind": "totk.inv.valid"},
        *grid(actions),
        {"type": "label", "rect": [320, 430, 0, 40], "text": "No save data yet",
         "text_scale": 5, "align": "center", "color": DIM, "need_bind": "!totk.inv.valid"},
        {"type": "label", "rect": [320, 430, 0, 40], "text": "Nothing here", "text_scale": 5,
         "align": "center", "color": DIM, "need_bind": "ui.inv.empty"},
        *detail_pane(derived),
    ]


def slot_states(derived, slot, value_field):
    """The gates of a gear slot's three states from .present: 1 the item, 0 "None", absent
    nothing (need_bind on a missing value hides)."""
    g = f"totk.gear.{slot}"
    known, none, has_value = f"ui.gear.{slot}.known", f"ui.gear.{slot}.none", \
        f"ui.gear.{slot}.has_value"
    derived.append({"name": known, "expr": exists(f"{g}.present")})
    derived.append({"name": none, "cmp": "eq", "a": f"{g}.present", "b": 0})
    derived.append({"name": has_value, "expr": exists(f"{g}.{value_field}")})
    return g, known, none, has_value


def status_column(derived, actions):
    """Right column, top: hearts and stamina, then the rupee, battery and arrow chips."""
    hearts = has(derived, "totk.status.hearts", "totk.status.max_hearts")
    stamina = has(derived, "totk.status.max_stamina")
    widgets = panel([640, 84, 592, 120])
    # Bars end at x 1032 (R5: 1072) so a right-aligned "Max 30000" at scale 5 fits beside them
    # in the built-in font as well.
    for y, role, colour, bar, value in (
            (96, "heart", HEART, {"bind": "totk.status.hearts",
                                  "max_bind": "totk.status.max_hearts", "need_bind": hearts},
             {"bind": "totk.status.hearts", "max_bind": "totk.status.max_hearts",
              "max_sep": "/", "need_bind": hearts}),
            (150, "stamina", STAMINA, {"bind": "totk.status.max_stamina",
                                       "max_bind": "totk.status.max_stamina",
                                       "need_bind": stamina},
             {"bind": "totk.status.max_stamina", "text": "Max ", "need_bind": stamina})):
        widgets += textured(role, [656, y, 44, 44], [
            {"type": "rect", "rect": [664, y + 8, 28, 28], "bg": colour, "color": CLEAR}])
        widgets += [
            {"type": "bar", "rect": [712, y + 8, 320, 22], "bg": BG, "color": colour, **bar},
            {"type": "value", "rect": [1220, y + 2, 0, 32], "text_scale": 5, "align": "right",
             "color": VALUE, **value},
        ]
    g, known, none, has_count = slot_states(derived, "arrow", "count")
    actions["gear_swap_3"] = {"kind": "module", "action": "totk.gear.swap", "argument": 3}
    chips = [
        (640, "rupee", "Rupees", "totk.status.valid",
         {"bind": "totk.status.rupees", "group": True,
          "need_bind": has(derived, "totk.status.rupees")}),
        (840, "battery", "Battery", "totk.status.valid",
         {"bind": "totk.status.max_battery", "div": 100,
          "need_bind": has(derived, "totk.status.max_battery")}),
        (1040, None, "Arrows", known,
         {"bind": f"{g}.count", "text": "x", "need_bind": has_count}),
    ]
    for x, role, caption, gate, value in chips:
        chip = {"type": "rect", "rect": [x, 212, 192, 64], "bg": PANEL, "color": EDGE}
        if role is None:
            chip["on_hold"] = "gear_swap_3"
        # A 32 px icon slot leaves 140 px for "999,999" at scale 5 in the built-in font.
        icon = [x + 10, 228, 32, 32]
        widgets.append(chip)
        if role:  # the caption names the chip, so a missing texture needs no fallback
            widgets += textured(role, icon, [])
        else:  # the arrow icon's frame, under its src_bind picture
            widgets.append({"type": "rect", "rect": icon, "bg": CLEAR, "color": EDGE})
            widgets.append({"type": "image", "rect": icon, "src_bind": f"{g}.icon",
                            "need_bind": f"{g}.present"})
            widgets.append({"type": "label", "rect": [x + 48, 246, 136, 20], "text": "None",
                            "text_scale": 4, "color": DIM, "need_bind": none})
        widgets += [
            {"type": "label", "rect": [x + 48, 220, 136, 20], "text": caption, "text_scale": 4,
             "color": DIM, "need_bind": gate},
            {"type": "value", "rect": [x + 48, 244, 136, 26], "text_scale": 5, "color": VALUE,
             **value},
        ]
    return widgets


def equipment(derived, actions):
    """Right column, below: the equipment panel with the weapon's plate (no 3D Link, R3), the
    weapon, bow and shield cards, and the bow's and shield's effects."""
    widgets = panel([640, 284, 592, 644], "equip_panel")
    w = "totk.gear.weapon"
    plate = [{"type": "rect", "rect": [800, 300, 272, 612], "bg": BG, "color": EDGE}]
    widgets += textured("plate", [800, 300, 272, 612], plate)
    widgets += [
        {"type": "image", "rect": [808, 420, 256, 256], "src_bind": f"{w}.icon",
         "need_bind": f"{w}.present"},
        {"type": "image", "rect": [1000, 420, 72, 72], "src_bind": f"{w}.fuse_icon",
         "need_bind": f"{w}.fuse"},
        {"type": "label", "rect": [936, 700, 0, 60], "bind_text": f"{w}.name", "text_scale": 5,
         "align": "center", "color": TEXT, "wrap_width": 256, "max_lines": 2, "line_gap": 8,
         "need_bind": f"{w}.present"},
        {"type": "label", "rect": [936, 768, 0, 26], "bind_text": f"{w}.effect",
         "text_scale": 4, "align": "center", "color": VALUE, "wrap_width": 256, "max_lines": 1,
         "need_bind": f"{w}.present"},
        {"type": "label", "rect": [936, 800, 0, 24], "bind_text": f"{w}.fuse_name",
         "text_scale": 4, "align": "center", "color": DIM, "wrap_width": 256, "max_lines": 1,
         "need_bind": f"{w}.fuse"},
        {"type": "label", "rect": [936, 540, 0, 40], "text": "No save data yet",
         "text_scale": 4, "align": "center", "color": DIM, "need_bind": "!totk.gear.valid"},
    ]
    for k, (slot, caption, y) in enumerate(CARDS):
        g, known, none, has_life = slot_states(derived, slot, "life")
        actions[f"gear_swap_{k}"] = {"kind": "module", "action": "totk.gear.swap", "argument": k}
        card = [{"type": "rect", "rect": [1100, y + 24, 112, 112], "bg": BG, "color": EDGE,
                 "on_hold": f"gear_swap_{k}", "need_bind": known}]
        widgets.append({"type": "label", "rect": [1156, y, 0, 22], "text": caption,
                        "text_scale": 4, "align": "center", "color": DIM, "need_bind": known})
        widgets += textured("slot", [1100, y + 24, 112, 112], card, need_bind=known)
        widgets += [
            {"type": "image", "rect": [1104, y + 28, 104, 104], "src_bind": f"{g}.icon",
             "need_bind": f"{g}.present"},
            # Centre-aligned text is centred on rect x (the runtime's anchor), here the card's.
            {"type": "label", "rect": [1156, y + 70, 0, 20], "text": "None", "text_scale": 4,
             "align": "center", "color": DIM, "need_bind": none},
            {"type": "value", "rect": [1156, y + 140, 0, 24], "bind": f"{g}.life",
             "text_scale": 4, "align": "center", "color": VALUE, "need_bind": has_life},
        ]
        if slot in FUSES:
            widgets.append({"type": "image", "rect": [1172, y + 24, 40, 40],
                            "src_bind": f"{g}.fuse_icon", "need_bind": f"{g}.fuse"})
        if slot != "weapon":  # the weapon's effect is on the plate
            widgets.append({"type": "label", "rect": [652, y + 70, 140, 52],
                            "bind_text": f"{g}.effect", "text_scale": 4, "color": VALUE,
                            "wrap_width": 140, "max_lines": 2, "need_bind": f"{g}.present"})
    return widgets


def ability_bar(actions):
    """Bottom: the seven abilities, owned at full colour, others dimmed; taps are refused."""
    widgets = panel([0, 940, 1240, 108], "bar_bg")
    for k, (a, name, _) in enumerate(ABILITIES):
        x, owned = 18 + 172 * k, f"totk.ability.{a}.owned"
        # Locked: the save says not owned (no save: neither variant shows).
        locked = {"need_bind": "totk.ability.valid", "hide_bind": owned, "hide_eq": 1}
        actions[f"ability_select_{k}"] = {"kind": "module", "action": "totk.ability.select",
                                          "argument": k}
        icon = [x + 54, 946, 64, 64]
        widgets.append({"type": "rect", "rect": icon, "bg": CLEAR, "color": EDGE,
                        "on_tap": f"ability_select_{k}"})
        if TEX[f"ability_{a}"]:
            widgets += [{"type": "image", "rect": icon, **TEX[f"ability_{a}"],
                         "need_bind": owned},
                        {"type": "image", "rect": icon, **TEX[f"ability_{a}"],
                         "tint": LOCKED, **locked}]
        widgets += [
            {"type": "label", "rect": [x + 86, 1014, 0, 22], "text": name, "text_scale": 4,
             "align": "center", "color": TEXT, "need_bind": owned},
            {"type": "label", "rect": [x + 86, 1014, 0, 22], "text": name, "text_scale": 4,
             "align": "center", "color": DIM, **locked},
        ]
    return widgets


def home_page(derived, actions):
    return (inventory_side(derived, actions) + status_column(derived, actions) +
            equipment(derived, actions) + ability_bar(actions))


def later_page(domain):
    """Abilities (T5) and Map (T7): the frame and the empty state until their packet."""
    return [*panel([16, 92, 1208, 944]),
            {"type": "label", "rect": [620, 480, 0, 60], "text": "No save data yet",
             "text_scale": 5, "align": "center", "color": DIM,
             "need_bind": f"!totk.{domain}.valid"},
            {"type": "label", "rect": [620, 480, 0, 60], "text": "Coming in a later update",
             "text_scale": 5, "align": "center", "color": DIM,
             "need_bind": f"totk.{domain}.valid"}]


def manifest():
    derived, actions = [], {}
    for k in range(len(PAGES)):
        actions[f"ui_page_{k}"] = {"kind": "module", "action": "totk.ui.page", "argument": k}
    pages = []
    for k, (page_id, _, domain) in enumerate(PAGES):
        body = home_page(derived, actions) if page_id == "home" else later_page(domain)
        page = {"id": page_id, "widgets": body + header(k, domain) + notice()}
        if page_id == "home":
            page["scrolls"] = [GRID_REGION]
        pages.append(page)
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
