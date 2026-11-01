#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Check the TotK manifest against the Eden Duo runtime and the module's key list (PLAN.md T4).

python3 -I tools/totk/check_manifest.py --eden <eden-duo checkout>
Fails (exit 1) when
- an object key (not starting with "_") is not a "key" string literal in $E/src/core/mods/*.cpp;
- an action kind is not page, module or view_reset, or a module action is not in kActionNames;
- an on_tap/on_hold/on_swipe_*/drop_action/enforce names an undefined action, a page action or
  page bind an undefined page, a widget scroll an undefined region;
- a rect is not 4 numbers or leaves the canvas, or hide_eq is not an integer;
- a bind starting "totk." is not a key or family in native/modules/totk_keys.h;
- the file is over 4 MiB.
Warns when a key listed in totk_keys.h is bound nowhere. Std-lib only.
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "packages/TearsOfTheKingdom/dualscreen/manifest.json"
KEYS_HEADER = ROOT / "native/modules/totk_keys.h"
MAX_BYTES = 4 << 20
KINDS = {"page", "module", "view_reset"}
ACTION_REFS = ("on_tap", "on_hold", "on_swipe_left", "on_swipe_right", "on_swipe_up",
               "on_swipe_down", "drop_action")
# Objects whose keys are names (actions, text_map values, ...), not runtime keys.
NAME_MAPS = {"actions", "text_map", "tables", "sprite_map", "flags"}
TOKEN = re.compile(r"(?<![A-Za-z0-9_.])totk\.[A-Za-z0-9_.{}]*[A-Za-z0-9_}]")


def runtime_literals(eden):
    mods = Path(eden) / "src/core/mods"
    sources = sorted(mods.glob("*.cpp"))
    if not sources:
        raise SystemExit(f"check_manifest: no runtime sources in {mods}")
    literals = set()
    for path in sources:
        literals.update(re.findall(r'"((?:[^"\\\n]|\\.)*)"', path.read_text(errors="replace")))
    return literals


def parse_keys_header(text):
    """(key patterns as {family: regex}, module action names) from totk_keys.h."""
    text = re.sub(r"//[^\n]*", "", text)  # comments quote "totk." too
    block = re.search(r"kActionNames\[[^\]]*\]\s*=\s*\{(.*?)\};", text, re.S)
    if not block:
        raise SystemExit("check_manifest: kActionNames not found in totk_keys.h")
    actions = re.findall(r'"([^"]*)"', block.group(1))
    rest = text[:block.start()] + text[block.end():]

    def names(array):
        found = re.search(array + r"\[\]\s*=\s*\{(.*?)\};", rest, re.S)
        if not found:
            raise SystemExit(f"check_manifest: {array} not found in totk_keys.h")
        return re.findall(r'"([^"]*)"', found.group(1))
    holders = {"<s>": "(?:" + "|".join(names("kGearSlotNames")) + ")",
               "<a>": "(?:" + "|".join(names("kAbilityNames")) + ")",
               "{i}": r"(?:\{i\}|0|[1-9][0-9]*)", "{k}": "[0-7]"}
    patterns = {}
    for key in re.findall(r'"(totk\.[^"]*)"', rest):
        regex = re.escape(key)
        for holder, alternative in holders.items():
            regex = regex.replace(re.escape(holder), alternative)
        patterns[key] = re.compile(regex)
    return patterns, set(actions)


def is_number(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def check(manifest, literals, patterns, module_actions, size=0):
    """Returns (errors, warnings)."""
    errors, warnings = [], []
    if size > MAX_BYTES:
        errors.append(f"manifest is {size} bytes, over {MAX_BYTES}")
    canvas_w, canvas_h = manifest.get("canvas_w", 0), manifest.get("canvas_h", 0)

    def walk_keys(value, where, names=False):
        if isinstance(value, dict):
            for key, child in value.items():
                if not names and not key.startswith("_") and key not in literals:
                    errors.append(f"{where}: key '{key}' is not a runtime key literal")
                walk_keys(child, f"{where}.{key}", key in NAME_MAPS and not names)
        elif isinstance(value, list):
            for i, child in enumerate(value):
                walk_keys(child, f"{where}[{i}]")
    walk_keys(manifest, "manifest")

    actions = manifest.get("actions", {})
    pages = manifest.get("pages", [])
    page_ids = {p.get("id") for p in pages}
    for name, action in actions.items():
        kind = action.get("kind")
        if kind not in KINDS:
            errors.append(f"action '{name}': kind '{kind}' is not one of {sorted(KINDS)}")
        if kind == "module" and action.get("action") not in module_actions:
            errors.append(f"action '{name}': module action '{action.get('action')}' is not in "
                          "kActionNames (totk_keys.h)")
        if kind == "page" and action.get("page") not in page_ids:
            errors.append(f"action '{name}': page '{action.get('page')}' is not defined")
    for i, entry in enumerate(manifest.get("enforce", [])):
        if entry.get("action") not in actions:
            errors.append(f"enforce[{i}]: action '{entry.get('action')}' is not defined")
    for i, bind in enumerate(manifest.get("page_binds", [])):
        for branch in ("when_equal", "when_not_equal"):
            target = bind.get(branch)
            if target is not None and target.get("page") not in page_ids:
                errors.append(f"page_binds[{i}].{branch}: page '{target.get('page')}' is not "
                              "defined")

    def check_rect(rect, where):
        if not (isinstance(rect, list) and len(rect) == 4 and all(map(is_number, rect))):
            errors.append(f"{where}: rect {rect!r} is not 4 numbers")
        elif (rect[0] < 0 or rect[1] < 0 or rect[2] < 0 or rect[3] < 0 or
              rect[0] + rect[2] > canvas_w or rect[1] + rect[3] > canvas_h):
            errors.append(f"{where}: rect {rect} leaves the {canvas_w}x{canvas_h} canvas")

    for page in pages:
        scrolls = page.get("scrolls", [])
        regions = {s.get("id") for s in scrolls}
        for s in scrolls:
            check_rect(s.get("rect"), f"page '{page.get('id')}' scroll '{s.get('id')}'")
        for i, w in enumerate(page.get("widgets", [])):
            where = f"page '{page.get('id')}' widget {i}"
            check_rect(w.get("rect"), where)
            if "hide_eq" in w and not (isinstance(w["hide_eq"], int) and
                                       not isinstance(w["hide_eq"], bool)):
                errors.append(f"{where}: hide_eq {w['hide_eq']!r} is not an integer")
            for ref in ACTION_REFS:
                if ref in w and w[ref] not in actions:
                    errors.append(f"{where}: {ref} '{w[ref]}' is not a defined action")
            if isinstance(w.get("scroll"), str) and w["scroll"] not in regions:
                errors.append(f"{where}: scroll '{w['scroll']}' is not a region of its page")

    used = set()

    def walk_binds(value, where):
        if isinstance(value, dict):
            for k, child in value.items():
                # a module action's "action" is an action name (checked above), not a key
                if not (k == "action" and value.get("kind") == "module"):
                    walk_binds(child, f"{where}.{k}")
        elif isinstance(value, list):
            for i, child in enumerate(value):
                walk_binds(child, f"{where}[{i}]")
        elif isinstance(value, str):
            for token in TOKEN.findall(value):
                hits = [f for f, regex in patterns.items() if regex.fullmatch(token)]
                if not hits:
                    errors.append(f"{where}: '{token}' is not a key in totk_keys.h")
                used.update(hits)
    walk_binds(manifest, "manifest")
    for family in patterns:
        if family not in used:
            warnings.append(f"'{family}' (totk_keys.h) is bound by no page")
    return errors, warnings


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--eden", required=True, help="Eden Duo checkout (src/core/mods)")
    parser.add_argument("--manifest", default=str(MANIFEST))
    parser.add_argument("--keys", default=str(KEYS_HEADER))
    args = parser.parse_args(argv)
    raw = Path(args.manifest).read_bytes()
    patterns, module_actions = parse_keys_header(Path(args.keys).read_text())
    errors, warnings = check(json.loads(raw), runtime_literals(args.eden), patterns,
                             module_actions, len(raw))
    for w in warnings:
        print(f"check_manifest: warning: {w}", file=sys.stderr)
    for e in errors:
        print(f"check_manifest: error: {e}", file=sys.stderr)
    print(f"check_manifest: {len(errors)} errors, {len(warnings)} warnings")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
